package com.iocextractor.adapter.out.store.jdbc;

import java.util.Objects;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Explicit publication-time projections; retained history and old confirmation anchors stay literal. */
final class JdbcPublicationTimes {
    private JdbcPublicationTimes() { }

    static String changed(JdbcPrivateChanges.Effect effect) {
        var flags = new StringBuilder(",");
        for (String column : effect.table().columns()) {
            boolean changed = effect.previous() == null || !Objects.equals(effect.value(column), effect.oldValue(column));
            changed |= forcedChange(effect, column);
            if (changed) { flags.append(column).append(','); }
        }
        return flags.toString();
    }

    static String project(JdbcPublicationTable table, String column, boolean artifact) {
        String name = table.name();
        String expression = expression(name, column, artifact);
        return expression == null ? "v." + quote(column) : "CASE WHEN v." + quote(column)
                + " IS NOT NULL AND instr(v.publication_columns,'," + column + ",')>0 THEN "
                + expression + " ELSE v." + quote(column) + " END AS " + quote(column);
    }

    private static boolean forcedChange(JdbcPrivateChanges.Effect effect, String column) {
        if (effect.renewsValidity() && java.util.Set.of("_last_confirmed_at_epoch_ms", "_valid_until_epoch_ms").contains(column)) {
            return true;
        }
        if (effect.table().columns().contains("occurrences") && column.equals("last_seen_at")) {
            return !Objects.equals(effect.value("occurrences"), effect.oldValue("occurrences"));
        }
        return switch (effect.table().name()) {
            case "artifact_revision" -> column.equals("changed_at");
            case "artifact_projection_state" -> column.equals("requested_at_ms");
            case "export_slot_state" -> column.equals("updated_at_ms");
            default -> false;
        };
    }

    private static String expression(String table, String column, boolean artifact) {
        if (artifact) {
            return switch (column) {
                case "_first_confirmed_at_epoch_ms", "_last_confirmed_at_epoch_ms" -> "u.effective_millis";
                case "_valid_until_epoch_ms" -> "anchor.valid_until";
                case "_created_at" -> iso();
                default -> null;
            };
        }
        return switch (table) {
            case "artifact_revision" -> column.equals("changed_at") ? iso() : null;
            case "artifact_projection_state" -> atPublication(column, "requested_at_ms");
            case "export_slot_assignment" -> atPublication(column, "assigned_at_ms");
            case "export_slot_state" -> atPublication(column, "updated_at_ms");
            case "canonical_observation" -> atPublication(column, "started_at_ms");
            case "canonical_lifecycle_field_origin_history" -> atPublication(column, "archived_at_ms");
            case "confirmation_receipt_artifact" -> atPublication(column, "staged_at_ms");
            case "confirmation_receipt" -> switch (column) {
                case "completed_at_ms" -> "u.effective_millis";
                case "purge_after_ms" -> "u.effective_millis+(v.purge_after_ms-v.completed_at_ms)";
                default -> null;
            };
            case "canonical_observation_commit", "import_commit" -> switch (column) {
                case "effective_as_of_ms", "committed_at_ms" -> "u.effective_millis";
                case "purge_after_ms" -> "u.effective_millis+(v.purge_after_ms-v.committed_at_ms)";
                default -> null;
            };
            default -> childTime(table, column);
        };
    }

    private static String childTime(String table, String column) {
        if (table.endsWith("_history")) { return atPublication(column, "closed_at_epoch_ms"); }
        if (table.endsWith("_sources") && !table.endsWith("_history_sources")) {
            return java.util.Set.of("first_seen_at", "last_seen_at").contains(column) ? iso() : null;
        }
        return table.endsWith("_receipt_rows") ? atPublication(column, "observed_at_ms") : null;
    }

    private static String atPublication(String column, String expected) {
        return column.equals(expected) ? "u.effective_millis" : null;
    }

    private static String iso() {
        return "CASE WHEN u.effective_millis%1000=0 THEN "
                + "strftime('%Y-%m-%dT%H:%M:%SZ',u.effective_millis/1000.0,'unixepoch') ELSE "
                + "strftime('%Y-%m-%dT%H:%M:%fZ',u.effective_millis/1000.0,'unixepoch') END";
    }
}
