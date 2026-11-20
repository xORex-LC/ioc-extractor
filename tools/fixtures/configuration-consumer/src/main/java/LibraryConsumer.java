import com.iocextractor.config.boot.MigrationInspector;
import com.iocextractor.config.boot.PropertyInspection;
import com.iocextractor.config.boot.PropertyInspector;
import com.iocextractor.config.boot.PropertyMigration;
import com.iocextractor.config.boot.PropertySchema;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Exercises packaged public contracts with a collector schema unrelated to IOC. */
public final class LibraryConsumer {
    public record Collector(String requestTimeout, List<Connection> connections) { }
    public record Connection(String name) { }

    public static void main(String[] arguments) {
        var namesOnly = new EnumerablePropertySource<Object>("sensitive-source-label", new Object()) {
            @Override public String[] getPropertyNames() {
                return new String[] {"collector.requestTimeout", "collector.connections[3].old-name", "collector.unknown"};
            }
            @Override public Object getProperty(String name) {
                throw new AssertionError("inspection read a property value");
            }
        };
        var inspection = PropertyInspector.inspect(new PropertySchema("collector", Collector.class), List.of(namesOnly));
        require(inspection.complete(), "supported schema lost coverage");
        require(inspection.declarations().size() == 3, "declaration lost");
        require(inspection.declarations().stream().anyMatch(d -> d.rawName().equals("collector.requestTimeout")
                && d.status() == PropertyInspection.Status.KNOWN), "relaxed name rejected");
        require(inspection.declarations().getLast().status() == PropertyInspection.Status.UNKNOWN, "unknown accepted");
        var migration = MigrationInspector.inspect(inspection, List.of(new PropertyMigration(
                "collector.connections[].old-name", PropertyMigration.Kind.REMOVED,
                Optional.of("collector.connections[].name"))));
        require(migration.inspection().equals(inspection), "migration replaced its snapshot");
        require(migration.observations().size() == 1, "removed name not observed");
        var observation = migration.observations().getFirst();
        require(observation.declaration().status() == PropertyInspection.Status.UNKNOWN, "migration rewrote name acceptance");
        require(observation.matches().getFirst().replacement().orElseThrow().equals("collector.connections[3].name"),
                "migration did not preserve list index");
        require(!inspection.toString().contains("sensitive-source-label"), "arbitrary source label escaped");
        var shadowed = PropertyInspector.inspect(new PropertySchema("collector", Collector.class), List.of(
                new MapPropertySource("override", Map.of("collector.request-timeout", "2s")), namesOnly));
        require(shadowed.declarations().size() == 4, "shadowed declarations were discarded");
        System.out.println("INSPECTION_AND_MIGRATION_VALIDATED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
