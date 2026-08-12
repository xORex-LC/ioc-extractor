import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Black-box fixture tests for {@link BuildQualityVerifier}.
 *
 * <p>The harness uses synthetic Maven reactors so negative scenarios never
 * mutate the real checkout.</p>
 */
public final class BuildQualityVerifierTest {

    private static final String POM_HEADER = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
            """;

    private BuildQualityVerifierTest() {
    }

    public static void main(String[] args) throws Exception {
        List<Scenario> scenarios = List.of(
                new Scenario(
                        "new reactor module without disposition",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> {
                            fixture.addModule("rogue", "rogue", "jar", false);
                            fixture.replaceRoot(
                                    "</modules>",
                                    "    <module>rogue</module>\n  </modules>");
                        },
                        "scope manifest paths versus root Maven reactor differ; "
                                + "missing=[rogue], unexpected=[]"),
                new Scenario(
                        "stale manifest module",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.appendManifest(
                                "ghost\tghost\texcluded\tStale module\n"),
                        "scope manifest paths versus root Maven reactor differ; "
                                + "missing=[], unexpected=[ghost]"),
                new Scenario(
                        "manifest artifact mismatch",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceManifest(
                                "app\tapp\tanalyzed",
                                "app\twrong-app\tanalyzed"),
                        "manifest artifactId mismatch for app"),
                new Scenario(
                        "duplicate manifest path",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.appendManifest(
                                "app\tapp\tanalyzed\tDuplicate\n"),
                        "duplicate module path"),
                new Scenario(
                        "analyzed non-JAR module",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.replace(
                                fixture.root().resolve("app/pom.xml"),
                                "<packaging>jar</packaging>",
                                "<packaging>pom</packaging>"),
                        "analyzed module must use jar packaging"),
                new Scenario(
                        "analyzed module skips SpotBugs",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.replace(
                                fixture.root().resolve("app/pom.xml"),
                                "</project>",
                                spotBugsSkipPlugin() + "</project>"),
                        "analyzed module explicitly skips SpotBugs: app"),
                new Scenario(
                        "excluded module lacks explicit SpotBugs skip",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.replace(
                                fixture.root().resolve("support/pom.xml"),
                                spotBugsSkipPlugin(),
                                ""),
                        "excluded module must explicitly configure SpotBugs skip=true: support"),
                new Scenario(
                        "aggregate dependencies drift from analyzed scope",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                dependency("app"),
                                ""),
                        "analyzed scope versus SpotBugs report-module dependencies differ; "
                                + "missing=[app], unexpected=[]"),
                new Scenario(
                        "report module is not aggregate",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceManifest(
                                "report\treport\taggregate",
                                "report\treport\texcluded"),
                        "SpotBugs report module must use aggregate disposition: report"),
                new Scenario(
                        "raw aggregate XML crosses into filtered output",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "spotbugs-raw/spotbugs-raw.xml",
                                "spotbugs/spotbugs-raw.xml"),
                        "SpotBugs raw aggregate XML filename must be "
                                + "spotbugs-raw/spotbugs-raw.xml"),
                new Scenario(
                        "filtered aggregate XML crosses into raw output",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "spotbugs/spotbugs.xml",
                                "spotbugs-raw/spotbugs.xml"),
                        "SpotBugs filtered aggregate XML filename must be "
                                + "spotbugs/spotbugs.xml"),
                new Scenario(
                        "module raw XML directory drifts from aggregate discovery",
                        Control.SPOTBUGS,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRoot(
                                "<spotbugsXmlOutputDirectory>"
                                        + "${project.build.directory}/spotbugs-raw"
                                        + "</spotbugsXmlOutputDirectory>",
                                "<spotbugsXmlOutputDirectory>"
                                        + "${project.build.directory}/spotbugs"
                                        + "</spotbugsXmlOutputDirectory>"),
                        "SpotBugs module XML outputDirectory must be "
                                + "${project.build.directory}/spotbugs-raw"),
                new Scenario(
                        "missing SpotBugs module report",
                        Control.SPOTBUGS,
                        Mode.VERIFY_REPORTS,
                        fixture -> {
                            fixture.writeValidReports();
                            Files.delete(fixture.root().resolve("app/target/spotbugs/spotbugs.xml"));
                        },
                        "SpotBugs report integrity failed"),
                new Scenario(
                        "excluded module produced a SpotBugs report",
                        Control.SPOTBUGS,
                        Mode.VERIFY_REPORTS,
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.write(
                                    "support/target/spotbugs/spotbugs.xml",
                                    "<BugCollection/>");
                        },
                        "excluded module produced a SpotBugs report"),
                new Scenario(
                        "CPD production source root is missing",
                        Control.CPD,
                        Mode.VALIDATE,
                        fixture -> fixture.deleteTree(
                                fixture.root().resolve("app/src/main/java")),
                        "production source root for app does not exist"),
                new Scenario(
                        "CPD configured source roots drift",
                        Control.CPD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                sourceRoot("app"),
                                sourceRoot("support")),
                        "analyzed scope versus configured CPD source roots differ"),
                new Scenario(
                        "CPD test-bytecode scope is enabled",
                        Control.CPD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "<includeTests>false</includeTests>",
                                "<includeTests>true</includeTests>"),
                        "CPD includeTests must be false"),
                new Scenario(
                        "CPD XML has an unexpected root",
                        Control.CPD,
                        Mode.VERIFY_REPORTS,
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.write(
                                    "report/target/cpd/cpd.xml",
                                    "<unexpected/>");
                        },
                        "unexpected CPD XML root"),
                new Scenario(
                        "PMD configured source roots drift",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                sourceRoot("app"),
                                sourceRoot("support")),
                        "analyzed scope versus configured PMD source roots differ"),
                new Scenario(
                        "PMD test source analysis is enabled",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "<includeTests>false</includeTests>",
                                "<includeTests>true</includeTests>"),
                        "PMD includeTests must be false"),
                new Scenario(
                        "PMD analysis cache is enabled",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "<analysisCache>false</analysisCache>",
                                "<analysisCache>true</analysisCache>"),
                        "PMD analysisCache must be false"),
                new Scenario(
                        "PMD analyzer errors are ignored",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "<skipPmdError>false</skipPmdError>",
                                "<skipPmdError>true</skipPmdError>"),
                        "PMD skipPmdError must be false"),
                new Scenario(
                        "PMD aggregate goal forks the lifecycle",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "<goal>aggregate-pmd-no-fork</goal>",
                                "<goal>aggregate-pmd</goal>"),
                        "PMD execution goals differ"),
                new Scenario(
                        "PMD engine version drifts",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "<version>${pmd.version}</version>",
                                "<version>7.25.0</version>"),
                        "PMD engine plugin dependencies differ"),
                new Scenario(
                        "PMD engine version property drifts",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRoot(
                                "<pmd.version>7.26.0</pmd.version>",
                                "<pmd.version>7.25.0</pmd.version>"),
                        "PMD engine version property must be 7.26.0"),
                new Scenario(
                        "PMD source encoding drifts",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRoot(
                                "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
                                "<project.build.sourceEncoding>UTF-16</project.build.sourceEncoding>"),
                        "reactor source encoding must be UTF-8"),
                new Scenario(
                        "PMD stale-output cleanup wiring is removed",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "<id>clean-stale-pmd-output</id>",
                                "<id>disabled-clean-stale-pmd-output</id>"),
                        "PMD lifecycle execution IDs differ"),
                new Scenario(
                        "PMD report-integrity wiring is removed",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "<id>verify-pmd-report-integrity</id>",
                                "<id>disabled-verify-pmd-report-integrity</id>"),
                        "PMD lifecycle execution IDs differ"),
                new Scenario(
                        "PMD report verifier fails open",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "<exec executable=\"${java.home}/bin/java\" failonerror=\"true\">",
                                "<exec executable=\"${java.home}/bin/java\" failonerror=\"false\">"),
                        "PMD report verifier failonerror must be true"),
                new Scenario(
                        "PMD ruleset uses a category-wide reference",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRuleset(
                                "category/java/errorprone.xml/UselessPureMethodCall",
                                "category/java/errorprone.xml"),
                        "PMD policy rule must reference one exact rule"),
                new Scenario(
                        "PMD rule introduces a suppression property",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRuleset(
                                "<rule ref=\"category/java/bestpractices.xml/UnusedAssignment\"/>",
                                "<rule ref=\"category/java/bestpractices.xml/UnusedAssignment\">"
                                        + "<properties><property name=\"violationSuppressRegex\" "
                                        + "value=\".*\"/></properties></rule>"),
                        "PMD policy rule properties differ"),
                new Scenario(
                        "PMD ruleset loses an accepted candidate",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRuleset(
                                "  <rule ref=\"category/java/bestpractices.xml/UnusedAssignment\"/>\n",
                                ""),
                        "PMD policy rules differ"),
                new Scenario(
                        "PMD ruleset duplicates a candidate",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRuleset(
                                "  <rule ref=\"category/java/bestpractices.xml/UnusedAssignment\"/>\n",
                                "  <rule ref=\"category/java/bestpractices.xml/UnusedAssignment\"/>\n"
                                        + "  <rule ref=\"category/java/bestpractices.xml/UnusedAssignment\"/>\n"),
                        "duplicate PMD policy rule reference"),
                new Scenario(
                        "PMD ruleset introduces an exclusion",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRuleset(
                                "</ruleset>",
                                "  <exclude-pattern>.*/Legacy.java</exclude-pattern>\n</ruleset>"),
                        "PMD policy ruleset must not contain source filters or exclusions"),
                new Scenario(
                        "PMD calibrated cognitive threshold drifts",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRuleset(
                                "<property name=\"reportLevel\" value=\"16\"/>",
                                "<property name=\"reportLevel\" value=\"15\"/>"),
                        "PMD policy rule properties differ for "
                                + "category/java/design.xml/CognitiveComplexity"),
                new Scenario(
                        "PMD calibrated parameter threshold drifts",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRuleset(
                                "<property name=\"minimum\" value=\"13\"/>",
                                "<property name=\"minimum\" value=\"12\"/>"),
                        "PMD policy rule properties differ for "
                                + "category/java/design.xml/ExcessiveParameterList"),
                new Scenario(
                        "PMD policy rule adds an attribute override",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceRuleset(
                                "<rule ref=\"category/java/bestpractices.xml/UnusedAssignment\"/>",
                                "<rule ref=\"category/java/bestpractices.xml/UnusedAssignment\" "
                                        + "message=\"overridden\"/>"),
                        "PMD policy rule must contain only the exact ref attribute"),
                new Scenario(
                        "PMD watchlist ruleset loses a reviewed rule",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceWatchlistRuleset(
                                "  <rule ref=\"category/java/errorprone.xml/CloseResource\"/>\n",
                                ""),
                        "PMD watchlist rules differ"),
                new Scenario(
                        "PMD watchlist report selection drifts",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "${project.build.directory}/pmd-watchlist",
                                "${project.build.directory}/pmd-deferred"),
                        "PMD watchlist report directory must be "
                                + "${project.build.directory}/pmd-watchlist"),
                new Scenario(
                        "PMD watchlist profile gains activation behavior",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.replaceReport(
                                "<id>pmd-watchlist</id>",
                                "<id>pmd-watchlist</id><activation/>"),
                        "PMD watchlist profile elements differ"),
                new Scenario(
                        "PMD source introduces a suppression marker",
                        Control.PMD,
                        Mode.VALIDATE,
                        fixture -> fixture.write(
                                "app/src/main/java/App.java",
                                "final class App {} // NOPMD\n"),
                        "PMD source suppression marker is forbidden"),
                new Scenario(
                        "PMD XML is missing",
                        Control.PMD,
                        Mode.VERIFY_REPORTS,
                        fixture -> {
                            fixture.writeValidReports();
                            Files.delete(fixture.root().resolve("report/target/pmd/pmd.xml"));
                        },
                        "PMD XML is missing or empty"),
                new Scenario(
                        "PMD HTML is missing",
                        Control.PMD,
                        Mode.VERIFY_REPORTS,
                        fixture -> {
                            fixture.writeValidReports();
                            Files.delete(fixture.root().resolve("report/target/pmd/pmd.html"));
                        },
                        "PMD HTML is missing or empty"),
                new Scenario(
                        "PMD XML contains an analyzer error",
                        Control.PMD,
                        Mode.VERIFY_REPORTS,
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(
                                    fixture.root().resolve("report/target/pmd/pmd.xml"),
                                    "</pmd>",
                                    "<error filename=\"App.java\" msg=\"failure\"/>\n</pmd>");
                        },
                        "PMD XML contains analyzer errors"),
                new Scenario(
                        "PMD XML references an excluded source",
                        Control.PMD,
                        Mode.VERIFY_REPORTS,
                        fixture -> {
                            fixture.writeValidReports();
                            Path report = fixture.root().resolve("report/target/pmd/pmd.xml");
                            fixture.replace(
                                    report,
                                    fixture.root().resolve("app/src/main/java/App.java")
                                            .toAbsolutePath().normalize().toString(),
                                    fixture.root().resolve("support/src/main/java/Support.java")
                                            .toAbsolutePath().normalize().toString());
                        },
                        "PMD XML references a source outside the analyzed inventory"),
                new Scenario(
                        "PMD XML engine version drifts",
                        Control.PMD,
                        Mode.VERIFY_REPORTS,
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(
                                    fixture.root().resolve("report/target/pmd/pmd.xml"),
                                    "version=\"7.26.0\"",
                                    "version=\"7.25.0\"");
                        },
                        "PMD report engine version must be 7.26.0"),
                new Scenario(
                        "PMD watchlist XML contains a policy rule",
                        Control.PMD,
                        Mode.VERIFY_REPORTS,
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.replace(
                                    fixture.root().resolve(
                                            "report/target/pmd-watchlist/pmd.xml"),
                                    "rule=\"CloseResource\"",
                                    "rule=\"UnusedLocalVariable\"");
                        },
                        "PMD XML contains a rule outside the selected policy",
                        "watchlist"));

        runHappyPath(Control.SPOTBUGS, Mode.VALIDATE);
        runHappyPath(Control.CPD, Mode.VALIDATE);
        runHappyPath(Control.PMD, Mode.VALIDATE);
        runHappyPath(Control.SPOTBUGS, Mode.VERIFY_REPORTS);
        runHappyPath(Control.CPD, Mode.VERIFY_REPORTS);
        runHappyPath(Control.PMD, Mode.VERIFY_REPORTS);
        runPmdWatchlistHappyPath();
        runPmdWatchlistMissingReportNegativePath();

        List<String> failures = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            try {
                runNegativeScenario(scenario);
            } catch (AssertionError | Exception e) {
                failures.add(scenario.name() + ": " + e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            throw new AssertionError(
                    "BuildQualityVerifier fixture failures:\n - "
                            + String.join("\n - ", failures));
        }
        System.out.printf(
                "BuildQualityVerifier: 7 happy paths and %d negative scenarios passed%n",
                scenarios.size() + 1);
    }

    private static void runPmdWatchlistHappyPath()
            throws Exception {
        try (Fixture fixture = Fixture.create(Control.PMD)) {
            fixture.writeValidReports();
            Result result = fixture.execute(Mode.VERIFY_REPORTS, "watchlist");
            require(
                    result.exitCode() == 0,
                    "PMD watchlist verify-reports should pass, stderr="
                            + result.standardError());
        }
    }

    private static void runPmdWatchlistMissingReportNegativePath()
            throws Exception {
        try (Fixture fixture = Fixture.create(Control.PMD)) {
            fixture.writeValidReports();
            Files.delete(fixture.root().resolve("report/target/pmd-watchlist/pmd.xml"));
            Result result = fixture.execute(Mode.VERIFY_REPORTS, "watchlist");
            require(
                    result.exitCode() != 0,
                    "PMD watchlist without XML unexpectedly passed");
            require(
                    result.standardError().contains("PMD XML is missing or empty"),
                    "PMD watchlist missing-report failure differs, stderr="
                            + result.standardError());
        }
    }

    private static void runHappyPath(Control control, Mode mode)
            throws Exception {
        try (Fixture fixture = Fixture.create(control)) {
            if (mode == Mode.VERIFY_REPORTS) {
                fixture.writeValidReports();
            }
            Result result = fixture.execute(mode);
            require(
                    result.exitCode() == 0,
                    control.externalName() + " " + mode.externalName()
                            + " should pass, stderr=" + result.standardError());
        }
    }

    private static void runNegativeScenario(Scenario scenario)
            throws Exception {
        try (Fixture fixture = Fixture.create(scenario.control())) {
            scenario.mutation().apply(fixture);
            Result result = fixture.execute(scenario.mode(), scenario.pmdReportKind());
            require(
                    result.exitCode() != 0,
                    "scenario unexpectedly passed");
            require(
                    result.standardError().contains(scenario.expectedError()),
                    "expected error containing <" + scenario.expectedError()
                            + ">, got <" + result.standardError() + ">");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String dependency(String artifactId) {
        return """
                  <dependency>
                    <groupId>com.iocextractor</groupId>
                    <artifactId>%s</artifactId>
                  </dependency>
                """.formatted(artifactId);
    }

    private static String sourceRoot(String module) {
        return "<compileSourceRoot>${maven.multiModuleProjectDirectory}/"
                + module + "/src/main/java</compileSourceRoot>";
    }

    private static String spotBugsSkipPlugin() {
        return """
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>spotbugs-maven-plugin</artifactId>
                        <configuration><skip>true</skip></configuration>
                      </plugin>
                    </plugins>
                  </build>
                """;
    }

    private enum Control {
        SPOTBUGS("spotbugs"),
        CPD("cpd"),
        PMD("pmd");

        private final String externalName;

        Control(String externalName) {
            this.externalName = externalName;
        }

        String externalName() {
            return externalName;
        }
    }

    private enum Mode {
        VALIDATE("validate"),
        VERIFY_REPORTS("verify-reports");

        private final String externalName;

        Mode(String externalName) {
            this.externalName = externalName;
        }

        String externalName() {
            return externalName;
        }
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(Fixture fixture) throws Exception;
    }

    private record Scenario(
            String name,
            Control control,
            Mode mode,
            Mutation mutation,
            String expectedError,
            String pmdReportKind) {

        Scenario(
                String name,
                Control control,
                Mode mode,
                Mutation mutation,
                String expectedError) {
            this(name, control, mode, mutation, expectedError, "policy");
        }
    }

    private record Result(
            int exitCode,
            String standardOutput,
            String standardError) {
    }

    private static final class Fixture implements AutoCloseable {

        private final Path root;
        private final Control control;
        private final Path manifest;
        private final Path reportPom;

        private Fixture(Path root, Control control) {
            this.root = root;
            this.control = control;
            this.manifest = root.resolve("scope.tsv");
            this.reportPom = root.resolve("report/pom.xml");
        }

        static Fixture create(Control control)
                throws IOException {
            Fixture fixture = new Fixture(
                    Files.createTempDirectory("build-quality-verifier-"),
                    control);
            fixture.writeBaseReactor();
            return fixture;
        }

        Path root() {
            return root;
        }

        void writeBaseReactor()
                throws IOException {
            write(
                    "pom.xml",
                    POM_HEADER
                            + """
                                <artifactId>root</artifactId>
                                <packaging>pom</packaging>
                                <properties>
                                  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                                  <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
                                  <pmd.version>7.26.0</pmd.version>
                                </properties>
                                <modules>
                                  <module>app</module>
                                  <module>support</module>
                                  <module>report</module>
                                </modules>
                            """
                            + (control == Control.SPOTBUGS ? spotBugsModulePlugin() : "")
                            + "</project>\n");
            addModule("app", "app", "jar", false);
            addModule("support", "support", "jar", control == Control.SPOTBUGS);
            write(
                    "app/src/main/java/App.java",
                    "final class App {}\n");
            write(
                    "support/src/main/java/Support.java",
                    "final class Support {}\n");
            write(
                    "scope.tsv",
                    """
                            # module-path	artifact-id	disposition	rationale
                            .	root	excluded	Root parent
                            app	app	analyzed	Production code
                            support	support	excluded	Test support
                            report	report	aggregate	Report owner
                            """);
            write(
                    "report/pom.xml",
                    switch (control) {
                        case SPOTBUGS -> spotBugsReportPom();
                        case CPD -> cpdReportPom();
                        case PMD -> pmdReportPom();
                    });
            if (control == Control.PMD) {
                write("report/pmd-ruleset.xml", pmdRuleset());
                write("report/pmd-watchlist-ruleset.xml", pmdWatchlistRuleset());
            }
        }

        void addModule(
                String modulePath,
                String artifactId,
                String packaging,
                boolean spotBugsSkipped)
                throws IOException {
            write(
                    modulePath + "/pom.xml",
                    POM_HEADER
                            + "  <artifactId>" + artifactId + "</artifactId>\n"
                            + "  <packaging>" + packaging + "</packaging>\n"
                            + (spotBugsSkipped ? spotBugsSkipPlugin() : "")
                            + "</project>\n");
        }

        void writeValidReports()
                throws IOException {
            if (control == Control.SPOTBUGS) {
                write("app/target/spotbugs/spotbugs.xml", "<BugCollection/>");
                write("app/target/spotbugs/spotbugs.html", "<html>app</html>");
                write("report/target/spotbugs/spotbugs.xml", "<BugCollection/>");
                write("report/target/spotbugs/spotbugs.html", "<html>aggregate</html>");
                return;
            }

            if (control == Control.PMD) {
                String source = root.resolve("app/src/main/java/App.java")
                        .toAbsolutePath()
                        .normalize()
                        .toString();
                writePmdReport("report/target/pmd", source, "UnusedLocalVariable");
                writePmdReport("report/target/pmd-watchlist", source, "CloseResource");
                return;
            }

            String source = root.resolve("app/src/main/java/App.java")
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            write(
                    "report/target/cpd/cpd.xml",
                    """
                            <pmd-cpd xmlns="https://pmd-code.org/schema/cpd-report"
                                     pmdVersion="test">
                              <duplication lines="1" tokens="1">
                                <file path="%s"/>
                              </duplication>
                            </pmd-cpd>
                            """.formatted(source));
            write(
                    "report/target/cpd/cpd.html",
                    "<html><title>CPD Results</title></html>");
        }

        private void writePmdReport(String reportDirectory, String source, String rule)
                throws IOException {
            write(
                        reportDirectory + "/pmd.xml",
                        """
                                <pmd xmlns="http://pmd.sourceforge.net/report/2.0.0"
                                     version="7.26.0">
                                  <file name="%s">
                                    <violation rule="%s" priority="3"
                                               beginline="1" endline="1"
                                               begincolumn="1" endcolumn="1">test</violation>
                                  </file>
                                </pmd>
                                """.formatted(source, rule));
            write(
                    reportDirectory + "/pmd.html",
                    "<html><title>PMD Results</title></html>");
        }

        Result execute(Mode mode) {
            return execute(mode, "policy");
        }

        Result execute(Mode mode, String pmdReportKind) {
            ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
            ByteArrayOutputStream standardError = new ByteArrayOutputStream();
            int exitCode;
            try (PrintStream out = new PrintStream(
                    standardOutput,
                    true,
                    StandardCharsets.UTF_8);
                    PrintStream err = new PrintStream(
                            standardError,
                            true,
                            StandardCharsets.UTF_8)) {
                List<String> arguments = new ArrayList<>(List.of(
                        control.externalName(),
                        mode.externalName(),
                        root.toString(),
                        manifest.toString(),
                        reportPom.toString()));
                if (control == Control.PMD && mode == Mode.VERIFY_REPORTS) {
                    arguments.add(pmdReportKind);
                }
                exitCode = BuildQualityVerifier.execute(
                        arguments.toArray(String[]::new),
                        out,
                        err);
            }
            return new Result(
                    exitCode,
                    standardOutput.toString(StandardCharsets.UTF_8),
                    standardError.toString(StandardCharsets.UTF_8));
        }

        void replaceRoot(String expected, String replacement)
                throws IOException {
            replace(root.resolve("pom.xml"), expected, replacement);
        }

        void replaceManifest(String expected, String replacement)
                throws IOException {
            replace(manifest, expected, replacement);
        }

        void appendManifest(String content)
                throws IOException {
            Files.writeString(
                    manifest,
                    content,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        }

        void replaceReport(String expected, String replacement)
                throws IOException {
            replace(reportPom, expected, replacement);
        }

        void replaceRuleset(String expected, String replacement)
                throws IOException {
            replace(root.resolve("report/pmd-ruleset.xml"), expected, replacement);
        }

        void replaceWatchlistRuleset(String expected, String replacement)
                throws IOException {
            replace(root.resolve("report/pmd-watchlist-ruleset.xml"), expected, replacement);
        }

        void replace(Path file, String expected, String replacement)
                throws IOException {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            require(content.contains(expected), "fixture mutation target is missing: " + expected);
            Files.writeString(
                    file,
                    content.replace(expected, replacement),
                    StandardCharsets.UTF_8);
        }

        void write(String relativePath, String content)
                throws IOException {
            Path file = root.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }

        void deleteTree(Path path)
                throws IOException {
            if (!Files.exists(path)) {
                return;
            }
            try (var paths = Files.walk(path)) {
                for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(candidate);
                }
            }
        }

        @Override
        public void close()
                throws IOException {
            deleteTree(root);
        }

        private String spotBugsReportPom() {
            return POM_HEADER
                    + """
                        <artifactId>report</artifactId>
                        <packaging>pom</packaging>
                        <dependencies>
                    """
                    + dependency("app")
                    + """
                        </dependencies>
                        <build>
                          <plugins>
                            <plugin>
                              <artifactId>spotbugs-maven-plugin</artifactId>
                              <configuration><skip>true</skip></configuration>
                              <executions>
                                <execution>
                                  <id>create-reactor-spotbugs-raw-report</id>
                                  <phase>verify</phase>
                                  <goals><goal>spotbugs-aggregate</goal></goals>
                                  <configuration>
                                    <skip>false</skip>
                                    <effort>Max</effort>
                                    <threshold>Low</threshold>
                                    <skipEmptyReport>false</skipEmptyReport>
                                    <outputDirectory>${project.build.directory}/spotbugs-raw</outputDirectory>
                                    <spotbugsXmlOutputFilename>spotbugs-raw/spotbugs-raw.xml</spotbugsXmlOutputFilename>
                                  </configuration>
                                </execution>
                                <execution>
                                  <id>create-reactor-spotbugs-report</id>
                                  <phase>verify</phase>
                                  <goals><goal>spotbugs-aggregate</goal></goals>
                                  <configuration>
                                    <skip>false</skip>
                                    <effort>Max</effort>
                                    <threshold>Low</threshold>
                                    <skipEmptyReport>false</skipEmptyReport>
                                    <outputDirectory>${project.build.directory}/spotbugs</outputDirectory>
                                    <spotbugsXmlOutputFilename>spotbugs/spotbugs.xml</spotbugsXmlOutputFilename>
                                  </configuration>
                                </execution>
                              </executions>
                            </plugin>
                          </plugins>
                        </build>
                      </project>
                    """;
        }

        private String spotBugsModulePlugin() {
            return """
                      <build>
                        <plugins>
                          <plugin>
                            <artifactId>spotbugs-maven-plugin</artifactId>
                            <executions>
                              <execution>
                                <id>analyze-production-bytecode</id>
                                <phase>verify</phase>
                                <goals><goal>spotbugs</goal></goals>
                                <configuration>
                                  <effort>Max</effort>
                                  <threshold>Low</threshold>
                                  <includeTests>false</includeTests>
                                  <failOnError>true</failOnError>
                                  <skipEmptyReport>false</skipEmptyReport>
                                  <htmlOutput>false</htmlOutput>
                                  <xmlOutput>false</xmlOutput>
                                  <outputDirectory>${project.build.directory}/spotbugs-raw</outputDirectory>
                                  <spotbugsXmlOutputDirectory>${project.build.directory}/spotbugs-raw</spotbugsXmlOutputDirectory>
                                  <spotbugsXmlOutputFilename>spotbugs-raw.xml</spotbugsXmlOutputFilename>
                                </configuration>
                              </execution>
                            </executions>
                          </plugin>
                        </plugins>
                      </build>
                    """;
        }

        private String cpdReportPom() {
            return POM_HEADER
                    + """
                        <artifactId>report</artifactId>
                        <packaging>pom</packaging>
                        <dependencies>
                    """
                    + dependency("app")
                    + """
                        </dependencies>
                        <build>
                          <plugins>
                            <plugin>
                              <artifactId>maven-pmd-plugin</artifactId>
                              <executions>
                                <execution>
                                  <id>create-repository-cpd-report</id>
                                  <phase>verify</phase>
                                  <goals><goal>aggregate-cpd</goal></goals>
                                  <configuration>
                                    <skip>false</skip>
                                    <language>java</language>
                                    <includeTests>false</includeTests>
                                    <skipEmptyReport>false</skipEmptyReport>
                                    <format>xml</format>
                                    <targetDirectory>${project.build.directory}/cpd</targetDirectory>
                                    <outputDirectory>${project.build.directory}/cpd</outputDirectory>
                                    <compileSourceRoots>
                                      %s
                                    </compileSourceRoots>
                                    <excludes>
                                      <exclude>**/generated/**</exclude>
                                      <exclude>**/vendor/**</exclude>
                                    </excludes>
                                  </configuration>
                                </execution>
                              </executions>
                            </plugin>
                          </plugins>
                        </build>
                      </project>
                    """.formatted(sourceRoot("app"));
        }

        private String pmdReportPom() {
            return POM_HEADER
                    + """
                        <artifactId>report</artifactId>
                        <packaging>pom</packaging>
                        <properties>
                          <ioc.pmd.ruleset>${project.basedir}/pmd-ruleset.xml</ioc.pmd.ruleset>
                          <ioc.pmd.reportDirectory>${project.build.directory}/pmd</ioc.pmd.reportDirectory>
                          <ioc.pmd.reportKind>policy</ioc.pmd.reportKind>
                        </properties>
                        <dependencies>
                    """
                    + dependency("app")
                    + """
                        </dependencies>
                        <profiles>
                          <profile>
                            <id>pmd-analysis</id>
                            <build>
                              <plugins>
                                <plugin>
                                  <artifactId>maven-pmd-plugin</artifactId>
                                  <dependencies>
                                    <dependency>
                                      <groupId>net.sourceforge.pmd</groupId>
                                      <artifactId>pmd-core</artifactId>
                                      <version>${pmd.version}</version>
                                    </dependency>
                                    <dependency>
                                      <groupId>net.sourceforge.pmd</groupId>
                                      <artifactId>pmd-java</artifactId>
                                      <version>${pmd.version}</version>
                                    </dependency>
                                  </dependencies>
                                  <executions>
                                    <execution>
                                      <id>create-repository-pmd-report</id>
                                      <phase>verify</phase>
                                      <goals><goal>aggregate-pmd-no-fork</goal></goals>
                                      <configuration>
                                        <skip>false</skip>
                                        <language>java</language>
                                        <targetJdk>${java.version}</targetJdk>
                                        <typeResolution>true</typeResolution>
                                        <includeTests>false</includeTests>
                                        <analysisCache>false</analysisCache>
                                        <skipPmdError>false</skipPmdError>
                                        <renderProcessingErrors>true</renderProcessingErrors>
                                        <skipEmptyReport>false</skipEmptyReport>
                                        <linkXRef>false</linkXRef>
                                        <format>xml</format>
                                        <targetDirectory>${ioc.pmd.reportDirectory}</targetDirectory>
                                        <outputDirectory>${ioc.pmd.reportDirectory}</outputDirectory>
                                        <rulesets>
                                          <ruleset>${ioc.pmd.ruleset}</ruleset>
                                        </rulesets>
                                        <compileSourceRoots>
                                          %s
                                        </compileSourceRoots>
                                        <excludes>
                                          <exclude>**/generated/**</exclude>
                                          <exclude>**/vendor/**</exclude>
                                        </excludes>
                                      </configuration>
                                    </execution>
                                  </executions>
                                </plugin>
                                <plugin>
                                  <artifactId>maven-antrun-plugin</artifactId>
                                  <executions>
                                    <execution>
                                      <id>clean-stale-pmd-output</id>
                                      <phase>initialize</phase>
                                      <goals><goal>run</goal></goals>
                                      <configuration>
                                        <target>
                                          <delete dir="${ioc.pmd.reportDirectory}" quiet="true"/>
                                        </target>
                                      </configuration>
                                    </execution>
                                    <execution>
                                      <id>verify-pmd-report-integrity</id>
                                      <phase>verify</phase>
                                      <goals><goal>run</goal></goals>
                                      <configuration>
                                        <target>
                                          <exec executable="${java.home}/bin/java" failonerror="true">
                                            <arg value="${maven.multiModuleProjectDirectory}/build-support/build-quality/BuildQualityVerifier.java"/>
                                            <arg value="pmd"/>
                                            <arg value="verify-reports"/>
                                            <arg value="${maven.multiModuleProjectDirectory}"/>
                                            <arg value="${project.basedir}/pmd-scope.tsv"/>
                                            <arg value="${project.basedir}/pom.xml"/>
                                            <arg value="${ioc.pmd.reportKind}"/>
                                          </exec>
                                        </target>
                                      </configuration>
                                    </execution>
                                  </executions>
                                </plugin>
                              </plugins>
                            </build>
                          </profile>
                          <profile>
                            <id>pmd-watchlist</id>
                            <properties>
                              <ioc.pmd.ruleset>${project.basedir}/pmd-watchlist-ruleset.xml</ioc.pmd.ruleset>
                              <ioc.pmd.reportDirectory>${project.build.directory}/pmd-watchlist</ioc.pmd.reportDirectory>
                              <ioc.pmd.reportKind>watchlist</ioc.pmd.reportKind>
                            </properties>
                          </profile>
                        </profiles>
                      </project>
                    """.formatted(sourceRoot("app"));
        }

        private String pmdRuleset() {
            StringBuilder rules = new StringBuilder();
            for (String rule : new TreeSet<>(BuildQualityVerifier.PMD_POLICY_RULES)) {
                rules.append("  <rule ref=\"").append(rule).append("\"");
                if (rule.endsWith("/CognitiveComplexity")) {
                    rules.append(">\n    <properties>\n"
                            + "      <property name=\"reportLevel\" value=\"16\"/>\n"
                            + "    </properties>\n  </rule>\n");
                } else if (rule.endsWith("/ExcessiveParameterList")) {
                    rules.append(">\n    <properties>\n"
                            + "      <property name=\"minimum\" value=\"13\"/>\n"
                            + "    </properties>\n  </rule>\n");
                } else {
                    rules.append("/>\n");
                }
            }
            return """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ruleset name="test"
                             xmlns="http://pmd.sourceforge.net/ruleset/2.0.0">
                    %s</ruleset>
                    """.formatted(rules);
        }

        private String pmdWatchlistRuleset() {
            StringBuilder rules = new StringBuilder();
            for (String rule : new TreeSet<>(BuildQualityVerifier.PMD_WATCHLIST_RULES)) {
                rules.append("  <rule ref=\"")
                        .append(rule)
                        .append("\"/>\n");
            }
            return """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ruleset name="watchlist-test"
                             xmlns="http://pmd.sourceforge.net/ruleset/2.0.0">
                    %s</ruleset>
                    """.formatted(rules);
        }
    }
}
