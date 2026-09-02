import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Verifies the repository-owned Surefire/Failsafe source taxonomy and report union.
 *
 * <p>The tool uses only JDK APIs so the root build can compile it before any
 * reactor project. Naming owns Maven lifecycle selection; tags describe test
 * semantics and may never silently remove a suite from the release gate.</p>
 */
public final class TestLifecycleVerifier {

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "integration", "contract", "architecture", "e2e", "slow", "external");
    private static final Pattern DIRECT_TAG = Pattern.compile(
            "@Tag\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;");
    private static final Pattern EXECUTABLE_TEST = Pattern.compile(
            "@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|ArchTest)\\b");
    private static final Pattern INHERITED_CONTRACT = Pattern.compile(
            "\\bextends\\s+[A-Za-z_$][A-Za-z0-9_$.]*ContractTest\\b");
    private static final Map<String, AnnotationContract> COMPOSED_ANNOTATIONS = Map.of(
            "IntegrationTest.java", new AnnotationContract("integration", null),
            "ContractTest.java", new AnnotationContract("contract", null),
            "EndToEndTest.java", new AnnotationContract("e2e", "@IntegrationTest"),
            "ExternalTest.java", new AnnotationContract("external", "@IntegrationTest"),
            "SlowTest.java", new AnnotationContract("slow", null));

    private TestLifecycleVerifier() {
    }

    public static void main(String[] args) {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] args, PrintStream standardOutput, PrintStream errorOutput) {
        try {
            if (args.length != 3) {
                throw new VerificationException(
                        "usage: TestLifecycleVerifier <validate|verify-reports> "
                                + "<reactor-root> <lifecycle-properties>");
            }
            Mode mode = Mode.parse(args[0]);
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            Path contractFile = Path.of(args[2]).toAbsolutePath().normalize();
            Inventory inventory = validate(root, contractFile);
            if (mode == Mode.VERIFY_REPORTS) {
                verifyReports(inventory);
            }
            standardOutput.printf(
                    Locale.ROOT,
                    "[test-lifecycle] %s completed: fast=%d, integration=%d, "
                            + "external=%d, deterministic-offline=%d%n",
                    mode.externalName(),
                    inventory.fastSuites().size(),
                    inventory.integrationSuites().size(),
                    inventory.externalSuites().size(),
                    inventory.deterministicOfflineCount());
            return 0;
        } catch (VerificationException e) {
            errorOutput.println("[test-lifecycle] ERROR: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            errorOutput.println(
                    "[test-lifecycle] ERROR: unexpected verifier failure: " + e.getMessage());
            e.printStackTrace(errorOutput);
            return 1;
        }
    }

    private static Inventory validate(Path root, Path contractFile) throws Exception {
        requireDirectory(root, "reactor root");
        Path rootPom = root.resolve("pom.xml");
        requireFile(rootPom, "root POM");
        requireFile(contractFile, "test lifecycle contract");
        requireUnderRoot(root, contractFile, "test lifecycle contract");

        ExpectedCounts expected = readExpectedCounts(contractFile);
        validateMavenLifecycle(rootPom);
        validateComposedAnnotations(root);

        LinkedHashSet<String> modulePaths = readReactorModules(rootPom);
        LinkedHashMap<String, SuiteSource> fast = new LinkedHashMap<>();
        LinkedHashMap<String, SuiteSource> integration = new LinkedHashMap<>();
        LinkedHashSet<String> external = new LinkedHashSet<>();

        for (String modulePath : modulePaths) {
            Path module = root.resolve(modulePath).normalize();
            requireUnderRoot(root, module, "reactor module " + modulePath);
            requireFile(module.resolve("pom.xml"), "POM for " + modulePath);
            scanUnknownTags(module, modulePath);
            Path testRoot = module.resolve("src/test/java");
            if (!Files.isDirectory(testRoot)) {
                continue;
            }
            try (Stream<Path> sources = Files.walk(testRoot)) {
                for (Path source : sources.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    classifySource(modulePath, testRoot, source, fast, integration, external);
                }
            }
        }

        compareCount("fast suite count", expected.fast(), fast.size());
        compareCount("integration suite count", expected.integration(), integration.size());
        compareCount("external suite count", expected.external(), external.size());
        int deterministicOffline = fast.size() + integration.size() - external.size();
        compareCount(
                "deterministic offline suite count",
                expected.deterministicOffline(),
                deterministicOffline);

        Set<String> overlap = new TreeSet<>(fast.keySet());
        overlap.retainAll(integration.keySet());
        if (!overlap.isEmpty()) {
            throw new VerificationException(
                    "Surefire and Failsafe source selections overlap: " + overlap);
        }
        return new Inventory(root, modulePaths, fast, integration, external, deterministicOffline);
    }

    private static void classifySource(
            String modulePath,
            Path testRoot,
            Path source,
            Map<String, SuiteSource> fast,
            Map<String, SuiteSource> integration,
            Set<String> external)
            throws IOException, VerificationException {
        String fileName = source.getFileName().toString();
        String simpleName = fileName.substring(0, fileName.length() - ".java".length());
        boolean selectedBySurefire = isSurefireName(simpleName);
        boolean selectedByFailsafe = isFailsafeName(simpleName);
        String body = Files.readString(source, StandardCharsets.UTF_8);
        boolean executableSuite = EXECUTABLE_TEST.matcher(body).find()
                || INHERITED_CONTRACT.matcher(body).find();

        if (!selectedBySurefire && !selectedByFailsafe) {
            if (executableSuite) {
                throw new VerificationException(
                        "executable test source is outside Surefire/Failsafe naming: "
                                + modulePath + "/src/test/java/" + testRoot.relativize(source));
            }
            return;
        }
        if (!executableSuite) {
            return;
        }
        if (selectedBySurefire && selectedByFailsafe) {
            throw new VerificationException("ambiguous test lifecycle name: " + source);
        }
        if (!Pattern.compile("\\b(class|record|interface)\\s+" + Pattern.quote(simpleName)
                + "\\b").matcher(body).find()) {
            throw new VerificationException(
                    "selected test source does not declare matching top-level type "
                            + simpleName + ": " + source);
        }

        Set<String> tags = sourceTags(body);
        boolean integrationTag = tags.contains("integration");
        if (selectedByFailsafe && !integrationTag) {
            throw new VerificationException(
                    "Failsafe suite is missing integration semantics: " + source);
        }
        if (selectedBySurefire && integrationTag) {
            throw new VerificationException(
                    "integration/E2E suite is owned by Surefire instead of Failsafe: " + source);
        }
        if ((simpleName.contains("IntegrationTest") || simpleName.contains("E2ETest"))
                && !selectedByFailsafe) {
            throw new VerificationException(
                    "integration/E2E test name is outside Failsafe lifecycle: " + source);
        }
        if ((tags.contains("external") || tags.contains("e2e")) && !integrationTag) {
            throw new VerificationException(
                    "external/E2E tag must compose integration semantics: " + source);
        }
        if (tags.contains("external") && !body.contains("@EnabledIfSystemProperty")) {
            throw new VerificationException(
                    "external suite must declare an explicit provisioning condition: " + source);
        }

        Matcher packageMatcher = PACKAGE.matcher(body);
        if (!packageMatcher.find()) {
            throw new VerificationException("selected test source has no package declaration: " + source);
        }
        String className = packageMatcher.group(1) + "." + simpleName;
        SuiteSource suite = new SuiteSource(source);
        Map<String, SuiteSource> owner = selectedByFailsafe ? integration : fast;
        SuiteSource previous = owner.putIfAbsent(className, suite);
        if (previous != null) {
            throw new VerificationException(
                    "duplicate selected test class " + className + ": "
                            + previous.source() + " and " + source);
        }
        if (tags.contains("external")) {
            external.add(className);
        }
    }

    private static Set<String> sourceTags(String body) throws VerificationException {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Matcher matcher = DIRECT_TAG.matcher(body);
        while (matcher.find()) {
            String tag = matcher.group(1);
            requireAllowedTag(tag, "test source");
            tags.add(tag);
        }
        if (body.contains("@IntegrationTest")) {
            tags.add("integration");
        }
        if (body.contains("@ContractTest")) {
            tags.add("contract");
        }
        if (body.contains("@EndToEndTest")) {
            tags.add("integration");
            tags.add("e2e");
        }
        if (body.contains("@ExternalTest")) {
            tags.add("integration");
            tags.add("external");
        }
        if (body.contains("@SlowTest")) {
            tags.add("slow");
        }
        return Set.copyOf(tags);
    }

    private static void scanUnknownTags(Path module, String modulePath)
            throws IOException, VerificationException {
        for (String sourcePath : List.of("src/main/java", "src/test/java")) {
            Path sourceRoot = module.resolve(sourcePath);
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                for (Path source : sources.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .toList()) {
                    Matcher matcher = DIRECT_TAG.matcher(
                            Files.readString(source, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        requireAllowedTag(
                                matcher.group(1),
                                modulePath + "/" + module.relativize(source));
                    }
                }
            }
        }
    }

    private static void validateComposedAnnotations(Path root)
            throws IOException, VerificationException {
        Path annotationRoot = root.resolve(
                "core/ioc-application-tck/src/main/java/"
                        + "com/iocextractor/application/tck/junit");
        requireDirectory(annotationRoot, "composed test annotation package");
        for (Map.Entry<String, AnnotationContract> entry : COMPOSED_ANNOTATIONS.entrySet()) {
            Path source = annotationRoot.resolve(entry.getKey());
            requireFile(source, "composed test annotation " + entry.getKey());
            String body = Files.readString(source, StandardCharsets.UTF_8);
            AnnotationContract contract = entry.getValue();
            if (!body.contains("@Tag(\"" + contract.tag() + "\")")) {
                throw new VerificationException(
                        entry.getKey() + " must declare @Tag(\"" + contract.tag() + "\")");
            }
            if (contract.metaAnnotation() != null && !body.contains(contract.metaAnnotation())) {
                throw new VerificationException(
                        entry.getKey() + " must declare " + contract.metaAnnotation());
            }
        }
    }

    private static void validateMavenLifecycle(Path rootPom) throws Exception {
        Document document = parseXml(rootPom);
        Element project = document.getDocumentElement();
        Element build = directChild(project, "build");
        Element plugins = directChild(build, "plugins");
        Element surefire = directPlugin(plugins, "maven-surefire-plugin");
        Element failsafe = directPlugin(plugins, "maven-failsafe-plugin");
        Element jacoco = directPlugin(plugins, "jacoco-maven-plugin");
        Element antrun = directPlugin(plugins, "maven-antrun-plugin");

        if (!"${maven-surefire-plugin.version}".equals(directText(surefire, "version"))) {
            throw new VerificationException("Surefire version must use the pinned shared property");
        }
        if (!"${maven-surefire-plugin.version}".equals(directText(failsafe, "version"))) {
            throw new VerificationException("Failsafe version must use the pinned shared property");
        }
        Set<String> goals = new LinkedHashSet<>();
        Element executions = directChild(failsafe, "executions");
        for (Element execution : directChildren(executions, "execution")) {
            Element executionGoals = directChild(execution, "goals");
            for (Element goal : directChildren(executionGoals, "goal")) {
                goals.add(goal.getTextContent().trim());
            }
        }
        if (!goals.equals(Set.of("integration-test", "verify"))) {
            throw new VerificationException(
                    "Failsafe lifecycle goals differ; expected integration-test and verify, found "
                            + goals);
        }
        validateIntegrationOnlyProfile(project);
        validateCoverageAndCleanup(jacoco, antrun);
        String projectXml = Files.readString(rootPom, StandardCharsets.UTF_8);
        if (projectXml.contains("<groups>") || projectXml.contains("<excludedGroups>")) {
            throw new VerificationException(
                    "regular Maven lifecycle must not filter the accepted universe by JUnit tags");
        }
    }

    private static void validateCoverageAndCleanup(Element jacoco, Element antrun)
            throws VerificationException {
        Element prepareAgent = directExecution(jacoco, "prepare-coverage-agent");
        Set<String> agentGoals = new LinkedHashSet<>();
        for (Element goal : directChildren(directChild(prepareAgent, "goals"), "goal")) {
            agentGoals.add(goal.getTextContent().trim());
        }
        if (!agentGoals.equals(Set.of("prepare-agent"))
                || !"true".equals(directText(
                        directChild(prepareAgent, "configuration"), "append"))) {
            throw new VerificationException(
                    "JaCoCo prepare-coverage-agent must append Surefire and Failsafe data");
        }

        Element cleanup = directExecution(antrun, "clean-stale-test-output");
        if (!"initialize".equals(directText(cleanup, "phase"))) {
            throw new VerificationException(
                    "clean-stale-test-output must run in initialize");
        }
        Element cleanupTarget = directChild(directChild(cleanup, "configuration"), "target");
        if (!"skipTests".equals(cleanupTarget.getAttribute("unless"))) {
            throw new VerificationException(
                    "test-output cleanup must preserve reports during -DskipTests analysis");
        }
        Set<String> cleanedPaths = new LinkedHashSet<>();
        for (Element delete : directChildren(cleanupTarget, "delete")) {
            for (String attribute : List.of("dir", "file")) {
                String path = delete.getAttribute(attribute);
                if (!path.isBlank()) {
                    cleanedPaths.add(path);
                }
            }
        }
        Set<String> expectedPaths = Set.of(
                "${project.build.directory}/surefire-reports",
                "${project.build.directory}/failsafe-reports",
                "${project.build.directory}/failsafe-summary.xml",
                "${project.build.directory}/jacoco.exec",
                "${project.reporting.outputDirectory}/jacoco");
        if (!cleanedPaths.equals(expectedPaths)) {
            throw new VerificationException(
                    "test-output cleanup paths differ; expected=" + expectedPaths
                            + ", actual=" + cleanedPaths);
        }
    }

    private static void validateIntegrationOnlyProfile(Element project)
            throws VerificationException {
        Element profiles = directChild(project, "profiles");
        Element integrationOnly = null;
        for (Element profile : directChildren(profiles, "profile")) {
            if ("integration-tests-only".equals(directText(profile, "id"))) {
                integrationOnly = profile;
                break;
            }
        }
        if (integrationOnly == null) {
            throw new VerificationException("root build is missing integration-tests-only profile");
        }
        Element activationProperty = directChild(
                directChild(integrationOnly, "activation"), "property");
        if (!"skip.unit.tests".equals(directText(activationProperty, "name"))
                || !"true".equals(directText(activationProperty, "value"))) {
            throw new VerificationException(
                    "integration-tests-only must activate only for skip.unit.tests=true");
        }
        Element profilePlugins = directChild(
                directChild(integrationOnly, "build"), "plugins");
        Element profileSurefire = directPlugin(profilePlugins, "maven-surefire-plugin");
        Element configuration = directChild(profileSurefire, "configuration");
        if (!"true".equals(directText(configuration, "skipTests"))) {
            throw new VerificationException(
                    "integration-tests-only must set Surefire skipTests to true");
        }
    }

    private static void verifyReports(Inventory inventory) throws Exception {
        LinkedHashSet<String> surefireReports = new LinkedHashSet<>();
        LinkedHashSet<String> failsafeReports = new LinkedHashSet<>();
        for (String modulePath : inventory.modulePaths()) {
            Path target = inventory.root().resolve(modulePath).resolve("target");
            readReportDirectory(target.resolve("surefire-reports"), surefireReports);
            readReportDirectory(target.resolve("failsafe-reports"), failsafeReports);
        }
        compareSets(
                "Surefire reports versus fast source selection",
                inventory.fastSuites().keySet(),
                surefireReports);
        compareSets(
                "Failsafe reports versus integration source selection",
                inventory.integrationSuites().keySet(),
                failsafeReports);
        Set<String> overlap = new TreeSet<>(surefireReports);
        overlap.retainAll(failsafeReports);
        if (!overlap.isEmpty()) {
            throw new VerificationException(
                    "Surefire and Failsafe reports overlap: " + overlap);
        }
    }

    private static void readReportDirectory(Path reportDirectory, Set<String> reports)
            throws Exception {
        if (!Files.isDirectory(reportDirectory)) {
            return;
        }
        try (Stream<Path> files = Files.list(reportDirectory)) {
            for (Path report : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("TEST-"))
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList()) {
                Document document = parseXml(report);
                Element root = document.getDocumentElement();
                if (!"testsuite".equals(root.getTagName())) {
                    throw new VerificationException(
                            "unexpected test report root in " + report + ": " + root.getTagName());
                }
                String suiteName = root.getAttribute("name").trim();
                if (suiteName.isEmpty()) {
                    throw new VerificationException("test report has no suite name: " + report);
                }
                if (!reports.add(suiteName)) {
                    throw new VerificationException(
                            "duplicate test report suite " + suiteName + " at " + report);
                }
            }
        }
    }

    private static ExpectedCounts readExpectedCounts(Path contractFile)
            throws IOException, VerificationException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(contractFile)) {
            properties.load(input);
        }
        Set<String> expectedKeys = Set.of(
                "expected.fast",
                "expected.integration",
                "expected.external",
                "expected.deterministicOffline");
        if (!properties.stringPropertyNames().equals(expectedKeys)) {
            throw new VerificationException(
                    "test lifecycle contract keys differ; expected=" + expectedKeys
                            + ", actual=" + properties.stringPropertyNames());
        }
        return new ExpectedCounts(
                nonNegativeInteger(properties, "expected.fast"),
                nonNegativeInteger(properties, "expected.integration"),
                nonNegativeInteger(properties, "expected.external"),
                nonNegativeInteger(properties, "expected.deterministicOffline"));
    }

    private static int nonNegativeInteger(Properties properties, String key)
            throws VerificationException {
        String value = properties.getProperty(key);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new VerificationException(key + " must be a non-negative decimal integer");
        }
    }

    private static LinkedHashSet<String> readReactorModules(Path rootPom) throws Exception {
        Document document = parseXml(rootPom);
        Element modules = directChild(document.getDocumentElement(), "modules");
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Element module : directChildren(modules, "module")) {
            String path = module.getTextContent().trim().replace('\\', '/');
            if (path.isEmpty() || path.startsWith("/") || path.contains("..")) {
                throw new VerificationException("invalid reactor module path: " + path);
            }
            if (!paths.add(path)) {
                throw new VerificationException("duplicate reactor module path: " + path);
            }
        }
        if (paths.isEmpty()) {
            throw new VerificationException("root POM declares no reactor modules");
        }
        return paths;
    }

    private static Document parseXml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (InputStream input = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Element directPlugin(Element plugins, String artifactId)
            throws VerificationException {
        for (Element plugin : directChildren(plugins, "plugin")) {
            if (artifactId.equals(directText(plugin, "artifactId"))) {
                return plugin;
            }
        }
        throw new VerificationException("root build is missing " + artifactId);
    }

    private static Element directExecution(Element plugin, String executionId)
            throws VerificationException {
        Element executions = directChild(plugin, "executions");
        for (Element execution : directChildren(executions, "execution")) {
            if (executionId.equals(directText(execution, "id"))) {
                return execution;
            }
        }
        throw new VerificationException(
                "plugin " + directText(plugin, "artifactId")
                        + " is missing execution " + executionId);
    }

    private static Element directChild(Element parent, String name)
            throws VerificationException {
        for (Element child : directChildren(parent, name)) {
            return child;
        }
        throw new VerificationException(
                "missing <" + name + "> under <" + parent.getTagName() + ">");
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                children.add(element);
            }
        }
        return children;
    }

    private static String directText(Element parent, String name)
            throws VerificationException {
        return directChild(parent, name).getTextContent().trim();
    }

    private static boolean isSurefireName(String simpleName) {
        return simpleName.startsWith("Test")
                || simpleName.endsWith("Test")
                || simpleName.endsWith("Tests")
                || simpleName.endsWith("TestCase");
    }

    private static boolean isFailsafeName(String simpleName) {
        return simpleName.startsWith("IT")
                || simpleName.endsWith("IT")
                || simpleName.endsWith("ITCase");
    }

    private static void requireAllowedTag(String tag, String owner)
            throws VerificationException {
        if (!ALLOWED_TAGS.contains(tag)) {
            throw new VerificationException(
                    "unknown JUnit tag '" + tag + "' in " + owner
                            + "; allowed=" + new TreeSet<>(ALLOWED_TAGS));
        }
    }

    private static void compareCount(String label, int expected, int actual)
            throws VerificationException {
        if (expected != actual) {
            throw new VerificationException(
                    label + " differs; expected=" + expected + ", actual=" + actual);
        }
    }

    private static void compareSets(String label, Set<String> expected, Set<String> actual)
            throws VerificationException {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new VerificationException(
                    label + " differ; missing=" + missing + ", unexpected=" + unexpected);
        }
    }

    private static void requireDirectory(Path path, String label) throws VerificationException {
        if (!Files.isDirectory(path)) {
            throw new VerificationException(label + " does not exist: " + path);
        }
    }

    private static void requireFile(Path path, String label) throws VerificationException {
        if (!Files.isRegularFile(path)) {
            throw new VerificationException(label + " does not exist: " + path);
        }
    }

    private static void requireUnderRoot(Path root, Path path, String label)
            throws VerificationException {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new VerificationException(label + " escapes reactor root: " + path);
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

        static Mode parse(String value) throws VerificationException {
            for (Mode mode : values()) {
                if (mode.externalName.equals(value)) {
                    return mode;
                }
            }
            throw new VerificationException("unknown mode: " + value);
        }
    }

    private record AnnotationContract(String tag, String metaAnnotation) {
    }

    private record ExpectedCounts(
            int fast,
            int integration,
            int external,
            int deterministicOffline) {
    }

    private record SuiteSource(Path source) {
    }

    private record Inventory(
            Path root,
            Set<String> modulePaths,
            Map<String, SuiteSource> fastSuites,
            Map<String, SuiteSource> integrationSuites,
            Set<String> externalSuites,
            int deterministicOfflineCount) {
    }

    private static final class VerificationException extends Exception {
        private VerificationException(String message) {
            super(message);
        }
    }
}
