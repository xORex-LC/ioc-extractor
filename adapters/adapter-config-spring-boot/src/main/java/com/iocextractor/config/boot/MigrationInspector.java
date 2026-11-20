package com.iocextractor.config.boot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Observes old names without visiting property sources, reading values or changing Binder behavior. */
public final class MigrationInspector {

    private MigrationInspector() { }

    /**
     * Matches service descriptors against declarations already collected for the same namespace.
     * Fixed record paths and {@code []} list placeholders are supported. Map wildcards,
     * migration chains and overlapping old paths are rejected. Shadowed and null declarations
     * remain observable; callers must not infer a conflict between effective bound values.
     *
     * @param inspection snapshot for the descriptors' namespace
     * @param migrations explicit old-name descriptions, all in one namespace
     * @return observations retaining original coverage and ambiguity
     * @throws IllegalArgumentException if catalog paths overlap, form a chain or belong
     *         to a different namespace from an inspected declaration
     */
    public static MigrationInspection inspect(PropertyInspection inspection, List<PropertyMigration> migrations) {
        Objects.requireNonNull(inspection, "inspection");
        List<Descriptor> descriptors = List.copyOf(migrations).stream().map(Descriptor::new).toList();
        validate(descriptors);
        List<MigrationInspection.Observation> observations = new ArrayList<>();
        for (PropertyInspection.Declaration declaration : inspection.declarations()) {
            List<MigrationInspection.Match> matches = new ArrayList<>();
            for (Descriptor descriptor : descriptors) {
                matches.addAll(descriptor.matches(declaration));
            }
            if (!matches.isEmpty()) {
                observations.add(new MigrationInspection.Observation(declaration, matches, resolution(declaration, matches)));
            }
        }
        return new MigrationInspection(inspection, observations);
    }

    private static MigrationInspection.Resolution resolution(PropertyInspection.Declaration declaration,
                                                              List<MigrationInspection.Match> matches) {
        if (declaration.status() == PropertyInspection.Status.UNSUPPORTED
                || declaration.source().kind() != PropertyInspection.SourceKind.PROPERTIES
                && declaration.source().kind() != PropertyInspection.SourceKind.ENVIRONMENT) {
            return MigrationInspection.Resolution.UNSUPPORTED;
        }
        Set<String> paths = new LinkedHashSet<>();
        declaration.canonicalNames().forEach(path -> paths.add(path.replace("-", "")));
        matches.forEach(match -> paths.add(match.path().replace("-", "")));
        return declaration.status() == PropertyInspection.Status.AMBIGUOUS || paths.size() > 1
                ? MigrationInspection.Resolution.AMBIGUOUS : MigrationInspection.Resolution.RESOLVED;
    }

    private static void validate(List<Descriptor> descriptors) {
        for (Descriptor left : descriptors) {
            for (Descriptor right : descriptors) {
                if (!left.path().prefix().equals(right.path().prefix())) {
                    throw new IllegalArgumentException("Migration descriptors must share one namespace");
                }
                if (left != right && (left.path().environmentShape().equals(right.path().environmentShape())
                        || left.path().contains(right.path()) || right.path().contains(left.path()))) {
                    throw new IllegalArgumentException("Old migration paths overlap");
                }
                if (left.migration().replacement().map(MigrationPath::new)
                        .map(path -> path.relaxed().equals(right.path().relaxed())).orElse(false)) {
                    throw new IllegalArgumentException("Migration chains are not supported");
                }
            }
        }
    }

    private record Descriptor(PropertyMigration migration, MigrationPath path, SchemaModel.Node root) {
        Descriptor(PropertyMigration migration) {
            this(migration, new MigrationPath(migration.path()), new MigrationPath(migration.path()).root());
        }

        List<MigrationInspection.Match> matches(PropertyInspection.Declaration declaration) {
            boolean environment = declaration.source().kind() != PropertyInspection.SourceKind.PROPERTIES;
            PropertyInspection.Declaration candidate = NameMatcher.match(path.prefix(), root, declaration.rawName(),
                    declaration.source(), environment, true);
            if (candidate == null) {
                throw new IllegalArgumentException("Migration namespace differs from an inspected declaration");
            }
            return candidate.canonicalNames().stream().filter(path::matches)
                    .map(name -> new MigrationInspection.Match(migration, name,
                            migration.replacement().map(template -> path.replacement(name, template)))).toList();
        }
    }
}
