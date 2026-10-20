import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import qualification.BootRecipeMain;

import java.nio.file.Files;
import java.nio.file.Path;

/** Checks the actual Boot guide with resolved JARs and no IOC runtime. */
public final class LibraryConsumer {
    /**
     * Loads the copied application recipe and checks the file it writes.
     * @param args unused
     * @throws Exception if compilation inputs, configuration or JSON output are invalid
     */
    public static void main(String[] args) throws Exception {
        var recipe = Path.of("src/main/resources/recipe").toAbsolutePath();
        BootRecipeMain.main(new String[]{recipe.toString(), "supported"});
        require(Files.readString(recipe.resolve("status.txt")).isEmpty(), "no encoder warnings or errors");
        var lines = Files.readAllLines(Path.of(System.getenv("COLLECTOR_LOG_FILE")));
        require(lines.size() == 5, "five supported recipe records");
        var mapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
        for (String line : lines) {
            var record = mapper.readTree(line);
            require("feeds-collector".equals(record.at("/service/name").textValue()), "service metadata");
            require("feeds-collector".equals(record.at("/event/dataset").textValue()), "application dataset");
            require(!record.has("ioc"), "no IOC vocabulary");
        }
        var collection = mapper.readTree(lines.get(0));
        require(collection.at("/event/duration").isIntegralNumber()
                && collection.at("/event/duration").longValue() == 500L, "native nanoseconds");
        require(collection.at("/collector/items").isIntegralNumber()
                && collection.at("/collector/items").longValue() == 12L, "native count");
        require(collection.at("/collector/retry").isBoolean()
                && !collection.at("/collector/retry").booleanValue(), "native boolean");
        require("[redacted]".equals(collection.at("/collector/auth/token").textValue()), "mask");
        var omission = mapper.readTree(lines.get(1));
        require(omission.at("/event/outcome").isMissingNode()
                && omission.at("/event/duration").isMissingNode(), "omission");
        require(IllegalStateException.class.getName().equals(mapper.readTree(lines.get(2))
                .at("/error/type").textValue()), "Throwable type");
        require(mapper.readTree(lines.get(2)).at("/error/stack_trace").textValue()
                .contains("synthetic connection failure"), "Throwable encoding");
        require(mapper.readTree(lines.get(3)).at("/error/stack_trace").isMissingNode(), "type-only error");
        require(!String.join("\n", lines).contains("synthetic-ambient"), "ambient token hidden");
        require(com.iocextractor.platform.logging.LogEvents.class.getProtectionDomain()
                .getCodeSource().getLocation().toString().endsWith(".jar"), "library comes from a JAR");
        System.out.println("Boot logging consumer: actual Java/YAML/XML guide and five JSON records passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
