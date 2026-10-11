import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Streams diagnostic JFR stacks; sampled allocation weights are not exact allocated bytes or elapsed time. */
public class ProcessingAllocationProfile {
    private record Metric(long events, long weight) {
        Metric add(long next) { return new Metric(events + 1, weight + next); }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) { throw new IllegalArgumentException("Expected one diagnostic JFR path"); }
        var totals = new TreeMap<String, Metric>();
        try (var recording = new RecordingFile(Path.of(arguments[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String kind = switch (event.getEventType().getName()) {
                    case "jdk.ObjectAllocationSample" -> "sampled_allocation_weight_bytes";
                    case "jdk.ExecutionSample" -> "execution_stack_samples";
                    case "jdk.NativeMethodSample" -> "native_stack_samples";
                    default -> null;
                };
                if (kind == null) { continue; }
                String method = "outside_IOC";
                if (event.getStackTrace() != null) {
                    for (var frame : event.getStackTrace().getFrames()) {
                        var candidate = frame.getMethod();
                        if (candidate.getType().getName().startsWith("com.iocextractor.")) {
                            method = candidate.getType().getName() + "." + candidate.getName() + candidate.getDescriptor();
                            break;
                        }
                    }
                }
                String key = kind + "\t" + method;
                long weight = kind.equals("sampled_allocation_weight_bytes") ? event.getLong("weight") : 1;
                totals.put(key, totals.getOrDefault(key, new Metric(0, 0)).add(weight));
            }
        }
        System.out.println("metric\tnearest_IOC_frame\tevents\tweight");
        for (Map.Entry<String, Metric> entry : totals.entrySet()) {
            System.out.println(entry.getKey() + "\t" + entry.getValue().events() + "\t" + entry.getValue().weight());
        }
    }
}
