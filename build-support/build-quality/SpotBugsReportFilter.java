import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Applies the generated SpotBugs filter to an existing native XML report. */
public final class SpotBugsReportFilter {

    private SpotBugsReportFilter() {
    }

    /**
     * Filters a raw report without re-running bytecode analysis.
     *
     * @param args raw XML, generated filter XML and filtered XML paths
     * @throws Exception when the SpotBugs workflow filter cannot produce output
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: SpotBugsReportFilter <raw-xml> <filter-xml> <filtered-xml>");
        }
        Path raw = Path.of(args[0]).toAbsolutePath().normalize();
        Path filter = Path.of(args[1]).toAbsolutePath().normalize();
        Path filtered = Path.of(args[2]).toAbsolutePath().normalize();
        if (!Files.exists(raw)) {
            return;
        }
        if (!Files.isRegularFile(filter) || Files.size(filter) == 0) {
            throw new IllegalStateException("generated SpotBugs filter is missing: " + filter);
        }
        Files.createDirectories(filtered.getParent());
        Files.deleteIfExists(filtered);

        Class<?> workflowFilter = Class.forName("edu.umd.cs.findbugs.workflow.Filter");
        Method main = workflowFilter.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) new String[] {
                "-exclude", filter.toString(), raw.toString(), filtered.toString()
            });
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
        if (!Files.isRegularFile(filtered) || Files.size(filtered) == 0) {
            throw new IllegalStateException(
                    "SpotBugs workflow filter produced no output: " + filtered);
        }
    }
}
