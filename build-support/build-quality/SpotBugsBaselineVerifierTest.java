import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Black-box contract matrix for {@link SpotBugsBaselineVerifier}. */
public final class SpotBugsBaselineVerifierTest {

    private SpotBugsBaselineVerifierTest() {
    }

    /** Runs the dependency-free validate/verify fixture matrix. */
    public static void main(String[] args) throws Exception {
        List<Scenario> scenarios = List.of(
                new Scenario(
                        "new finding",
                        fixture -> fixture.addRawFinding(
                                finding("feedface", 0, 30, 2, 14)),
                        "new="),
                new Scenario(
                        "third occurrence with an already accepted hash",
                        fixture -> fixture.addRawFinding(
                                finding(Fixture.HASH, 2, 30, 2, 14)),
                        Fixture.HASH + "/2"),
                new Scenario(
                        "stale acceptance",
                        fixture -> fixture.removeSecondRawFinding(),
                        "stale="),
                new Scenario(
                        "bytecode anchor moved",
                        fixture -> fixture.replaceRaw("startBytecode='20'", "startBytecode='21'"),
                        "accepted baseline differs from raw findings"),
                new Scenario(
                        "priority drift",
                        fixture -> fixture.replaceRaw("priority='2' rank='14'", "priority='1' rank='14'"),
                        "accepted baseline differs from raw findings"),
                new Scenario(
                        "engine version drift",
                        fixture -> fixture.replaceAppRaw("version='4.10.3'", "version='4.11.0'"),
                        "engine version drift"),
                new Scenario(
                        "analyzer error",
                        fixture -> fixture.replaceAppRaw("errors='0'", "errors='1'"),
                        "analyzer errors=1"),
                new Scenario(
                        "missing analyzer class",
                        fixture -> fixture.replaceAppRaw("missingClasses='0'", "missingClasses='1'"),
                        "missingClasses=1"),
                new Scenario(
                        "occurrence max drift",
                        fixture -> fixture.replaceAppRaw(
                                "instanceOccurrenceMax='1'", "instanceOccurrenceMax='2'"),
                        "instanceOccurrenceMax mismatch"),
                new Scenario(
                        "visible filtered finding",
                        Fixture::writeVisibleFilteredReport,
                        "filtered report still contains 1 findings"),
                new Scenario(
                        "raw aggregate omission",
                        Fixture::removeSecondAggregateFinding,
                        "raw aggregate differs from module raw union"),
                new Scenario(
                        "suppression no longer matches",
                        fixture -> fixture.replaceBaseline(
                                "<Method name=\"run\" />", "<Method name=\"other\" />"),
                        "does not match its exact raw finding"));

        List<ValidationScenario> validationScenarios = List.of(
                new ValidationScenario(
                        "duplicate accepted id",
                        fixture -> fixture.replaceBaseline("id=\"SB04-002\"", "id=\"SB04-001\""),
                        "duplicate accepted finding id"),
                new ValidationScenario(
                        "duplicate accepted identity",
                        Fixture::appendDuplicateBaselineIdentity,
                        "duplicate accepted finding identity"),
                new ValidationScenario(
                        "missing rationale",
                        fixture -> fixture.replaceBaseline(
                                "Reviewed false positive.</rationale>", "</rationale>"),
                        "missing rationale"),
                new ValidationScenario(
                        "broad suppression",
                        fixture -> fixture.replaceBaseline("<Method name=\"run\" />", ""),
                        "must contain an exact Method and/or Field"),
                new ValidationScenario(
                        "invalid instance hash",
                        fixture -> fixture.replaceBaseline(Fixture.HASH, "NOT_A_HASH"),
                        "invalid instance hash"));

        runHappyPath();
        runAnchorlessFieldHappyPath();
        runDualPrimaryHappyPath();
        List<String> failures = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            try {
                runVerifyFailure(scenario);
            } catch (AssertionError | Exception e) {
                failures.add(scenario.name() + ": " + e.getMessage());
            }
        }
        for (ValidationScenario scenario : validationScenarios) {
            try {
                runValidationFailure(scenario);
            } catch (AssertionError | Exception e) {
                failures.add(scenario.name() + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(
                    "SpotBugsBaselineVerifier fixture failures:\n - "
                            + String.join("\n - ", failures));
        }
        System.out.printf(
                "SpotBugsBaselineVerifier: 3 happy paths and %d negative scenarios passed%n",
                scenarios.size() + validationScenarios.size());
    }

    private static void runHappyPath() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            Result validation = fixture.executeValidate();
            require(validation.exitCode() == 0, "validate should pass: " + validation.standardError());
            String generated = Files.readString(fixture.generatedFilter(), StandardCharsets.UTF_8);
            require(count(generated, "<Match>") == 1, "shared selector must be generated once");
            require(generated.contains("SB04-001, SB04-002"), "generated filter must retain evidence ids");

            fixture.writeValidReports();
            Result verification = fixture.executeVerify();
            require(verification.exitCode() == 0, "verify should pass: " + verification.standardError());
        }
    }

    private static void runAnchorlessFieldHappyPath() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            fixture.writeAnchorlessFieldBaseline();
            Result validation = fixture.executeValidate();
            require(
                    validation.exitCode() == 0,
                    "anchorless field validate should pass: " + validation.standardError());
        }
    }

    private static void runDualPrimaryHappyPath() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            require(fixture.executeValidate().exitCode() == 0, "fixture baseline must validate");
            fixture.writeValidReports();
            fixture.addPrimaryFieldAnnotations();
            Result verification = fixture.executeVerify();
            require(
                    verification.exitCode() == 0,
                    "dual-primary finding should pass: " + verification.standardError());
        }
    }

    private static void runVerifyFailure(Scenario scenario) throws Exception {
        try (Fixture fixture = Fixture.create()) {
            require(fixture.executeValidate().exitCode() == 0, "fixture baseline must validate");
            fixture.writeValidReports();
            scenario.mutation().apply(fixture);
            Result result = fixture.executeVerify();
            require(result.exitCode() != 0, "scenario unexpectedly passed");
            require(
                    result.standardError().contains(scenario.expectedError()),
                    "expected <" + scenario.expectedError() + ">, got <"
                            + result.standardError() + ">");
        }
    }

    private static void runValidationFailure(ValidationScenario scenario) throws Exception {
        try (Fixture fixture = Fixture.create()) {
            scenario.mutation().apply(fixture);
            Result result = fixture.executeValidate();
            require(result.exitCode() != 0, "scenario unexpectedly passed");
            require(
                    result.standardError().contains(scenario.expectedError()),
                    "expected <" + scenario.expectedError() + ">, got <"
                            + result.standardError() + ">");
        }
    }

    private static int count(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String finding(String hash, int occurrence, int bytecode, int priority, int rank) {
        return """
                <BugInstance type='NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE'
                             instanceHash='%s' instanceOccurrenceNum='%d'
                             instanceOccurrenceMax='%d'
                             priority='%d' rank='%d' category='STYLE'>
                  <Class classname='example.Sample' primary='true'/>
                  <Method classname='example.Sample' name='run' signature='()V' primary='true'/>
                  <SourceLine sourcepath='example/Sample.java' start='%d'
                              startBytecode='%d' primary='true'/>
                </BugInstance>
                """.formatted(
                hash,
                occurrence,
                hash.equals(Fixture.HASH) ? Math.max(1, occurrence) : occurrence,
                priority,
                rank,
                bytecode + 1,
                bytecode);
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(Fixture fixture) throws Exception;
    }

    private record Scenario(String name, Mutation mutation, String expectedError) {
    }

    private record ValidationScenario(String name, Mutation mutation, String expectedError) {
    }

    private record Result(int exitCode, String standardOutput, String standardError) {
    }

    private static final class Fixture implements AutoCloseable {

        private static final String HASH = "abc123";

        private final Path root;
        private final Path scope;
        private final Path baseline;
        private final Path generatedFilter;
        private final Path reportModule;

        private Fixture(Path root) {
            this.root = root;
            this.scope = root.resolve("scope.tsv");
            this.baseline = root.resolve("accepted.xml");
            this.generatedFilter = root.resolve("target/build-quality/spotbugs-filter.xml");
            this.reportModule = root.resolve("report");
        }

        static Fixture create() throws IOException {
            Fixture fixture = new Fixture(Files.createTempDirectory("spotbugs-baseline-verifier-"));
            fixture.write("app/.keep", "");
            fixture.write("report/.keep", "");
            fixture.write(
                    "scope.tsv",
                    """
                            # module-path\tartifact-id\tdisposition\trationale
                            .\troot\texcluded\tRoot parent
                            app\tapp\tanalyzed\tProduction code
                            report\treport\taggregate\tAggregate report
                            """);
            fixture.write("accepted.xml", baselineDocument());
            return fixture;
        }

        Path generatedFilter() {
            return generatedFilter;
        }

        void writeValidReports() throws IOException {
            String findings = finding(HASH, 0, 10, 2, 14)
                    + finding(HASH, 1, 20, 2, 14);
            write("app/target/spotbugs/spotbugs-raw.xml", report(findings, 2, true));
            write("app/target/spotbugs/spotbugs.xml", report("", 0, true));
            write("report/target/spotbugs/spotbugs-raw.xml", report(findings, 2, false));
            write("report/target/spotbugs/spotbugs.xml", report("", 0, false));
            write("report/target/spotbugs-raw/spotbugs.html", "<html>raw aggregate</html>");
        }

        void addRawFinding(String additional) throws IOException {
            if (additional.contains("instanceHash='" + HASH + "'")) {
                replaceRaw("instanceOccurrenceMax='1'", "instanceOccurrenceMax='2'");
            }
            replaceAppRaw("<Errors", additional + "<Errors");
            replaceAggregateRaw("<Errors", additional + "<Errors");
            incrementSummary(root.resolve("app/target/spotbugs/spotbugs-raw.xml"));
            incrementSummary(root.resolve("report/target/spotbugs/spotbugs-raw.xml"));
        }

        void removeSecondRawFinding() throws IOException {
            String second = finding(HASH, 1, 20, 2, 14);
            replaceAppRaw(second, "");
            replaceAggregateRaw(second, "");
            replaceAppRaw("instanceOccurrenceMax='1'", "instanceOccurrenceMax='0'");
            replaceAggregateRaw("instanceOccurrenceMax='1'", "instanceOccurrenceMax='0'");
            replaceAppRaw("total_bugs='2'", "total_bugs='1'");
            replaceAggregateRaw("total_bugs='2'", "total_bugs='1'");
        }

        void removeSecondAggregateFinding() throws IOException {
            replaceAggregateRaw(finding(HASH, 1, 20, 2, 14), "");
            replaceAggregateRaw("instanceOccurrenceMax='1'", "instanceOccurrenceMax='0'");
            replaceAggregateRaw("total_bugs='2'", "total_bugs='1'");
        }

        void writeVisibleFilteredReport() throws IOException {
            String visibleFinding = finding(HASH, 0, 10, 2, 14)
                    .replace("instanceOccurrenceMax='1'", "instanceOccurrenceMax='0'");
            write(
                    "app/target/spotbugs/spotbugs.xml",
                    report(visibleFinding, 1, true));
        }

        void addPrimaryFieldAnnotations() throws IOException {
            String field = "<Field classname='example.Sample' name='state' signature='I' primary='true'/>";
            replaceRaw("<SourceLine", field + "<SourceLine");
        }

        void appendDuplicateBaselineIdentity() throws IOException {
            String duplicate = acceptedFinding("SB04-003", 0, 10);
            replaceBaseline("</spotbugs-accepted-findings>", duplicate + "</spotbugs-accepted-findings>");
        }

        void writeAnchorlessFieldBaseline() throws IOException {
            write(
                    "accepted.xml",
                    """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <spotbugs-accepted-findings schemaVersion="1" engineVersion="4.10.3">
                              <finding id="SB04-115" module="app" type="SE_BAD_FIELD" hash="f"
                                       occurrence="0" priority="3" rank="18" category="BAD_PRACTICE"
                                       disposition="false-positive" owner="R030-BUILD" evidence="C2-MIX-F"
                                       review="code-or-analyzer-change">
                                <class name="example.Sample" />
                                <field name="value" signature="Ljava/lang/Object;" />
                                <anchor />
                                <suppression>
                                  <Bug pattern="SE_BAD_FIELD" />
                                  <Class name="example.Sample" />
                                  <Field name="value" />
                                </suppression>
                                <rationale>Reviewed false positive.</rationale>
                              </finding>
                            </spotbugs-accepted-findings>
                            """);
        }

        Result executeValidate() {
            return execute(new String[] {
                "validate",
                root.toString(),
                scope.toString(),
                baseline.toString(),
                generatedFilter.toString()
            });
        }

        Result executeVerify() {
            return execute(new String[] {
                "verify",
                root.toString(),
                scope.toString(),
                baseline.toString(),
                reportModule.toString()
            });
        }

        void replaceRaw(String expected, String replacement) throws IOException {
            replaceAppRaw(expected, replacement);
            replaceAggregateRaw(expected, replacement);
        }

        void replaceAppRaw(String expected, String replacement) throws IOException {
            replace(root.resolve("app/target/spotbugs/spotbugs-raw.xml"), expected, replacement);
        }

        void replaceAggregateRaw(String expected, String replacement) throws IOException {
            replace(root.resolve("report/target/spotbugs/spotbugs-raw.xml"), expected, replacement);
        }

        void replaceBaseline(String expected, String replacement) throws IOException {
            replace(baseline, expected, replacement);
        }

        private Result execute(String[] arguments) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            int exitCode;
            try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
                    PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8)) {
                exitCode = SpotBugsBaselineVerifier.execute(arguments, out, err);
            }
            return new Result(
                    exitCode,
                    output.toString(StandardCharsets.UTF_8),
                    error.toString(StandardCharsets.UTF_8));
        }

        private void incrementSummary(Path file) throws IOException {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int current = Integer.parseInt(content.replaceAll("(?s).*total_bugs='([0-9]+)'.*", "$1"));
            replace(file, "total_bugs='" + current + "'", "total_bugs='" + (current + 1) + "'");
        }

        private void write(String relative, String content) throws IOException {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }

        private void replace(Path file, String expected, String replacement) throws IOException {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            require(content.contains(expected), "fixture mutation target is missing: " + expected);
            Files.writeString(
                    file, content.replace(expected, replacement), StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }

        private static String baselineDocument() {
            return """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <spotbugs-accepted-findings schemaVersion="1" engineVersion="4.10.3">
                    %s
                    %s
                    </spotbugs-accepted-findings>
                    """.formatted(
                    acceptedFinding("SB04-001", 0, 10),
                    acceptedFinding("SB04-002", 1, 20));
        }

        private static String acceptedFinding(String id, int occurrence, int bytecode) {
            return """
                      <finding id="%s" module="app"
                               type="NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE" hash="%s"
                               occurrence="%d" priority="2" rank="14" category="STYLE"
                               disposition="false-positive" owner="R030-BUILD" evidence="C1-NP-A"
                               review="code-or-analyzer-change">
                        <class name="example.Sample" />
                        <method name="run" signature="()V" />
                        <anchor sourcePath="example/Sample.java" startLine="%d" bytecode="%d" />
                        <suppression>
                          <Bug pattern="NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE" />
                          <Class name="example.Sample" />
                          <Method name="run" />
                        </suppression>
                        <rationale>Reviewed false positive.</rationale>
                      </finding>
                    """.formatted(id, HASH, occurrence, bytecode + 1, bytecode);
        }

        private static String report(String findings, int total, boolean includeVersion) {
            return """
                    <?xml version='1.0' encoding='UTF-8'?>
                    <BugCollection%s>
                    %s
                      <Errors errors='0' missingClasses='0'/>
                      <FindBugsSummary total_bugs='%d' total_classes='1'/>
                    </BugCollection>
                    """.formatted(includeVersion ? " version='4.10.3'" : "", findings, total);
        }
    }
}
