import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Synthetic-reactor contract tests for {@link CoverageVerifier}. */
public final class CoverageVerifierTest {

    private CoverageVerifierTest() {
    }

    public static void main(String[] args) throws Exception {
        int happyPaths = 0;
        int negativeScenarios = 0;

        try (Fixture fixture = Fixture.create()) {
            assertSuccess(fixture, "validate");
            fixture.writeValidReports();
            assertSuccess(fixture, "verify-reports");
            happyPaths += 2;
        }

        List<Scenario> scenarios = List.of(
                new Scenario(
                        "new reactor module without disposition",
                        "validate",
                        fixture -> {
                            fixture.write("rogue/pom.xml", projectPom("rogue", "jar"));
                            fixture.replace(
                                    "pom.xml",
                                    "</modules>",
                                    "<module>rogue</module></modules>");
                        },
                        "coverage scope paths versus root Maven reactor differ"),
                new Scenario(
                        "manifest artifact mismatch",
                        "validate",
                        fixture -> fixture.replace(
                                "build-support/coverage-report/coverage-scope.tsv",
                                "app\tapp\tcovered",
                                "app\twrong-app\tcovered"),
                        "coverage manifest artifactId mismatch for app"),
                new Scenario(
                        "aggregate dependencies drift",
                        "validate",
                        fixture -> fixture.replace(
                                "build-support/coverage-report/pom.xml",
                                dependency("leaf"),
                                ""),
                        "covered scope versus coverage report-module dependencies differ"),
                new Scenario(
                        "ratchet scope drift",
                        "validate",
                        fixture -> fixture.replace(
                                "build-support/coverage-report/coverage-ratchets.tsv",
                                "leaf\t4\t0\t0\t0\t1\tenforced\tSmall module.\n",
                                ""),
                        "coverage ratchet scopes versus aggregate plus production modules differ"),
                new Scenario(
                        "JaCoCo exclusion",
                        "validate",
                        fixture -> fixture.replace(
                                "pom.xml",
                                "<append>true</append>",
                                "<append>true</append><excludes><exclude>sample/*</exclude></excludes>"),
                        "JaCoCo includes/excludes are not accepted"),
                new Scenario(
                        "JaCoCo skip override",
                        "validate",
                        fixture -> fixture.replace(
                                "pom.xml",
                                "<append>true</append>",
                                "<append>true</append><skip>true</skip>"),
                        "JaCoCo skip overrides are not accepted"),
                new Scenario(
                        "module report cleanup drift",
                        "validate",
                        fixture -> fixture.replace(
                                "pom.xml",
                                "${project.reporting.outputDirectory}/jacoco",
                                "${project.reporting.outputDirectory}/wrong"),
                        "must delete dir=${project.reporting.outputDirectory}/jacoco"),
                new Scenario(
                        "aggregate report cleanup drift",
                        "validate",
                        fixture -> fixture.replace(
                                "build-support/coverage-report/pom.xml",
                                "${project.reporting.outputDirectory}/jacoco-aggregate",
                                "${project.reporting.outputDirectory}/wrong"),
                        "must delete dir=${project.reporting.outputDirectory}/jacoco-aggregate"),
                new Scenario(
                        "late coverage gate removed",
                        "validate",
                        fixture -> fixture.replace(
                                "build-support/coverage-report/pom.xml",
                                fixture.coverageGate(),
                                ""),
                        "missing execution verify-coverage-policy"),
                new Scenario(
                        "missing aggregate report",
                        "verify-reports",
                        fixture -> { },
                        "aggregate JaCoCo XML is missing or empty"),
                new Scenario(
                        "aggregate report without JaCoCo doctype",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(
                                    Fixture.AGGREGATE_XML,
                                    Fixture.JACOCO_DOCTYPE,
                                    "");
                        },
                        "JaCoCo XML has no exact Report 1.1 document type"),
                new Scenario(
                        "malformed aggregate report",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(Fixture.AGGREGATE_XML, "</report>", "<broken>");
                        },
                        "cannot parse XML"),
                new Scenario(
                        "missing aggregate group",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(
                                    Fixture.AGGREGATE_XML,
                                    fixture.leafGroup(),
                                    "");
                        },
                        "aggregate JaCoCo groups versus covered production modules differ"),
                new Scenario(
                        "unexpected aggregate group",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(
                                    Fixture.AGGREGATE_XML,
                                    "</report>",
                                    fixture.ghostGroup() + "</report>");
                        },
                        "aggregate JaCoCo groups versus covered production modules differ"),
                new Scenario(
                        "aggregate counter sum drift",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replaceLast(
                                    Fixture.AGGREGATE_XML,
                                    counter("LINE", 2, 22),
                                    counter("LINE", 2, 21));
                        },
                        "aggregate JaCoCo LINE counter differs from group sum"),
                new Scenario(
                        "missing required local report",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            Files.delete(fixture.root().resolve("app/target/site/jacoco/jacoco.xml"));
                        },
                        "module JaCoCo XML for app is missing or empty"),
                new Scenario(
                        "aggregate-only module produced local report",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.write(
                                    "leaf/target/site/jacoco/jacoco.xml",
                                    fixture.localReport("leaf", fixture.leafCounters()));
                        },
                        "module JaCoCo XML is unexpected for leaf"),
                new Scenario(
                        "line ratio regression",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(
                                    "build-support/coverage-report/coverage-ratchets.tsv",
                                    "app\t18\t2\t3\t1",
                                    "app\t19\t1\t3\t1");
                        },
                        "coverage line ratio regression for app"),
                new Scenario(
                        "branch ratio regression",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(
                                    "build-support/coverage-report/coverage-ratchets.tsv",
                                    "app\t18\t2\t3\t1",
                                    "app\t18\t2\t4\t1");
                        },
                        "coverage branch ratio regression for app"),
                new Scenario(
                        "branch missed-count dilution",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replaceAll(
                                    Fixture.AGGREGATE_XML,
                                    counter("BRANCH", 1, 3),
                                    counter("BRANCH", 2, 6));
                        },
                        "coverage branch missed-count regression for reactor"),
                new Scenario(
                        "small-module instruction regression",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(
                                    Fixture.AGGREGATE_XML,
                                    fixture.leafGroup(),
                                    fixture.leafGroupWithInstructionRegression());
                            fixture.replaceLast(
                                    Fixture.AGGREGATE_XML,
                                    counter("INSTRUCTION", 11, 99),
                                    counter("INSTRUCTION", 12, 108));
                        },
                        "coverage instruction missed-count regression for leaf"),
                new Scenario(
                        "missing JaCoCo counter",
                        "verify-reports",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(
                                    Fixture.AGGREGATE_XML,
                                    counter("METHOD", 1, 9),
                                    "");
                        },
                        "JaCoCo group app counter types differ"));

        for (Scenario scenario : scenarios) {
            try (Fixture fixture = Fixture.create()) {
                scenario.mutation().apply(fixture);
                assertFailure(fixture, scenario.mode(), scenario.name(), scenario.messageFragment());
                negativeScenarios++;
            }
        }

        System.out.printf(
                "[coverage-verifier-test] passed %d happy paths and %d negative scenarios%n",
                happyPaths,
                negativeScenarios);
    }

    private static void assertSuccess(Fixture fixture, String mode) {
        Result result = run(fixture, mode);
        if (result.exitCode() != 0) {
            throw new AssertionError(
                    "expected success for " + mode + ", stderr=" + result.errorOutput());
        }
    }

    private static void assertFailure(
            Fixture fixture,
            String mode,
            String scenarioName,
            String messageFragment) {
        Result result = run(fixture, mode);
        if (result.exitCode() == 0) {
            throw new AssertionError(
                    "expected failure for " + scenarioName + " containing: " + messageFragment
                            + ", stdout=" + result.standardOutput());
        }
        if (!result.errorOutput().contains(messageFragment)) {
            throw new AssertionError(
                    "expected error for " + scenarioName + " containing '" + messageFragment
                            + "', stderr=" + result.errorOutput());
        }
    }

    private static Result run(Fixture fixture, String mode) {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        int exitCode = CoverageVerifier.execute(
                new String[]{
                        mode,
                        fixture.root().toString(),
                        fixture.root().resolve(
                                "build-support/coverage-report/coverage-scope.tsv").toString(),
                        fixture.root().resolve(
                                "build-support/coverage-report/coverage-ratchets.tsv").toString(),
                        fixture.root().resolve(
                                "build-support/coverage-report/pom.xml").toString()
                },
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        return new Result(
                exitCode,
                output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private static String projectPom(String artifactId, String packaging) {
        return "<project><modelVersion>4.0.0</modelVersion><artifactId>" + artifactId
                + "</artifactId><packaging>" + packaging + "</packaging></project>";
    }

    private static String dependency(String artifactId) {
        return "<dependency><groupId>com.iocextractor</groupId><artifactId>" + artifactId
                + "</artifactId></dependency>";
    }

    private static String counter(String type, long missed, long covered) {
        return "<counter type=\"" + type + "\" missed=\"" + missed
                + "\" covered=\"" + covered + "\"/>";
    }

    private record Scenario(
            String name,
            String mode,
            Mutation mutation,
            String messageFragment) {
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(Fixture fixture) throws Exception;
    }

    private record Result(int exitCode, String standardOutput, String errorOutput) {
    }

    private static final class Fixture implements AutoCloseable {

        static final String AGGREGATE_XML =
                "build-support/coverage-report/target/site/jacoco-aggregate/jacoco.xml";
        static final String JACOCO_DOCTYPE =
                "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">";

        private final Path root;

        private Fixture(Path root) {
            this.root = root;
        }

        static Fixture create() throws IOException {
            Fixture fixture = new Fixture(Files.createTempDirectory("coverage-verifier-"));
            fixture.writeFixture();
            return fixture;
        }

        Path root() {
            return root;
        }

        void writeFixture() throws IOException {
            write("pom.xml", """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <artifactId>root</artifactId><packaging>pom</packaging>
                      <modules><module>app</module><module>leaf</module><module>support</module>
                        <module>build-support/coverage-report</module></modules>
                      <build><plugins>
                        <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><executions>
                          <execution><id>prepare-coverage-agent</id><goals><goal>prepare-agent</goal></goals>
                            <configuration><append>true</append></configuration></execution>
                          <execution><id>create-module-coverage-report</id><phase>verify</phase>
                            <goals><goal>report</goal></goals><configuration><formats>
                              <format>HTML</format><format>XML</format>
                            </formats></configuration></execution>
                        </executions></plugin>
                        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-antrun-plugin</artifactId><executions>
                          <execution><id>clean-stale-test-output</id><phase>initialize</phase><configuration>
                            <target unless="skipTests">
                              <delete file="${project.build.directory}/jacoco.exec"/>
                              <delete dir="${project.reporting.outputDirectory}/jacoco"/>
                            </target>
                          </configuration></execution>
                        </executions></plugin>
                      </plugins></build>
                    </project>
                    """);
            write("app/pom.xml", projectPom("app", "jar"));
            write("leaf/pom.xml", projectPom("leaf", "jar"));
            write("support/pom.xml", projectPom("support", "jar"));
            write("build-support/coverage-report/pom.xml", """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <artifactId>coverage</artifactId><packaging>pom</packaging>
                      <dependencies>
                    """ + dependency("app") + dependency("leaf") + """
                      </dependencies><build><plugins>
                        <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><executions>
                          <execution><id>create-reactor-coverage-report</id><phase>verify</phase>
                            <goals><goal>report-aggregate</goal></goals><configuration>
                              <formats><format>HTML</format><format>XML</format></formats>
                              <title>ioc-extractor reactor coverage</title>
                            </configuration></execution>
                        </executions></plugin>
                        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-antrun-plugin</artifactId><executions>
                          <execution><id>clean-stale-aggregate-coverage-output</id><phase>initialize</phase><configuration>
                            <target unless="skipTests"><delete
                              dir="${project.reporting.outputDirectory}/jacoco-aggregate"/></target>
                          </configuration></execution>
                    """ + coverageGate() + """
                        </executions></plugin>
                      </plugins></build>
                    </project>
                    """);
            write("build-support/coverage-report/coverage-scope.tsv", """
                    .\troot\texcluded\tnone\tRoot parent.
                    app\tapp\tcovered\trequired\tProduction module with local tests.
                    leaf\tleaf\tcovered\taggregate-only\tDownstream-only production module.
                    support\tsupport\texcluded\tnone\tTest support.
                    build-support/coverage-report\tcoverage\taggregate\tnone\tAggregate owner.
                    """);
            write("build-support/coverage-report/coverage-ratchets.tsv", """
                    reactor\t22\t2\t3\t1\t11\tobserved\tAggregate baseline.
                    app\t18\t2\t3\t1\t10\tobserved\tApplication baseline.
                    leaf\t4\t0\t0\t0\t1\tenforced\tSmall module.
                    """);
        }

        void writeValidReports() throws IOException {
            write("app/target/jacoco.exec", "execution-data");
            write(
                    "app/target/site/jacoco/jacoco.xml",
                    localReport("app", appCounters()));
            write("app/target/site/jacoco/index.html", "<html>app</html>");
            write(AGGREGATE_XML, aggregateReport());
            write(
                    "build-support/coverage-report/target/site/jacoco-aggregate/index.html",
                    "<html>aggregate</html>");
        }

        String localReport(String name, String counters) {
            return "<?xml version=\"1.0\"?>" + JACOCO_DOCTYPE
                    + "<report name=\"" + name + "\"><package name=\"sample\"/>"
                    + counters + "</report>";
        }

        String aggregateReport() {
            return "<?xml version=\"1.0\"?>" + JACOCO_DOCTYPE
                    + "<report name=\"ioc-extractor reactor coverage\">"
                    + appGroup() + leafGroup() + aggregateCounters() + "</report>";
        }

        String coverageGate() {
            return """
                          <execution><id>verify-coverage-policy</id><phase>verify</phase><configuration>
                            <target><java classname="CoverageVerifier" fork="true" failonerror="true">
                              <arg value="verify-reports"/>
                              <arg value="${maven.multiModuleProjectDirectory}"/>
                              <arg value="${maven.multiModuleProjectDirectory}/build-support/coverage-report/coverage-scope.tsv"/>
                              <arg value="${maven.multiModuleProjectDirectory}/build-support/coverage-report/coverage-ratchets.tsv"/>
                              <arg value="${maven.multiModuleProjectDirectory}/build-support/coverage-report/pom.xml"/>
                            </java></target>
                          </configuration></execution>
                    """;
        }

        String appGroup() {
            return "<group name=\"app\"><package name=\"sample\"/>"
                    + appCounters() + "</group>";
        }

        String leafGroup() {
            return "<group name=\"leaf\"><package name=\"sample\"/>"
                    + leafCounters() + "</group>";
        }

        String leafGroupWithInstructionRegression() {
            return "<group name=\"leaf\"><package name=\"sample\"/>"
                    + counter("INSTRUCTION", 2, 18)
                    + counter("BRANCH", 0, 0)
                    + counter("LINE", 0, 4)
                    + counter("COMPLEXITY", 0, 1)
                    + counter("METHOD", 0, 1)
                    + counter("CLASS", 0, 1)
                    + "</group>";
        }

        String ghostGroup() {
            return "<group name=\"ghost\"><package name=\"sample\"/>"
                    + leafCounters() + "</group>";
        }

        String appCounters() {
            return counter("INSTRUCTION", 10, 90)
                    + counter("BRANCH", 1, 3)
                    + counter("LINE", 2, 18)
                    + counter("COMPLEXITY", 1, 9)
                    + counter("METHOD", 1, 9)
                    + counter("CLASS", 0, 2);
        }

        String leafCounters() {
            return counter("INSTRUCTION", 1, 9)
                    + counter("BRANCH", 0, 0)
                    + counter("LINE", 0, 4)
                    + counter("COMPLEXITY", 0, 1)
                    + counter("METHOD", 0, 1)
                    + counter("CLASS", 0, 1);
        }

        String aggregateCounters() {
            return counter("INSTRUCTION", 11, 99)
                    + counter("BRANCH", 1, 3)
                    + counter("LINE", 2, 22)
                    + counter("COMPLEXITY", 1, 10)
                    + counter("METHOD", 1, 10)
                    + counter("CLASS", 0, 3);
        }

        void replace(String relativePath, String existing, String replacement) throws IOException {
            Path file = root.resolve(relativePath);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int first = content.indexOf(existing);
            if (first < 0) {
                throw new AssertionError(
                        "fixture replacement anchor is missing in " + relativePath + ": " + existing);
            }
            Files.writeString(
                    file,
                    content.substring(0, first) + replacement
                            + content.substring(first + existing.length()),
                    StandardCharsets.UTF_8);
        }

        void replaceLast(String relativePath, String existing, String replacement)
                throws IOException {
            Path file = root.resolve(relativePath);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int last = content.lastIndexOf(existing);
            if (last < 0) {
                throw new AssertionError(
                        "fixture replacement anchor is missing in " + relativePath + ": " + existing);
            }
            Files.writeString(
                    file,
                    content.substring(0, last) + replacement
                            + content.substring(last + existing.length()),
                    StandardCharsets.UTF_8);
        }

        void replaceAll(String relativePath, String existing, String replacement)
                throws IOException {
            Path file = root.resolve(relativePath);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (!content.contains(existing)) {
                throw new AssertionError(
                        "fixture replacement anchor is missing in " + relativePath + ": " + existing);
            }
            Files.writeString(file, content.replace(existing, replacement), StandardCharsets.UTF_8);
        }

        void write(String relativePath, String content) throws IOException {
            Path file = root.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            if (!Files.exists(root)) {
                return;
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
