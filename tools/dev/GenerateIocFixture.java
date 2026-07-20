import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Generates deterministic, non-routable IOC fixtures for development and load smoke tests.
 * This source file intentionally has no project dependencies and runs through the JDK source launcher.
 */
public final class GenerateIocFixture {

    private static final int SECTION_SIZE = 250;

    private GenerateIocFixture() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help()) {
            usage();
            return;
        }
        generate(options);
    }

    private static void generate(Options options) throws IOException {
        Path output = options.output().toAbsolutePath().normalize();
        Path manifest = options.manifest().toAbsolutePath().normalize();
        requireWritableTarget(output, options.force());
        requireWritableTarget(manifest, options.force());
        Files.createDirectories(output.getParent());
        Files.createDirectories(manifest.getParent());

        Path outputStaging = Files.createTempFile(output.getParent(), ".ioc-fixture-", ".tmp");
        Stats stats;
        try {
            stats = writeFixture(outputStaging, options);
            publish(outputStaging, output);
        } finally {
            Files.deleteIfExists(outputStaging);
        }

        String sha256 = hex(digest("SHA-256", Files.readAllBytes(output)));
        Path manifestStaging = Files.createTempFile(manifest.getParent(), ".ioc-manifest-", ".tmp");
        try {
            writeManifest(manifestStaging, output, sha256, options, stats);
            publish(manifestStaging, manifest);
        } finally {
            Files.deleteIfExists(manifestStaging);
        }

        System.out.printf(Locale.ROOT,
                "fixture=%s manifest=%s rows=%d unique=%d duplicates=%d defanged=%d sha256=%s%n",
                output, manifest, options.size(), stats.uniqueValues(), stats.duplicates(),
                stats.defanged(), sha256);
    }

    private static Stats writeFixture(Path target, Options options) throws IOException {
        var random = new SplittableRandom(options.seed());
        var pool = new ArrayList<Indicator>();
        var uniqueValues = new HashSet<String>();
        var rowsByType = new EnumMap<IocType, Integer>(IocType.class);
        for (IocType type : IocType.values()) {
            rowsByType.put(type, 0);
        }
        int duplicates = 0;
        int defanged = 0;
        long nextUnique = 0;

        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            boolean html = options.format() == OutputFormat.HTML;
            if (html) {
                writer.write("<!doctype html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\"><title>IOC fixture</title></head><body>\n");
            }

            for (int row = 0; row < options.size(); row++) {
                if (row % SECTION_SIZE == 0) {
                    writeSection(writer, html, row / SECTION_SIZE + 1);
                }

                boolean duplicate = !pool.isEmpty() && random.nextDouble() < options.duplicateRate();
                Indicator indicator;
                if (duplicate) {
                    indicator = pool.get(random.nextInt(pool.size()));
                    duplicates++;
                } else {
                    indicator = uniqueIndicator(options.seed(), nextUnique++);
                    pool.add(indicator);
                    uniqueValues.add(indicator.type() + "\u0000" + indicator.value());
                }

                boolean shouldDefang = indicator.type().network()
                        && random.nextDouble() < options.defangRate();
                String rendered = shouldDefang ? defang(indicator) : indicator.value();
                if (shouldDefang) {
                    defanged++;
                }
                rowsByType.compute(indicator.type(), (ignored, count) -> count + 1);
                writeRow(writer, html, row + 1, rendered);
            }

            if (html) {
                writer.write("</body></html>\n");
            }
        }
        return new Stats(options.size(), uniqueValues.size(), duplicates, defanged, rowsByType);
    }

    private static Indicator uniqueIndicator(long seed, long ordinal) {
        IocType type = IocType.values()[(int) (ordinal % IocType.values().length)];
        long sequence = ordinal / IocType.values().length + 1;
        String seedToken = Long.toUnsignedString(seed, 16);
        return switch (type) {
            case IPV4 -> new Indicator(type, privateIpv4(sequence));
            case DOMAIN -> new Indicator(type, "ioc-" + sequence + "-s" + seedToken + ".example.test");
            case URL -> new Indicator(type, "https://ioc-" + sequence + "-s" + seedToken
                    + ".example.test/path/" + sequence + "?sample=" + sequence);
            case MD5 -> new Indicator(type, syntheticHash(seed, sequence, type, 32));
            case SHA1 -> new Indicator(type, syntheticHash(seed, sequence, type, 40));
            case SHA256 -> new Indicator(type, syntheticHash(seed, sequence, type, 64));
        };
    }

    private static String privateIpv4(long sequence) {
        long value = Math.floorMod(sequence - 1, 16_777_214L) + 1;
        long second = (value >>> 16) & 0xff;
        long third = (value >>> 8) & 0xff;
        long fourth = value & 0xff;
        return "10." + second + "." + third + "." + fourth;
    }

    private static String syntheticHash(long seed, long sequence, IocType type, int length) {
        byte[] input = (seed + ":" + sequence + ":" + type.name()).getBytes(StandardCharsets.UTF_8);
        return hex(digest("SHA-256", input)).substring(0, length).toUpperCase(Locale.ROOT);
    }

    private static String defang(Indicator indicator) {
        if (indicator.type() == IocType.URL) {
            return indicator.value().replace("https://", "hxxps[:]//").replace(".", "[.]");
        }
        return indicator.value().replace(".", "[.]");
    }

    private static void writeSection(BufferedWriter writer, boolean html, int section) throws IOException {
        String value = "БИБ-" + String.format(Locale.ROOT, "%04d", section);
        if (html) {
            writer.write("<h2>" + value + "</h2>\n");
        } else {
            writer.write(value + System.lineSeparator());
        }
    }

    private static void writeRow(BufferedWriter writer, boolean html, int row, String value) throws IOException {
        String line = "sample-" + row + " :: " + value;
        if (html) {
            writer.write("<p>" + line.replace("&", "&amp;") + "</p>\n");
        } else {
            writer.write(line + System.lineSeparator());
        }
    }

    private static void writeManifest(Path target, Path output, String sha256,
                                      Options options, Stats stats) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write("{\n");
            writer.write("  \"formatVersion\": 1,\n");
            writer.write("  \"fixture\": \"" + json(output.toString()) + "\",\n");
            writer.write("  \"format\": \"" + options.format().name().toLowerCase(Locale.ROOT) + "\",\n");
            writer.write("  \"seed\": " + options.seed() + ",\n");
            writer.write("  \"inputRows\": " + stats.inputRows() + ",\n");
            writer.write("  \"uniqueInputValues\": " + stats.uniqueValues() + ",\n");
            writer.write("  \"duplicateRows\": " + stats.duplicates() + ",\n");
            writer.write("  \"defangedRows\": " + stats.defanged() + ",\n");
            writer.write("  \"rowsByType\": {\n");
            IocType[] types = IocType.values();
            for (int index = 0; index < types.length; index++) {
                IocType type = types[index];
                writer.write("    \"" + type.name() + "\": " + stats.rowsByType().get(type));
                writer.write(index + 1 == types.length ? "\n" : ",\n");
            }
            writer.write("  },\n");
            writer.write("  \"sha256\": \"" + sha256 + "\"\n");
            writer.write("}\n");
        }
    }

    private static void requireWritableTarget(Path target, boolean force) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("Refusing symbolic-link output: " + target);
        }
        if (Files.exists(target) && !force) {
            throw new IllegalArgumentException("Output exists; pass --force to replace it: " + target);
        }
        if (Files.exists(target) && !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Output is not a regular file: " + target);
        }
    }

    private static void publish(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] digest(String algorithm, byte[] value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required digest is unavailable: " + algorithm, e);
        }
    }

    private static String hex(byte[] value) {
        var result = new StringBuilder(value.length * 2);
        for (byte current : value) {
            result.append(String.format(Locale.ROOT, "%02x", current & 0xff));
        }
        return result.toString();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void usage() {
        System.out.println("""
                Usage: java tools/dev/GenerateIocFixture.java OPTIONS

                  --size N               Number of IOC-bearing rows (required, positive)
                  --seed N               Deterministic random seed (default: 42)
                  --output PATH          Fixture output path (required)
                  --manifest PATH        Manifest path (default: <output>.manifest.json)
                  --format html|text     Output format (default: html)
                  --duplicate-rate 0..1  Approximate duplicate-row rate (default: 0.10)
                  --defang-rate 0..1     Approximate network defang rate (default: 0.35)
                  --force                Replace regular output files
                  --help                 Show this help
                """);
    }

    private enum IocType {
        IPV4(true), DOMAIN(true), URL(true), MD5(false), SHA1(false), SHA256(false);

        private final boolean network;

        IocType(boolean network) {
            this.network = network;
        }

        boolean network() {
            return network;
        }
    }

    private enum OutputFormat {
        HTML, TEXT;

        static OutputFormat parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "html" -> HTML;
                case "text", "txt" -> TEXT;
                default -> throw new IllegalArgumentException("Unsupported format: " + value);
            };
        }
    }

    private record Indicator(IocType type, String value) {
    }

    private record Stats(int inputRows, int uniqueValues, int duplicates, int defanged,
                         Map<IocType, Integer> rowsByType) {
    }

    private record Options(int size, long seed, Path output, Path manifest,
                           OutputFormat format, double duplicateRate, double defangRate,
                           boolean force, boolean help) {

        static Options parse(String[] args) {
            int size = -1;
            long seed = 42;
            Path output = null;
            Path manifest = null;
            OutputFormat format = OutputFormat.HTML;
            double duplicateRate = 0.10;
            double defangRate = 0.35;
            boolean force = false;
            boolean help = false;

            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
                    case "--size" -> size = Integer.parseInt(value(args, ++index, argument));
                    case "--seed" -> seed = Long.parseLong(value(args, ++index, argument));
                    case "--output" -> output = Path.of(value(args, ++index, argument));
                    case "--manifest" -> manifest = Path.of(value(args, ++index, argument));
                    case "--format" -> format = OutputFormat.parse(value(args, ++index, argument));
                    case "--duplicate-rate" -> duplicateRate = Double.parseDouble(value(args, ++index, argument));
                    case "--defang-rate" -> defangRate = Double.parseDouble(value(args, ++index, argument));
                    case "--force" -> force = true;
                    case "-h", "--help" -> help = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + argument);
                }
            }
            if (help) {
                return new Options(1, seed, Path.of("unused"), Path.of("unused"), format,
                        duplicateRate, defangRate, force, true);
            }
            if (size <= 0) {
                throw new IllegalArgumentException("--size must be a positive integer");
            }
            if (output == null) {
                throw new IllegalArgumentException("--output is required");
            }
            if (manifest == null) {
                manifest = Path.of(output + ".manifest.json");
            }
            rate("--duplicate-rate", duplicateRate);
            rate("--defang-rate", defangRate);
            if (output.toAbsolutePath().normalize().equals(manifest.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("fixture and manifest paths must differ");
            }
            return new Options(size, seed, output, manifest, format, duplicateRate, defangRate, force, false);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static void rate(String option, double value) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(option + " must be within 0..1");
            }
        }
    }
}
