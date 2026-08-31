package com.iocextractor.bootstrap;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** Shared lexical and reflective primitives for the strict {@code ioc.*} property shape. */
final class IocConfigurationPropertyShape {

    private IocConfigurationPropertyShape() {
    }

    static List<String> tokens(String canonical) {
        return tokens(canonical, false);
    }

    static List<String> environmentTokens(String adapted) {
        return tokens(adapted, true);
    }

    static Type listElementType(Type type) {
        if (type instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1) {
            return parameterized.getActualTypeArguments()[0];
        }
        return Object.class;
    }

    static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }

    static boolean isIndex(String token) {
        if (token.length() <= 2 || token.charAt(0) != '[' || token.charAt(token.length() - 1) != ']') {
            return false;
        }
        for (int index = 1; index < token.length() - 1; index++) {
            if (!Character.isDigit(token.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    static String kebabCase(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isUpperCase(current)) {
                result.append('-').append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static List<String> tokens(String canonical, boolean numericSegmentsAreIndexes) {
        List<String> tokens = new ArrayList<>();
        for (String part : canonical.split("\\.")) {
            int bracket = part.indexOf('[');
            if (bracket < 0) {
                tokens.add(numericSegmentsAreIndexes && part.matches("\\d+")
                        ? "[" + part + "]"
                        : part);
                continue;
            }
            if (bracket > 0) {
                tokens.add(part.substring(0, bracket));
            }
            int cursor = bracket;
            while (cursor >= 0 && cursor < part.length()) {
                int end = part.indexOf(']', cursor);
                if (end < 0) {
                    return List.of();
                }
                tokens.add(part.substring(cursor, end + 1));
                cursor = part.indexOf('[', end);
            }
        }
        return tokens;
    }
}
