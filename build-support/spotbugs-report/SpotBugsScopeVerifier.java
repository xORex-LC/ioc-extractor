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

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Verifies that the SpotBugs scope has an explicit disposition for every
 * Maven reactor project and that all expected reports exist.
 *
 * <p>This source-file tool deliberately uses only JDK APIs so Maven can run it
 * with {@code java SpotBugsScopeVerifier.java ...} without adding a build-time
 * library or a custom Maven plugin.</p>
 */
public final class SpotBugsScopeVerifier {

    private static final String ROOT_PATH = ".";
    private static final String SPOTBUGS_PLUGIN = "spotbugs-maven-plugin";
    private static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9_.-]+");

    private SpotBugsScopeVerifier() {
    }

    public static void main(String[] args) {
        try {
            if (args.length != 4) {
                throw new VerificationException(
                        "usage: SpotBugsScopeVerifier <validate|verify-reports> "
                                + "<reactor-root> <scope-manifest> <report-pom>");
            }

            Mode mode = Mode.parse(args[0]);
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            Path manifest = Path.of(args[2]).toAbsolutePath().normalize();
            Path reportPom = Path.of(args[3]).toAbsolutePath().normalize();
            Registry registry = validateScope(root, manifest, reportPom);

            if (mode == Mode.VERIFY_REPORTS) {
                verifyReports(registry);
            }
        } catch (VerificationException e) {
            System.err.println("[spotbugs-scope] ERROR: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[spotbugs-scope] ERROR: unexpected verifier failure: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Registry validateScope(Path root, Path manifest, Path reportPom) throws Exception {
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

            String previousOwner = artifactOwners.putIfAbsent(entry.artifactId(), entry.modulePath());
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
                    if (project.spotBugsSkipped()) {
                        throw new VerificationException(
                                "analyzed module explicitly skips SpotBugs: " + entry.modulePath());
                    }
                    analyzedArtifacts.add(entry.artifactId());
                }
                case EXCLUDED -> validateExcludedProject(entry, project);
                case AGGREGATE -> {
                    if (!entry.modulePath().equals(reportModulePath)) {
                        throw new VerificationException(
                                "aggregate disposition belongs to " + reportModulePath
                                        + ", not " + entry.modulePath());
                    }
                    if (!"pom".equals(project.packaging())) {
                        throw new VerificationException(
                                "SpotBugs aggregate must use pom packaging: " + entry.modulePath());
                    }
                    if (!project.spotBugsSkipped()) {
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

        Set<String> dependencyArtifacts = readReportDependencies(reportPom);
        compareSets(
                "analyzed scope versus SpotBugs report-module dependencies",
                analyzedArtifacts,
                dependencyArtifacts);

        Registry registry = new Registry(root, List.copyOf(entries.values()));
        System.out.printf(
                Locale.ROOT,
                "[spotbugs-scope] validated %d reactor projects: %d analyzed, %d excluded, %d aggregate%n",
                entries.size(),
                counts.getOrDefault(Disposition.ANALYZED, 0),
                counts.getOrDefault(Disposition.EXCLUDED, 0),
                counts.getOrDefault(Disposition.AGGREGATE, 0));
        return registry;
    }

    private static void validateExcludedProject(ScopeEntry entry, ProjectModel project)
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

    private static void verifyReports(Registry registry) throws IOException, VerificationException {
        List<String> problems = new ArrayList<>();
        int expectedPairs = 0;

        for (ScopeEntry entry : registry.entries()) {
            Path moduleDirectory = ROOT_PATH.equals(entry.modulePath())
                    ? registry.root()
                    : registry.root().resolve(entry.modulePath());
            Path reportDirectory = moduleDirectory.resolve("target/spotbugs");
            Path xml = reportDirectory.resolve("spotbugs.xml");
            Path html = reportDirectory.resolve("spotbugs.html");

            if (entry.disposition() == Disposition.EXCLUDED) {
                rejectUnexpectedReport(xml, entry, problems);
                rejectUnexpectedReport(html, entry, problems);
            } else {
                expectedPairs++;
                requireNonEmptyReport(xml, entry, problems);
                requireNonEmptyReport(html, entry, problems);
            }
        }

        if (!problems.isEmpty()) {
            throw new VerificationException(
                    "SpotBugs report integrity failed:\n - " + String.join("\n - ", problems));
        }

        System.out.printf(
                Locale.ROOT,
                "[spotbugs-scope] verified %d XML/HTML report pairs%n",
                expectedPairs);
    }

    private static void requireNonEmptyReport(
            Path report,
            ScopeEntry entry,
            List<String> problems) throws IOException {
        if (!Files.isRegularFile(report) || Files.size(report) == 0) {
            problems.add(
                    "missing or empty " + entry.disposition().externalName()
                            + " report for " + entry.modulePath() + ": " + report);
        }
    }

    private static void rejectUnexpectedReport(
            Path report,
            ScopeEntry entry,
            List<String> problems) {
        if (Files.exists(report)) {
            problems.add(
                    "excluded module produced a SpotBugs report (" + entry.rationale()
                            + "): " + report);
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
                        "manifest line " + lineNumber + " must contain four tab-separated fields");
            }

            String modulePath = validateModulePath(fields[0], lineNumber);
            String artifactId = fields[1].trim();
            if (!artifactId.equals(fields[1]) || !ARTIFACT_ID.matcher(artifactId).matches()) {
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

    private static ProjectModel readProject(Path pom) throws Exception {
        Document document = parseXml(pom);
        Element project = document.getDocumentElement();
        String artifactId = requiredDirectText(project, "artifactId", pom);
        String packaging = optionalDirectText(project, "packaging", "jar");
        boolean spotBugsSkipped = readsDirectSpotBugsSkip(project, pom);
        return new ProjectModel(artifactId, packaging, spotBugsSkipped);
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

    private static Set<String> readReportDependencies(Path reportPom)
            throws Exception {
        Document document = parseXml(reportPom);
        Element project = document.getDocumentElement();
        Element dependencies = directChild(project, "dependencies");
        if (dependencies == null) {
            throw new VerificationException("SpotBugs report POM has no direct dependencies");
        }

        LinkedHashSet<String> artifacts = new LinkedHashSet<>();
        for (Element dependency : directChildren(dependencies, "dependency")) {
            String groupId = requiredDirectText(dependency, "groupId", reportPom);
            String artifactId = requiredDirectText(dependency, "artifactId", reportPom);
            String type = optionalDirectText(dependency, "type", "jar");
            String scope = optionalDirectText(dependency, "scope", "compile");

            if (!"com.iocextractor".equals(groupId)
                    || !"jar".equals(type)
                    || !"compile".equals(scope)) {
                throw new VerificationException(
                        "SpotBugs ordering dependency must be a compile-scope reactor JAR: "
                                + groupId + ":" + artifactId + ":" + type + ":" + scope);
            }
            if (!artifacts.add(artifactId)) {
                throw new VerificationException(
                        "duplicate SpotBugs report dependency: " + artifactId);
            }
        }
        return artifacts;
    }

    private static Document parseXml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        try (InputStream input = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Element directChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
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
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
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
            throw new VerificationException("unsafe or non-normalized module path" + location + ": " + value);
        }
        return value;
    }

    private static String toModulePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void compareSets(String subject, Set<String> expected, Set<String> actual)
            throws VerificationException {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new VerificationException(
                    subject + " differ; missing=" + missing + ", unexpected=" + unexpected);
        }
    }

    private static void requireDirectory(Path path, String subject) throws VerificationException {
        if (!Files.isDirectory(path)) {
            throw new VerificationException(subject + " does not exist: " + path);
        }
    }

    private static void requireFile(Path path, String subject) throws VerificationException {
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
        VALIDATE,
        VERIFY_REPORTS;

        static Mode parse(String value) throws VerificationException {
            return switch (value) {
                case "validate" -> VALIDATE;
                case "verify-reports" -> VERIFY_REPORTS;
                default -> throw new VerificationException("unknown verifier mode: " + value);
            };
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

        static Disposition parse(String value, int lineNumber) throws VerificationException {
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
            List<ScopeEntry> entries) {
    }

    private static final class VerificationException extends Exception {
        VerificationException(String message) {
            super(message);
        }
    }
}
