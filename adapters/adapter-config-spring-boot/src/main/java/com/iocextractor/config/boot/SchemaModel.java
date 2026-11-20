package com.iocextractor.config.boot;

import org.springframework.boot.context.properties.bind.Name;

import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Period;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Compiles only the record/container shapes whose naming rules are qualified. */
final class SchemaModel {

    enum Kind { SCALAR, RECORD, LIST, MAP, UNSUPPORTED }

    record Field(String name, Node node) { }

    record Node(Kind kind, List<Field> fields, Node element, boolean uncertainNames) { }

    private final PropertySchema schema;
    private final Node root;
    private final List<PropertyInspection.SchemaGap> gaps = new ArrayList<>();

    SchemaModel(PropertySchema schema) {
        this.schema = schema;
        this.root = build(schema.rootType(), schema.prefix(), new LinkedHashSet<>());
    }

    List<PropertyInspection.SchemaGap> gaps() {
        return List.copyOf(gaps);
    }

    PropertyInspection.Declaration match(String name, PropertyInspection.SourceReference source,
                                          boolean environment, boolean qualified) {
        return NameMatcher.match(schema.prefix(), root, name, source, environment, qualified);
    }

    private Node build(Type type, String path, Set<Type> ancestors) {
        Class<?> raw = rawClass(type);
        if (raw == null || !ancestors.add(type)) {
            return unsupported(path, "Unresolved or recursive schema");
        }
        try {
            if (raw.isRecord()) {
                return record(raw, path, ancestors);
            }
            if (raw == List.class) {
                return list(type, path, ancestors);
            }
            if (raw == Map.class) {
                return map(type, path);
            }
            if (scalar(raw)) {
                return new Node(Kind.SCALAR, List.of(), null, false);
            }
            return unsupported(path, "Unsupported type or custom conversion");
        } finally {
            ancestors.remove(type);
        }
    }

    private Node record(Class<?> raw, String path, Set<Type> ancestors) {
        RecordComponent[] components = raw.getRecordComponents();
        Parameter[] parameters = constructorParameters(raw, components);
        List<Field> fields = new ArrayList<>();
        boolean uncertain = false;
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            String name = kebab(component.getName());
            if (parameters[i].isAnnotationPresent(Name.class)
                    || component.getName().matches(".*[A-Z]{2}.*")
                    || !component.getName().matches("[a-z][a-zA-Z0-9]*")) {
                fields.add(new Field(name, unsupported(path + "." + name, "Alias or unqualified field naming")));
                uncertain = true;
            } else {
                fields.add(new Field(name, build(component.getGenericType(), path + "." + name, ancestors)));
            }
        }
        return new Node(Kind.RECORD, List.copyOf(fields), null, uncertain);
    }

    private Node list(Type type, String path, Set<Type> ancestors) {
        if (!(type instanceof ParameterizedType parameterized)) {
            return unsupported(path, "Unresolved list element");
        }
        Type element = parameterized.getActualTypeArguments()[0];
        Class<?> raw = rawClass(element);
        if (raw == null || !(raw.isRecord() || scalar(raw))) {
            return unsupported(path, "Unsupported list element");
        }
        return new Node(Kind.LIST, List.of(), build(element, path + "[]", ancestors), false);
    }

    private Node map(Type type, String path) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            Class<?> key = rawClass(arguments[0]);
            Class<?> value = rawClass(arguments[1]);
            if (key != null && (key == String.class || key.isEnum()) && value != null && scalar(value)) {
                return new Node(Kind.MAP, List.of(), null, false);
            }
        }
        return unsupported(path, "Only flat scalar maps with String or enum keys are qualified");
    }

    private Node unsupported(String path, String reason) {
        gaps.add(new PropertyInspection.SchemaGap(path, reason));
        return new Node(Kind.UNSUPPORTED, List.of(), null, true);
    }

    private static Parameter[] constructorParameters(Class<?> raw, RecordComponent[] components) {
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
        }
        try {
            return raw.getDeclaredConstructor(types).getParameters();
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Record canonical constructor is unavailable", ex);
        }
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> raw) {
            return raw;
        }
        return type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw
                ? raw : null;
    }

    private static boolean scalar(Class<?> raw) {
        return raw.isPrimitive() || raw.isEnum() || raw == String.class || raw == Boolean.class
                || raw == Character.class || Number.class.isAssignableFrom(raw)
                || TemporalAccessor.class.isAssignableFrom(raw) || raw == Duration.class || raw == Period.class
                || Path.class.isAssignableFrom(raw) || Charset.class.isAssignableFrom(raw)
                || InetAddress.class.isAssignableFrom(raw) || raw == URI.class || raw == URL.class
                || raw == UUID.class || raw == Locale.class || Date.class.isAssignableFrom(raw) || raw == Class.class;
    }

    private static String kebab(String name) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (Character.isUpperCase(current)) {
                result.append('-').append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
