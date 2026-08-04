import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Verifies fail-closed SpotBugs and CPD scope registries and their generated reports.
 *
 * <p>This source-file tool deliberately uses only JDK APIs so Maven can run it
 * with {@code java BuildQualityVerifier.java ...} without adding a build-time
 * library or a custom Maven plugin.</p>
 */
public final class BuildQualityVerifier {

    private static final String ROOT_PATH = ".";
    private static final String PROJECT_GROUP = "com.iocextractor";
    private static final String SPOTBUGS_PLUGIN = "spotbugs-maven-plugin";
    private static final String SPOTBUGS_AGGREGATE_GOAL = "spotbugs-aggregate";
    private static final String SPOTBUGS_MODULE_GOAL = "spotbugs";
    private static final String SPOTBUGS_MODULE_EXECUTION = "analyze-production-bytecode";
    private static final String SPOTBUGS_RAW_EXECUTION = "create-reactor-spotbugs-raw-report";
    private static final String SPOTBUGS_FILTERED_EXECUTION = "create-reactor-spotbugs-report";
    private static final String PMD_PLUGIN = "maven-pmd-plugin";
    private static final String CPD_EXECUTION = "create-repository-cpd-report";
    private static final String CPD_NAMESPACE = "https://pmd-code.org/schema/cpd-report";
    private static final String SOURCE_ROOT_PREFIX = "${maven.multiModuleProjectDirectory}/";
    private static final String SOURCE_ROOT_SUFFIX = "/src/main/java";
    private static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9_.-]+");

    private BuildQualityVerifier() {
    }

    public static void main(String[] args) {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(
            String[] args,
            java.io.PrintStream standardOutput,
            java.io.PrintStream errorOutput) {
        try {
            if (args.length != 5) {
                throw new VerificationException(
                        "usage: BuildQualityVerifier <spotbugs|cpd> <validate|verify-reports> "
                                + "<reactor-root> <scope-manifest> <report-pom>");
            }

            Control control = Control.parse(args[0]);
            Mode mode = Mode.parse(args[1]);
            Path root = Path.of(args[2]).toAbsolutePath().normalize();
            Path manifest = Path.of(args[3]).toAbsolutePath().normalize();
            Path reportPom = Path.of(args[4]).toAbsolutePath().normalize();
            Registry registry = validateScope(control, root, manifest, reportPom);

            if (mode == Mode.VERIFY_REPORTS) {
                verifyReports(control, registry);
            }
            standardOutput.printf(
                    Locale.ROOT,
                    "[%s-scope] %s completed%n",
                    control.externalName(),
                    mode.externalName());
            return 0;
        } catch (VerificationException e) {
            errorOutput.println("[build-quality] ERROR: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            errorOutput.println(
                    "[build-quality] ERROR: unexpected verifier failure: " + e.getMessage());
            e.printStackTrace(errorOutput);
            return 1;
        }
    }

    private static Registry validateScope(
            Control control,
            Path root,
            Path manifest,
            Path reportPom)
            throws Exception {
        requireDirectory(root, "reactor root");
        requireFile(root.resolve("pom.xml"), "root POM");
        requireFile(manifest, "scope manifest");
        requireFile(reportPom, "report POM");
        requireUnderRoot(root, reportPom, "report POM");

        LinkedHashMap<String, ScopeEntry> entries = readManifest(manifest);
        LinkedHashSet<String> reactorPaths = readReactorPaths(root.resolve("pom.xml"));
        compareSets(
                "scope manifest paths versus root Maven reactor",
                reactorPaths,
                entries.keySet());

        String reportModulePath = toModulePath(root.relativize(reportPom.getParent()));
        Map<String, String> artifactOwners = new HashMap<>();
        EnumMap<Disposition, Integer> counts = new EnumMap<>(Disposition.class);
        LinkedHashSet<String> analyzedArtifacts = new LinkedHashSet<>();
        LinkedHashSet<String> configuredSourceRoots = new LinkedHashSet<>();
        List<Path> analyzedSourceRoots = new ArrayList<>();

        for (ScopeEntry entry : entries.values()) {
            Path moduleDirectory = ROOT_PATH.equals(entry.modulePath())
                    ? root
                    : root.resolve(entry.modulePath()).normalize();
            requireUnderRoot(root, moduleDirectory, "module " + entry.modulePath());
            Path modulePom = moduleDirectory.resolve("pom.xml");
            requireFile(modulePom, "POM for " + entry.modulePath());

            ProjectModel project = readProject(modulePom);
            if (!entry.artifactId().equals(project.artifactId())) {
                throw new VerificationException(
                        "manifest artifactId mismatch for " + entry.modulePath()
                                + ": expected " + project.artifactId()
                                + " from its POM, found " + entry.artifactId());
            }

            String previousOwner = artifactOwners.putIfAbsent(
                    entry.artifactId(),
                    entry.modulePath());
            if (previousOwner != null) {
                throw new VerificationException(
                        "duplicate reactor artifactId " + entry.artifactId()
                                + " for " + previousOwner + " and " + entry.modulePath());
            }

            counts.merge(entry.disposition(), 1, Integer::sum);
            switch (entry.disposition()) {
                case ANALYZED -> {
                    if (!"jar".equals(project.packaging())) {
                        throw new VerificationException(
                                "analyzed module must use jar packaging: " + entry.modulePath()
                                        + " uses " + project.packaging());
                    }
                    analyzedArtifacts.add(entry.artifactId());
                    if (control == Control.SPOTBUGS) {
                        if (project.spotBugsSkipped()) {
                            throw new VerificationException(
                                    "analyzed module explicitly skips SpotBugs: "
                                            + entry.modulePath());
                        }
                    } else {
                        Path sourceRoot = moduleDirectory.resolve("src/main/java").normalize();
                        requireDirectory(
                                sourceRoot,
                                "production source root for " + entry.modulePath());
                        analyzedSourceRoots.add(sourceRoot);
                        configuredSourceRoots.add(
                                SOURCE_ROOT_PREFIX + entry.modulePath() + SOURCE_ROOT_SUFFIX);
                    }
                }
                case EXCLUDED -> {
                    if (entry.modulePath().equals(reportModulePath)) {
                        throw new VerificationException(
                                control.displayName()
                                        + " report module must use aggregate disposition: "
                                        + reportModulePath);
                    }
                    if (control == Control.SPOTBUGS) {
                        validateSpotBugsExcludedProject(entry, project);
                    }
                }
                case AGGREGATE -> {
                    if (!entry.modulePath().equals(reportModulePath)) {
                        throw new VerificationException(
                                "aggregate disposition belongs to " + reportModulePath
                                        + ", not " + entry.modulePath());
                    }
                    if (!"pom".equals(project.packaging())) {
                        throw new VerificationException(
                                control.displayName()
                                        + " aggregate must use pom packaging: "
                                        + entry.modulePath());
                    }
                    if (control == Control.SPOTBUGS && !project.spotBugsSkipped()) {
                        throw new VerificationException(
                                "SpotBugs aggregate must skip inherited per-module analysis: "
                                        + entry.modulePath());
                    }
                }
            }
        }

        if (counts.getOrDefault(Disposition.AGGREGATE, 0) != 1) {
            throw new VerificationException(
                    "scope manifest must contain exactly one aggregate disposition");
        }

        Set<String> dependencyArtifacts = readReportDependencies(control, reportPom);
        compareSets(
                "analyzed scope versus " + control.displayName() + " report-module dependencies",
                analyzedArtifacts,
                dependencyArtifacts);

        if (control == Control.SPOTBUGS) {
            validateSpotBugsModuleConfiguration(
                    readSpotBugsModuleConfiguration(root.resolve("pom.xml")));
            validateSpotBugsAggregateConfiguration(
                    readSpotBugsAggregateConfiguration(reportPom));
        } else {
            CpdConfiguration configuration = readCpdConfiguration(reportPom);
            compareSets(
                    "analyzed scope versus configured CPD source roots",
                    configuredSourceRoots,
                    configuration.sourceRoots());
            validateCpdConfiguration(configuration);
        }

        Registry registry = new Registry(
                root,
                List.copyOf(entries.values()),
                List.copyOf(analyzedSourceRoots),
                reportPom.getParent());
        return registry;
    }

    private static void validateSpotBugsExcludedProject(
            ScopeEntry entry,
            ProjectModel project)
            throws VerificationException {
        if (ROOT_PATH.equals(entry.modulePath())) {
            if (!"pom".equals(project.packaging())) {
                throw new VerificationException("root parent exclusion requires pom packaging");
            }
            return;
        }
        if (!project.spotBugsSkipped()) {
            throw new VerificationException(
                    "excluded module must explicitly configure SpotBugs skip=true: "
                            + entry.modulePath());
        }
    }

    private static void validateCpdConfiguration(CpdConfiguration configuration)
            throws VerificationException {
        requireValue("CPD execution phase", "verify", configuration.phase());
        compareSets(
                "CPD execution goals",
                Set.of("aggregate-cpd"),
                configuration.goals());
        requireValue("CPD skip", "false", configuration.skip());
        requireValue("CPD language", "java", configuration.language());
        requireValue("CPD includeTests", "false", configuration.includeTests());
        requireValue("CPD skipEmptyReport", "false", configuration.skipEmptyReport());
        requireValue("CPD format", "xml", configuration.format());
        requireValue(
                "CPD targetDirectory",
                "${project.build.directory}/cpd",
                configuration.targetDirectory());
        requireValue(
                "CPD outputDirectory",
                "${project.build.directory}/cpd",
                configuration.outputDirectory());
        compareSets(
                "CPD generated/vendor excludes",
                Set.of("**/generated/**", "**/vendor/**"),
                configuration.excludes());
    }

    private static void requireValue(String subject, String expected, String actual)
            throws VerificationException {
        if (!expected.equals(actual)) {
            throw new VerificationException(
                    subject + " must be " + expected + ", found " + actual);
        }
    }

    private static void verifyReports(Control control, Registry registry)
            throws Exception {
        if (control == Control.SPOTBUGS) {
            verifySpotBugsReports(registry);
        } else {
            verifyCpdReports(registry);
        }
    }

    private static void verifyCpdReports(Registry registry)
            throws Exception {
        Path reportDirectory = registry.reportModuleDirectory().resolve("target/cpd");
        Path xml = reportDirectory.resolve("cpd.xml");
        Path html = reportDirectory.resolve("cpd.html");
        requireNonEmptyReport(xml, "CPD XML");
        requireNonEmptyReport(html, "CPD HTML");

        Set<Path> expectedSources = collectExpectedSources(registry.analyzedSourceRoots());
        CpdReport report = readCpdReport(registry.root(), xml);
        compareSets(
                "expected production Java sources versus CPD XML unique file paths",
                expectedSources,
                report.sourceFiles());
        verifyHtml(html);

    }

    private static void verifySpotBugsReports(Registry registry)
            throws Exception {
        List<String> problems = new ArrayList<>();

        for (ScopeEntry entry : registry.entries()) {
            Path moduleDirectory = ROOT_PATH.equals(entry.modulePath())
                    ? registry.root()
                    : registry.root().resolve(entry.modulePath());
            Path reportDirectory = moduleDirectory.resolve("target/spotbugs");
            Path xml = reportDirectory.resolve("spotbugs.xml");
            Path html = reportDirectory.resolve("spotbugs.html");

            if (entry.disposition() == Disposition.EXCLUDED) {
                rejectUnexpectedSpotBugsReport(xml, entry, problems);
                rejectUnexpectedSpotBugsReport(html, entry, problems);
                continue;
            }

            verifySpotBugsReportPair(xml, html, entry, problems);
        }

        if (!problems.isEmpty()) {
            throw new VerificationException(
                    "SpotBugs report integrity failed:\n - "
                            + String.join("\n - ", problems));
        }
    }

    private static void verifySpotBugsReportPair(
            Path xml,
            Path html,
            ScopeEntry entry,
            List<String> problems) {
        try {
            requireNonEmptyReport(
                    xml,
                    "SpotBugs XML for " + entry.disposition().externalName()
                            + " " + entry.modulePath());
            requireNonEmptyReport(
                    html,
                    "SpotBugs HTML for " + entry.disposition().externalName()
                            + " " + entry.modulePath());
            verifySpotBugsXml(xml);
            verifySpotBugsHtml(html);
        } catch (Exception e) {
            problems.add(e.getMessage());
        }
    }

    private static void rejectUnexpectedSpotBugsReport(
            Path report,
            ScopeEntry entry,
            List<String> problems) {
        if (Files.exists(report)) {
            problems.add(
                    "excluded module produced a SpotBugs report (" + entry.rationale()
                            + "): " + report);
        }
    }

    private static void verifySpotBugsXml(Path xml)
            throws Exception {
        Element root = parseXml(xml).getDocumentElement();
        if (!"BugCollection".equals(root.getLocalName())) {
            throw new VerificationException(
                    "unexpected SpotBugs XML root in " + xml + ": " + root.getLocalName());
        }
    }

    private static void verifySpotBugsHtml(Path html)
            throws IOException, VerificationException {
        String content = Files.readString(html, StandardCharsets.UTF_8);
        if (!content.toLowerCase(Locale.ROOT).contains("<html")) {
            throw new VerificationException(
                    "SpotBugs HTML does not contain an HTML document: " + html);
        }
    }

    private static Set<Path> collectExpectedSources(List<Path> sourceRoots)
            throws IOException, VerificationException {
        LinkedHashSet<Path> sources = new LinkedHashSet<>();
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                for (Path path : paths
                        .filter(Files::isRegularFile)
                        .filter(candidate -> candidate.getFileName().toString().endsWith(".java"))
                        .filter(candidate -> !isGeneratedOrVendor(sourceRoot, candidate))
                        .toList()) {
                    Path normalized = path.toAbsolutePath().normalize();
                    if (!sources.add(normalized)) {
                        throw new VerificationException(
                                "duplicate production source path: " + normalized);
                    }
                }
            }
        }
        if (sources.isEmpty()) {
            throw new VerificationException("analyzed CPD scope contains no Java sources");
        }
        return sources;
    }

    private static boolean isGeneratedOrVendor(Path sourceRoot, Path candidate) {
        Path relative = sourceRoot.relativize(candidate);
        for (Path segment : relative) {
            String name = segment.toString();
            if ("generated".equals(name) || "vendor".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static CpdReport readCpdReport(Path root, Path xml)
            throws Exception {
        Document document = parseXml(xml);
        Element report = document.getDocumentElement();
        if (!"pmd-cpd".equals(report.getLocalName())
                || !CPD_NAMESPACE.equals(report.getNamespaceURI())) {
            throw new VerificationException(
                    "unexpected CPD XML root: {" + report.getNamespaceURI()
                            + "}" + report.getLocalName());
        }
        if (report.getAttribute("pmdVersion").isBlank()) {
            throw new VerificationException("CPD XML has no pmdVersion");
        }

        LinkedHashSet<Path> sourceFiles = new LinkedHashSet<>();
        NodeList files = report.getElementsByTagNameNS(CPD_NAMESPACE, "file");
        for (int index = 0; index < files.getLength(); index++) {
            Element file = (Element) files.item(index);
            String rawPath = file.getAttribute("path");
            if (rawPath.isBlank()) {
                throw new VerificationException("CPD XML contains a file without path");
            }

            Path source = Path.of(rawPath);
            if (!source.isAbsolute()) {
                source = root.resolve(source);
            }
            source = source.toAbsolutePath().normalize();
            requireUnderRoot(root, source, "CPD XML source path");
            if (!Files.isRegularFile(source)) {
                throw new VerificationException(
                        "CPD XML references a missing source file: " + source);
            }
            sourceFiles.add(source);
        }
        if (sourceFiles.isEmpty()) {
            throw new VerificationException("CPD XML contains no source files");
        }

        int duplicationCount = report
                .getElementsByTagNameNS(CPD_NAMESPACE, "duplication")
                .getLength();
        return new CpdReport(Set.copyOf(sourceFiles), duplicationCount);
    }

    private static void verifyHtml(Path html)
            throws IOException, VerificationException {
        String content = Files.readString(html, StandardCharsets.UTF_8);
        String normalized = content.toLowerCase(Locale.ROOT);
        if (!normalized.contains("<html") || !normalized.contains("cpd results")) {
            throw new VerificationException(
                    "CPD HTML does not contain the expected report document: " + html);
        }
    }

    private static void requireNonEmptyReport(Path report, String subject)
            throws IOException, VerificationException {
        if (!Files.isRegularFile(report) || Files.size(report) == 0) {
            throw new VerificationException(
                    subject + " is missing or empty: " + report);
        }
    }

    private static LinkedHashMap<String, ScopeEntry> readManifest(Path manifest)
            throws IOException, VerificationException {
        LinkedHashMap<String, ScopeEntry> entries = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int lineNumber = index + 1;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            String[] fields = line.split("\t", -1);
            if (fields.length != 4) {
                throw new VerificationException(
                        "manifest line " + lineNumber
                                + " must contain four tab-separated fields");
            }

            String modulePath = validateModulePath(fields[0], lineNumber);
            String artifactId = fields[1].trim();
            if (!artifactId.equals(fields[1])
                    || !ARTIFACT_ID.matcher(artifactId).matches()) {
                throw new VerificationException(
                        "invalid artifactId at manifest line " + lineNumber + ": " + fields[1]);
            }
            Disposition disposition = Disposition.parse(fields[2], lineNumber);
            String rationale = fields[3].trim();
            if (rationale.isEmpty()) {
                throw new VerificationException(
                        "missing rationale at manifest line " + lineNumber);
            }

            ScopeEntry previous = entries.putIfAbsent(
                    modulePath,
                    new ScopeEntry(modulePath, artifactId, disposition, rationale));
            if (previous != null) {
                throw new VerificationException(
                        "duplicate module path at manifest line " + lineNumber + ": " + modulePath);
            }
        }

        if (entries.isEmpty()) {
            throw new VerificationException("scope manifest contains no entries");
        }
        return entries;
    }

    private static LinkedHashSet<String> readReactorPaths(Path rootPom)
            throws Exception {
        Document document = parseXml(rootPom);
        Element project = document.getDocumentElement();
        Element modules = directChild(project, "modules");
        if (modules == null) {
            throw new VerificationException("root POM has no direct <modules> element");
        }

        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.add(ROOT_PATH);
        for (Element module : directChildren(modules, "module")) {
            String modulePath = validateModulePath(module.getTextContent().trim(), -1);
            if (!paths.add(modulePath)) {
                throw new VerificationException("duplicate root reactor module: " + modulePath);
            }
        }
        return paths;
    }

    private static ProjectModel readProject(Path pom)
            throws Exception {
        Document document = parseXml(pom);
        Element project = document.getDocumentElement();
        String artifactId = requiredDirectText(project, "artifactId", pom);
        String packaging = optionalDirectText(project, "packaging", "jar");
        return new ProjectModel(artifactId, packaging, readsDirectSpotBugsSkip(project, pom));
    }

    private static boolean readsDirectSpotBugsSkip(Element project, Path pom)
            throws VerificationException {
        Element build = directChild(project, "build");
        Element plugins = build == null ? null : directChild(build, "plugins");
        if (plugins == null) {
            return false;
        }

        for (Element plugin : directChildren(plugins, "plugin")) {
            if (!SPOTBUGS_PLUGIN.equals(optionalDirectText(plugin, "artifactId", ""))) {
                continue;
            }
            Element configuration = directChild(plugin, "configuration");
            String skip = configuration == null
                    ? ""
                    : optionalDirectText(configuration, "skip", "");
            if (skip.isEmpty()) {
                return false;
            }
            if (!"true".equals(skip) && !"false".equals(skip)) {
                throw new VerificationException(
                        "direct SpotBugs skip in " + pom + " must be literal true or false");
            }
            return Boolean.parseBoolean(skip);
        }
        return false;
    }

    private static Set<String> readReportDependencies(Control control, Path reportPom)
            throws Exception {
        Document document = parseXml(reportPom);
        Element project = document.getDocumentElement();
        Element dependencies = directChild(project, "dependencies");
        if (dependencies == null) {
            throw new VerificationException(
                    control.displayName() + " report POM has no direct dependencies");
        }

        LinkedHashSet<String> artifacts = new LinkedHashSet<>();
        for (Element dependency : directChildren(dependencies, "dependency")) {
            String groupId = requiredDirectText(dependency, "groupId", reportPom);
            String artifactId = requiredDirectText(dependency, "artifactId", reportPom);
            String type = optionalDirectText(dependency, "type", "jar");
            String scope = optionalDirectText(dependency, "scope", "compile");

            if (!PROJECT_GROUP.equals(groupId)
                    || !"jar".equals(type)
                    || !"compile".equals(scope)) {
                throw new VerificationException(
                        control.displayName()
                                + " ordering dependency must be a compile-scope reactor JAR: "
                                + groupId + ":" + artifactId + ":" + type + ":" + scope);
            }
            if (!artifacts.add(artifactId)) {
                throw new VerificationException(
                        "duplicate " + control.displayName()
                                + " report dependency: " + artifactId);
            }
        }
        return artifacts;
    }

    private static Map<String, SpotBugsAggregateConfiguration> readSpotBugsAggregateConfiguration(
            Path reportPom)
            throws Exception {
        Document document = parseXml(reportPom);
        Element project = document.getDocumentElement();
        Element build = directChild(project, "build");
        Element plugins = build == null ? null : directChild(build, "plugins");
        if (plugins == null) {
            throw new VerificationException("SpotBugs report POM has no direct build plugins");
        }

        Element spotBugsPlugin = null;
        for (Element plugin : directChildren(plugins, "plugin")) {
            if (SPOTBUGS_PLUGIN.equals(optionalDirectText(plugin, "artifactId", ""))) {
                spotBugsPlugin = plugin;
                break;
            }
        }
        if (spotBugsPlugin == null) {
            throw new VerificationException("SpotBugs report POM has no SpotBugs Maven Plugin");
        }

        LinkedHashMap<String, SpotBugsAggregateConfiguration> configurations =
                new LinkedHashMap<>();
        Element executions = directChild(spotBugsPlugin, "executions");
        if (executions != null) {
            for (Element execution : directChildren(executions, "execution")) {
                Element goalsElement = directChild(execution, "goals");
                LinkedHashSet<String> goals = new LinkedHashSet<>();
                if (goalsElement != null) {
                    for (Element goal : directChildren(goalsElement, "goal")) {
                        goals.add(goal.getTextContent().trim());
                    }
                }
                if (!goals.contains(SPOTBUGS_AGGREGATE_GOAL)) {
                    continue;
                }

                String id = requiredDirectText(execution, "id", reportPom);
                Element configuration = directChild(execution, "configuration");
                if (configuration == null) {
                    throw new VerificationException(
                            "SpotBugs aggregate execution has no configuration: " + id);
                }
                SpotBugsAggregateConfiguration previous = configurations.putIfAbsent(
                        id,
                        new SpotBugsAggregateConfiguration(
                                requiredDirectText(execution, "phase", reportPom),
                                Set.copyOf(goals),
                                requiredDirectText(configuration, "skip", reportPom),
                                requiredDirectText(configuration, "effort", reportPom),
                                requiredDirectText(configuration, "threshold", reportPom),
                                requiredDirectText(configuration, "skipEmptyReport", reportPom),
                                requiredDirectText(configuration, "outputDirectory", reportPom),
                                requiredDirectText(
                                        configuration,
                                        "spotbugsXmlOutputFilename",
                                        reportPom)));
                if (previous != null) {
                    throw new VerificationException(
                            "duplicate SpotBugs aggregate execution: " + id);
                }
            }
        }
        compareSets(
                "SpotBugs aggregate execution IDs",
                Set.of(SPOTBUGS_RAW_EXECUTION, SPOTBUGS_FILTERED_EXECUTION),
                configurations.keySet());
        return Map.copyOf(configurations);
    }

    private static SpotBugsModuleConfiguration readSpotBugsModuleConfiguration(Path rootPom)
            throws Exception {
        Document document = parseXml(rootPom);
        Element project = document.getDocumentElement();
        Element build = directChild(project, "build");
        Element plugins = build == null ? null : directChild(build, "plugins");
        if (plugins == null) {
            throw new VerificationException("root POM has no direct build plugins");
        }

        Element spotBugsPlugin = null;
        for (Element plugin : directChildren(plugins, "plugin")) {
            if (SPOTBUGS_PLUGIN.equals(optionalDirectText(plugin, "artifactId", ""))) {
                spotBugsPlugin = plugin;
                break;
            }
        }
        if (spotBugsPlugin == null) {
            throw new VerificationException("root POM has no SpotBugs Maven Plugin");
        }

        Element executions = directChild(spotBugsPlugin, "executions");
        Element moduleExecution = null;
        if (executions != null) {
            for (Element execution : directChildren(executions, "execution")) {
                if (SPOTBUGS_MODULE_EXECUTION.equals(optionalDirectText(execution, "id", ""))) {
                    moduleExecution = execution;
                    break;
                }
            }
        }
        if (moduleExecution == null) {
            throw new VerificationException(
                    "root POM has no " + SPOTBUGS_MODULE_EXECUTION + " execution");
        }

        Element configuration = directChild(moduleExecution, "configuration");
        if (configuration == null) {
            throw new VerificationException("SpotBugs module execution has no configuration");
        }
        Element goalsElement = directChild(moduleExecution, "goals");
        LinkedHashSet<String> goals = new LinkedHashSet<>();
        if (goalsElement != null) {
            for (Element goal : directChildren(goalsElement, "goal")) {
                goals.add(goal.getTextContent().trim());
            }
        }
        return new SpotBugsModuleConfiguration(
                requiredDirectText(moduleExecution, "phase", rootPom),
                Set.copyOf(goals),
                requiredDirectText(configuration, "effort", rootPom),
                requiredDirectText(configuration, "threshold", rootPom),
                requiredDirectText(configuration, "includeTests", rootPom),
                requiredDirectText(configuration, "failOnError", rootPom),
                requiredDirectText(configuration, "skipEmptyReport", rootPom),
                requiredDirectText(configuration, "htmlOutput", rootPom),
                requiredDirectText(configuration, "xmlOutput", rootPom),
                requiredDirectText(configuration, "outputDirectory", rootPom),
                requiredDirectText(configuration, "spotbugsXmlOutputDirectory", rootPom),
                requiredDirectText(configuration, "spotbugsXmlOutputFilename", rootPom));
    }

    private static void validateSpotBugsModuleConfiguration(
            SpotBugsModuleConfiguration configuration)
            throws VerificationException {
        requireValue("SpotBugs module phase", "verify", configuration.phase());
        compareSets("SpotBugs module goals", Set.of(SPOTBUGS_MODULE_GOAL), configuration.goals());
        requireValue("SpotBugs module effort", "Max", configuration.effort());
        requireValue("SpotBugs module threshold", "Low", configuration.threshold());
        requireValue("SpotBugs module includeTests", "false", configuration.includeTests());
        requireValue("SpotBugs module failOnError", "true", configuration.failOnError());
        requireValue(
                "SpotBugs module skipEmptyReport", "false", configuration.skipEmptyReport());
        requireValue("SpotBugs module htmlOutput", "false", configuration.htmlOutput());
        requireValue("SpotBugs module xmlOutput", "false", configuration.xmlOutput());
        requireValue(
                "SpotBugs module outputDirectory",
                "${project.build.directory}/spotbugs-raw",
                configuration.outputDirectory());
        requireValue(
                "SpotBugs module XML outputDirectory",
                "${project.build.directory}/spotbugs-raw",
                configuration.xmlOutputDirectory());
        requireValue(
                "SpotBugs module XML filename",
                "spotbugs-raw.xml",
                configuration.xmlFilename());
    }

    private static void validateSpotBugsAggregateConfiguration(
            Map<String, SpotBugsAggregateConfiguration> configurations)
            throws VerificationException {
        validateSpotBugsAggregateExecution(
                "raw",
                configurations.get(SPOTBUGS_RAW_EXECUTION),
                "${project.build.directory}/spotbugs-raw",
                "spotbugs-raw/spotbugs-raw.xml");
        validateSpotBugsAggregateExecution(
                "filtered",
                configurations.get(SPOTBUGS_FILTERED_EXECUTION),
                "${project.build.directory}/spotbugs",
                "spotbugs/spotbugs.xml");
    }

    private static void validateSpotBugsAggregateExecution(
            String view,
            SpotBugsAggregateConfiguration configuration,
            String outputDirectory,
            String xmlFilename)
            throws VerificationException {
        requireValue("SpotBugs " + view + " aggregate phase", "verify", configuration.phase());
        compareSets(
                "SpotBugs " + view + " aggregate goals",
                Set.of(SPOTBUGS_AGGREGATE_GOAL),
                configuration.goals());
        requireValue("SpotBugs " + view + " aggregate skip", "false", configuration.skip());
        requireValue("SpotBugs " + view + " aggregate effort", "Max", configuration.effort());
        requireValue(
                "SpotBugs " + view + " aggregate threshold", "Low", configuration.threshold());
        requireValue(
                "SpotBugs " + view + " aggregate skipEmptyReport",
                "false",
                configuration.skipEmptyReport());
        requireValue(
                "SpotBugs " + view + " aggregate outputDirectory",
                outputDirectory,
                configuration.outputDirectory());
        requireValue(
                "SpotBugs " + view + " aggregate XML filename",
                xmlFilename,
                configuration.xmlFilename());
    }

    private static CpdConfiguration readCpdConfiguration(Path reportPom)
            throws Exception {
        Document document = parseXml(reportPom);
        Element project = document.getDocumentElement();
        Element build = directChild(project, "build");
        Element plugins = build == null ? null : directChild(build, "plugins");
        if (plugins == null) {
            throw new VerificationException("CPD report POM has no direct build plugins");
        }

        Element pmdPlugin = null;
        for (Element plugin : directChildren(plugins, "plugin")) {
            if (PMD_PLUGIN.equals(optionalDirectText(plugin, "artifactId", ""))) {
                pmdPlugin = plugin;
                break;
            }
        }
        if (pmdPlugin == null) {
            throw new VerificationException("CPD report POM has no Maven PMD Plugin");
        }

        Element executions = directChild(pmdPlugin, "executions");
        Element cpdExecution = null;
        if (executions != null) {
            for (Element execution : directChildren(executions, "execution")) {
                if (CPD_EXECUTION.equals(optionalDirectText(execution, "id", ""))) {
                    cpdExecution = execution;
                    break;
                }
            }
        }
        if (cpdExecution == null) {
            throw new VerificationException(
                    "CPD report POM has no " + CPD_EXECUTION + " execution");
        }

        Element configuration = directChild(cpdExecution, "configuration");
        if (configuration == null) {
            throw new VerificationException("CPD execution has no configuration");
        }

        Element goalsElement = directChild(cpdExecution, "goals");
        LinkedHashSet<String> goals = new LinkedHashSet<>();
        if (goalsElement != null) {
            for (Element goal : directChildren(goalsElement, "goal")) {
                goals.add(goal.getTextContent().trim());
            }
        }

        Element rootsElement = directChild(configuration, "compileSourceRoots");
        LinkedHashSet<String> sourceRoots = new LinkedHashSet<>();
        if (rootsElement != null) {
            for (Element sourceRoot : directChildren(rootsElement, "compileSourceRoot")) {
                String value = sourceRoot.getTextContent().trim();
                if (!sourceRoots.add(value)) {
                    throw new VerificationException(
                            "duplicate configured CPD source root: " + value);
                }
            }
        }

        Element excludesElement = directChild(configuration, "excludes");
        LinkedHashSet<String> excludes = new LinkedHashSet<>();
        if (excludesElement != null) {
            for (Element exclude : directChildren(excludesElement, "exclude")) {
                excludes.add(exclude.getTextContent().trim());
            }
        }

        return new CpdConfiguration(
                requiredDirectText(cpdExecution, "phase", reportPom),
                Set.copyOf(goals),
                requiredDirectText(configuration, "skip", reportPom),
                requiredDirectText(configuration, "language", reportPom),
                requiredDirectText(configuration, "includeTests", reportPom),
                requiredDirectText(configuration, "skipEmptyReport", reportPom),
                requiredDirectText(configuration, "format", reportPom),
                requiredDirectText(configuration, "targetDirectory", reportPom),
                requiredDirectText(configuration, "outputDirectory", reportPom),
                Set.copyOf(sourceRoots),
                Set.copyOf(excludes));
    }

    private static Document parseXml(Path file)
            throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false);

        try (InputStream input = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Element directChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> directChildren(Element parent, String localName) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element
                    && localName.equals(element.getLocalName())) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String requiredDirectText(Element parent, String name, Path source)
            throws VerificationException {
        Element child = directChild(parent, name);
        if (child == null || child.getTextContent().isBlank()) {
            throw new VerificationException("missing direct <" + name + "> in " + source);
        }
        return child.getTextContent().trim();
    }

    private static String optionalDirectText(Element parent, String name, String defaultValue) {
        Element child = directChild(parent, name);
        return child == null || child.getTextContent().isBlank()
                ? defaultValue
                : child.getTextContent().trim();
    }

    private static String validateModulePath(String value, int lineNumber)
            throws VerificationException {
        String location = lineNumber > 0 ? " at manifest line " + lineNumber : "";
        if (!value.equals(value.trim()) || value.isEmpty() || value.contains("\\")) {
            throw new VerificationException("invalid module path" + location + ": " + value);
        }
        if (ROOT_PATH.equals(value)) {
            return value;
        }

        Path path = Path.of(value);
        String normalized = toModulePath(path.normalize());
        if (path.isAbsolute()
                || normalized.equals(ROOT_PATH)
                || normalized.startsWith("../")
                || !normalized.equals(value)) {
            throw new VerificationException(
                    "unsafe or non-normalized module path" + location + ": " + value);
        }
        return value;
    }

    private static String toModulePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static <T extends Comparable<? super T>> void compareSets(
            String subject,
            Set<T> expected,
            Set<T> actual)
            throws VerificationException {
        Set<T> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<T> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new VerificationException(
                    subject + " differ; missing=" + missing + ", unexpected=" + unexpected);
        }
    }

    private static void requireDirectory(Path path, String subject)
            throws VerificationException {
        if (!Files.isDirectory(path)) {
            throw new VerificationException(subject + " does not exist: " + path);
        }
    }

    private static void requireFile(Path path, String subject)
            throws VerificationException {
        if (!Files.isRegularFile(path)) {
            throw new VerificationException(subject + " does not exist: " + path);
        }
    }

    private static void requireUnderRoot(Path root, Path path, String subject)
            throws VerificationException {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new VerificationException(subject + " escapes reactor root: " + path);
        }
    }

    private enum Mode {
        VALIDATE("validate"),
        VERIFY_REPORTS("verify-reports");

        private final String externalName;

        Mode(String externalName) {
            this.externalName = externalName;
        }

        static Mode parse(String value) throws VerificationException {
            return switch (value) {
                case "validate" -> VALIDATE;
                case "verify-reports" -> VERIFY_REPORTS;
                default -> throw new VerificationException("unknown verifier mode: " + value);
            };
        }

        String externalName() {
            return externalName;
        }
    }

    private enum Control {
        SPOTBUGS("spotbugs", "SpotBugs"),
        CPD("cpd", "CPD");

        private final String externalName;
        private final String displayName;

        Control(String externalName, String displayName) {
            this.externalName = externalName;
            this.displayName = displayName;
        }

        static Control parse(String value) throws VerificationException {
            for (Control control : values()) {
                if (control.externalName.equals(value)) {
                    return control;
                }
            }
            throw new VerificationException("unknown build-quality control: " + value);
        }

        String externalName() {
            return externalName;
        }

        String displayName() {
            return displayName;
        }
    }

    private enum Disposition {
        ANALYZED("analyzed"),
        EXCLUDED("excluded"),
        AGGREGATE("aggregate");

        private final String externalName;

        Disposition(String externalName) {
            this.externalName = externalName;
        }

        String externalName() {
            return externalName;
        }

        static Disposition parse(String value, int lineNumber)
                throws VerificationException {
            for (Disposition disposition : values()) {
                if (disposition.externalName.equals(value)) {
                    return disposition;
                }
            }
            throw new VerificationException(
                    "unknown disposition at manifest line " + lineNumber + ": " + value);
        }
    }

    private record ScopeEntry(
            String modulePath,
            String artifactId,
            Disposition disposition,
            String rationale) {
    }

    private record ProjectModel(
            String artifactId,
            String packaging,
            boolean spotBugsSkipped) {
    }

    private record Registry(
            Path root,
            List<ScopeEntry> entries,
            List<Path> analyzedSourceRoots,
            Path reportModuleDirectory) {
    }

    private record CpdConfiguration(
            String phase,
            Set<String> goals,
            String skip,
            String language,
            String includeTests,
            String skipEmptyReport,
            String format,
            String targetDirectory,
            String outputDirectory,
            Set<String> sourceRoots,
            Set<String> excludes) {
    }

    private record SpotBugsAggregateConfiguration(
            String phase,
            Set<String> goals,
            String skip,
            String effort,
            String threshold,
            String skipEmptyReport,
            String outputDirectory,
            String xmlFilename) {
    }

    private record SpotBugsModuleConfiguration(
            String phase,
            Set<String> goals,
            String effort,
            String threshold,
            String includeTests,
            String failOnError,
            String skipEmptyReport,
            String htmlOutput,
            String xmlOutput,
            String outputDirectory,
            String xmlOutputDirectory,
            String xmlFilename) {
    }

    private record CpdReport(
            Set<Path> sourceFiles,
            int duplicationCount) {
    }

    private static final class VerificationException extends Exception {
        VerificationException(String message) {
            super(message);
        }
    }
}
