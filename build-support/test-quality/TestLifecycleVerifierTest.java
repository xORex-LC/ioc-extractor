import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Synthetic-reactor contract tests for {@link TestLifecycleVerifier}. */
public final class TestLifecycleVerifierTest {

    private TestLifecycleVerifierTest() {
    }

    public static void main(String[] args) throws Exception {
        int happyPaths = 0;
        int negativeScenarios = 0;

        try (Fixture fixture = Fixture.create()) {
            fixture.writeValidReports();
            assertSuccess(fixture, "validate");
            assertSuccess(fixture, "verify-reports");
            happyPaths += 2;
        }

        List<Scenario> scenarios = List.of(
                new Scenario(
                        "unknown tag",
                        fixture -> fixture.replace(
                                "app/src/test/java/sample/FastTest.java",
                                "class FastTest",
                                "@Tag(\"feature-x\") class FastTest"),
                        "unknown JUnit tag 'feature-x'"),
                new Scenario(
                        "Failsafe suite without integration metadata",
                        fixture -> fixture.replace(
                                "app/src/test/java/sample/DatabaseIT.java",
                                "@IntegrationTest\n",
                                ""),
                        "Failsafe suite is missing integration semantics"),
                new Scenario(
                        "integration metadata under Surefire",
                        fixture -> fixture.replace(
                                "app/src/test/java/sample/FastTest.java",
                                "class FastTest",
                                "@IntegrationTest class FastTest"),
                        "integration/E2E suite is owned by Surefire"),
                new Scenario(
                        "external suite without provisioning condition",
                        fixture -> fixture.replace(
                                "app/src/test/java/sample/ExternalIT.java",
                                "@EnabledIfSystemProperty(named = \"fixture\", matches = \"true\")\n",
                                ""),
                        "external suite must declare an explicit provisioning condition"),
                new Scenario(
                        "source count drift",
                        fixture -> fixture.replace(
                                "build-support/test-quality/test-lifecycle.properties",
                                "expected.fast=1",
                                "expected.fast=2"),
                        "fast suite count differs; expected=2, actual=1"),
                new Scenario(
                        "tag filters in regular lifecycle",
                        fixture -> fixture.replace(
                                "pom.xml",
                                "<skipTests>true</skipTests>",
                                "<skipTests>true</skipTests><groups>integration</groups>"),
                        "regular Maven lifecycle must not filter"),
                new Scenario(
                        "missing Failsafe report",
                        fixture -> {
                            fixture.writeValidReports();
                            Files.delete(fixture.root().resolve(
                                    "app/target/failsafe-reports/TEST-sample.DatabaseIT.xml"));
                        },
                        "Failsafe reports versus integration source selection differ"),
                new Scenario(
                        "suite reported by wrong engine",
                        fixture -> {
                            fixture.writeValidReports();
                            fixture.write(
                                    "app/target/surefire-reports/TEST-sample.DatabaseIT.xml",
                                    report("sample.DatabaseIT"));
                        },
                        "Surefire reports versus fast source selection differ"));

        for (Scenario scenario : scenarios) {
            try (Fixture fixture = Fixture.create()) {
                scenario.mutation().apply(fixture);
                assertFailure(fixture, scenario.name(), scenario.messageFragment());
                negativeScenarios++;
            }
        }

        System.out.printf(
                "[test-lifecycle-verifier-test] passed %d happy paths and %d negative scenarios%n",
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
            String scenarioName,
            String messageFragment) {
        Result result = run(fixture, "verify-reports");
        if (result.exitCode() == 0) {
            throw new AssertionError(
                    "expected failure for " + scenarioName + " containing: " + messageFragment
                            + ", stdout=" + result.standardOutput());
        }
        if (!result.errorOutput().contains(messageFragment)) {
            throw new AssertionError(
                    "expected error for " + scenarioName + " containing '" + messageFragment
                            + "', stderr="
                            + result.errorOutput());
        }
    }

    private static Result run(Fixture fixture, String mode) {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        int exitCode = TestLifecycleVerifier.execute(
                new String[]{
                        mode,
                        fixture.root().toString(),
                        fixture.root().resolve(
                                "build-support/test-quality/test-lifecycle.properties").toString()
                },
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        return new Result(
                exitCode,
                output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private static String report(String suiteName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<testsuite name=\"" + suiteName
                + "\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>";
    }

    private record Scenario(String name, Mutation mutation, String messageFragment) {
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(Fixture fixture) throws Exception;
    }

    private record Result(int exitCode, String standardOutput, String errorOutput) {
    }

    private static final class Fixture implements AutoCloseable {

        private final Path root;

        private Fixture(Path root) {
            this.root = root;
        }

        static Fixture create() throws IOException {
            Fixture fixture = new Fixture(Files.createTempDirectory("test-lifecycle-verifier-"));
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
                      <properties>
                        <maven-surefire-plugin.version>3.5.6</maven-surefire-plugin.version>
                      </properties>
                      <modules><module>app</module></modules>
                      <build><plugins>
                        <plugin>
                          <artifactId>maven-surefire-plugin</artifactId>
                          <version>${maven-surefire-plugin.version}</version>
                        </plugin>
                        <plugin>
                          <artifactId>maven-failsafe-plugin</artifactId>
                          <version>${maven-surefire-plugin.version}</version>
                          <executions><execution><goals>
                            <goal>integration-test</goal><goal>verify</goal>
                          </goals></execution></executions>
                        </plugin>
                        <plugin>
                          <artifactId>jacoco-maven-plugin</artifactId>
                          <executions><execution>
                            <id>prepare-coverage-agent</id>
                            <goals><goal>prepare-agent</goal></goals>
                            <configuration><append>true</append></configuration>
                          </execution></executions>
                        </plugin>
                        <plugin>
                          <artifactId>maven-antrun-plugin</artifactId>
                          <executions><execution>
                            <id>clean-stale-test-output</id><phase>initialize</phase>
                            <goals><goal>run</goal></goals>
                            <configuration><target unless="skipTests">
                              <delete dir="${project.build.directory}/surefire-reports"/>
                              <delete dir="${project.build.directory}/failsafe-reports"/>
                              <delete file="${project.build.directory}/failsafe-summary.xml"/>
                              <delete file="${project.build.directory}/jacoco.exec"/>
                            </target></configuration>
                          </execution></executions>
                        </plugin>
                      </plugins></build>
                      <profiles><profile>
                        <id>integration-tests-only</id>
                        <activation><property>
                          <name>skip.unit.tests</name><value>true</value>
                        </property></activation>
                        <build><plugins><plugin>
                          <artifactId>maven-surefire-plugin</artifactId>
                          <configuration><skipTests>true</skipTests></configuration>
                        </plugin></plugins></build>
                      </profile></profiles>
                    </project>
                    """);
            write("app/pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>");
            write("build-support/test-quality/test-lifecycle.properties", """
                    expected.fast=1
                    expected.integration=2
                    expected.external=1
                    expected.deterministicOffline=2
                    """);
            write("app/src/test/java/sample/FastTest.java", """
                    package sample;
                    import org.junit.jupiter.api.Tag;
                    import org.junit.jupiter.api.Test;
                    @Tag("contract")
                    class FastTest { @Test void runs() {} }
                    """);
            write("app/src/test/java/sample/DatabaseIT.java", """
                    package sample;
                    import com.iocextractor.application.tck.junit.IntegrationTest;
                    import org.junit.jupiter.api.Test;
                    @IntegrationTest
                    class DatabaseIT { @Test void runs() {} }
                    """);
            write("app/src/test/java/sample/ExternalIT.java", """
                    package sample;
                    import com.iocextractor.application.tck.junit.ExternalTest;
                    import org.junit.jupiter.api.Test;
                    import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
                    @EnabledIfSystemProperty(named = "fixture", matches = "true")
                    @ExternalTest
                    class ExternalIT { @Test void runs() {} }
                    """);
            for (var annotation : List.of(
                    new String[]{"IntegrationTest.java", "integration", ""},
                    new String[]{"ContractTest.java", "contract", ""},
                    new String[]{"EndToEndTest.java", "e2e", "@IntegrationTest\n"},
                    new String[]{"ExternalTest.java", "external", "@IntegrationTest\n"},
                    new String[]{"SlowTest.java", "slow", ""})) {
                write(
                        "core/ioc-application-tck/src/main/java/"
                                + "com/iocextractor/application/tck/junit/" + annotation[0],
                        "package com.iocextractor.application.tck.junit;\n"
                                + annotation[2]
                                + "@Tag(\"" + annotation[1] + "\")\n"
                                + "public @interface "
                                + annotation[0].substring(0, annotation[0].length() - 5)
                                + " {}\n");
            }
        }

        void writeValidReports() throws IOException {
            write("app/target/surefire-reports/TEST-sample.FastTest.xml", report("sample.FastTest"));
            write("app/target/failsafe-reports/TEST-sample.DatabaseIT.xml", report("sample.DatabaseIT"));
            write("app/target/failsafe-reports/TEST-sample.ExternalIT.xml", report("sample.ExternalIT"));
        }

        void replace(String relativePath, String existing, String replacement) throws IOException {
            Path file = root.resolve(relativePath);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (!content.contains(existing)) {
                throw new AssertionError(
                        "fixture replacement anchor is missing in " + relativePath + ": " + existing);
            }
            Files.writeString(
                    file,
                    content.replace(existing, replacement),
                    StandardCharsets.UTF_8);
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
