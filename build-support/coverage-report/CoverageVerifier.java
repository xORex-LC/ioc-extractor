import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Verifies the fail-closed JaCoCo universe, report integrity and coverage ratchets.
 *
 * <p>This class deliberately uses only JDK APIs. Root {@code validate} compiles it
 * before child modules, and the final coverage-report project reuses it after the
 * aggregate report has been generated.</p>
 */
public final class CoverageVerifier {

    private static final String ROOT_PATH = ".";
    private static final String PROJECT_GROUP = "com.iocextractor";
    private static final String AGGREGATE_NAME = "reactor";
    private static final String AGGREGATE_TITLE = "ioc-extractor reactor coverage";
    private static final String JACOCO_GROUP = "org.jacoco";
    private static final String JACOCO_PLUGIN = "jacoco-maven-plugin";
    private static final String MAVEN_PLUGIN_GROUP = "org.apache.maven.plugins";
    private static final String ANTRUN_PLUGIN = "maven-antrun-plugin";
    private static final Pattern MODULE_PATH = Pattern.compile(
            "(?:\\.|[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*)");
    private static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Set<CounterType> REQUIRED_COUNTERS;

    static {
        EnumSet<CounterType> required = EnumSet.allOf(CounterType.class);
        required.remove(CounterType.BRANCH);
        REQUIRED_COUNTERS = Set.copyOf(required);
    }

    private CoverageVerifier() {
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err));
    }

    static int execute(String[] args, PrintStream standardOutput, PrintStream errorOutput) {
        try {
            if (args.length != 5) {
                throw new VerificationException(
                        "usage: CoverageVerifier <validate|verify-reports> <reactor-root> "
                                + "<scope-manifest> <ratchet-snapshot> <report-pom>");
            }
            Mode mode = Mode.parse(args[0]);
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            Path scopeManifest = Path.of(args[2]).toAbsolutePath().normalize();
            Path ratchetSnapshot = Path.of(args[3]).toAbsolutePath().normalize();
            Path reportPom = Path.of(args[4]).toAbsolutePath().normalize();

            Policy policy = validatePolicy(root, scopeManifest, ratchetSnapshot, reportPom);
            if (mode == Mode.VERIFY_REPORTS) {
                CoverageReport report = verifyReports(policy);
                Counter lines = report.aggregateCounters().get(CounterType.LINE);
                Counter branches = report.aggregateCounters().get(CounterType.BRANCH);
                standardOutput.printf(
                        Locale.ROOT,
                        "[coverage] verify-reports completed: modules=%d, local=%d, "
                                + "lines=%d/%d (%.2f%%), branches=%d/%d (%.2f%%)%n",
                        policy.coveredEntries().size(),
                        policy.requiredLocalReports(),
                        lines.covered(),
                        lines.total(),
                        lines.percentage(),
                        branches.covered(),
                        branches.total(),
                        branches.percentage());
            } else {
                standardOutput.printf(
                        Locale.ROOT,
                        "[coverage] validate completed: reactor=%d, production=%d, "
                                + "local-required=%d, downstream-only=%d%n",
                        policy.entries().size(),
                        policy.coveredEntries().size(),
                        policy.requiredLocalReports(),
                        policy.aggregateOnlyReports());
            }
            return 0;
        } catch (VerificationException e) {
            errorOutput.println("[coverage] ERROR: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            errorOutput.println(
                    "[coverage] ERROR: unexpected verifier failure: " + e.getMessage());
            e.printStackTrace(errorOutput);
            return 1;
        }
    }

    private static Policy validatePolicy(
            Path root,
            Path scopeManifest,
            Path ratchetSnapshot,
            Path reportPom)
            throws Exception {
        requireDirectory(root, "reactor root");
        Path rootPom = root.resolve("pom.xml");
        requireFile(rootPom, "root POM");
        requireFile(scopeManifest, "coverage scope manifest");
        requireFile(ratchetSnapshot, "coverage ratchet snapshot");
        requireFile(reportPom, "coverage report POM");
        requireUnderRoot(root, scopeManifest, "coverage scope manifest");
        requireUnderRoot(root, ratchetSnapshot, "coverage ratchet snapshot");
        requireUnderRoot(root, reportPom, "coverage report POM");

        LinkedHashMap<String, ScopeEntry> entries = readScope(scopeManifest);
        LinkedHashSet<String> reactorPaths = readReactorPaths(rootPom);
        compareSets(
                "coverage scope paths versus root Maven reactor",
                reactorPaths,
                entries.keySet());

        Map<String, String> artifactOwners = new HashMap<>();
        List<ScopeEntry> coveredEntries = new ArrayList<>();
        ScopeEntry aggregateEntry = null;
        for (ScopeEntry entry : entries.values()) {
            Path module = ROOT_PATH.equals(entry.modulePath())
                    ? root
                    : root.resolve(entry.modulePath()).normalize();
            requireUnderRoot(root, module, "module " + entry.modulePath());
            Path modulePom = module.resolve("pom.xml");
            requireFile(modulePom, "POM for " + entry.modulePath());
            ProjectModel project = readProject(modulePom);
            if (!entry.artifactId().equals(project.artifactId())) {
                throw new VerificationException(
                        "coverage manifest artifactId mismatch for " + entry.modulePath()
                                + ": expected " + project.artifactId() + ", found "
                                + entry.artifactId());
            }
            String previousOwner = artifactOwners.putIfAbsent(
                    entry.artifactId(), entry.modulePath());
            if (previousOwner != null) {
                throw new VerificationException(
                        "duplicate reactor artifactId " + entry.artifactId() + " in "
                                + previousOwner + " and " + entry.modulePath());
            }
            validateDisposition(entry, project);
            rejectModuleCoverageFilters(modulePom, entry);
            if (entry.disposition() == Disposition.COVERED) {
                coveredEntries.add(entry);
            } else if (entry.disposition() == Disposition.AGGREGATE) {
                if (aggregateEntry != null) {
                    throw new VerificationException(
                            "coverage scope must contain exactly one aggregate disposition");
                }
                aggregateEntry = entry;
            }
        }
        if (aggregateEntry == null) {
            throw new VerificationException(
                    "coverage scope must contain exactly one aggregate disposition");
        }
        String reportModulePath = toModulePath(root.relativize(reportPom.getParent()));
        if (!aggregateEntry.modulePath().equals(reportModulePath)) {
            throw new VerificationException(
                    "coverage report POM does not belong to aggregate module; expected="
                            + aggregateEntry.modulePath() + ", actual=" + reportModulePath);
        }
        if (coveredEntries.isEmpty()) {
            throw new VerificationException("coverage scope contains no production modules");
        }

        LinkedHashSet<String> coveredArtifacts = new LinkedHashSet<>();
        for (ScopeEntry entry : coveredEntries) {
            coveredArtifacts.add(entry.artifactId());
        }
        compareSets(
                "covered scope versus coverage report-module dependencies",
                coveredArtifacts,
                readReportDependencies(reportPom));

        LinkedHashMap<String, Ratchet> ratchets = readRatchets(ratchetSnapshot);
        LinkedHashSet<String> expectedRatchets = new LinkedHashSet<>();
        expectedRatchets.add(AGGREGATE_NAME);
        expectedRatchets.addAll(coveredArtifacts);
        compareSets(
                "coverage ratchet scopes versus aggregate plus production modules",
                expectedRatchets,
                ratchets.keySet());
        validateBuildConfiguration(rootPom, reportPom);

        return new Policy(
                root,
                reportPom.getParent(),
                List.copyOf(entries.values()),
                List.copyOf(coveredEntries),
                Map.copyOf(ratchets));
    }

    private static void validateDisposition(ScopeEntry entry, ProjectModel project)
            throws VerificationException {
        switch (entry.disposition()) {
            case COVERED -> {
                if (!"jar".equals(project.packaging())) {
                    throw new VerificationException(
                            "covered module must use jar packaging: " + entry.modulePath());
                }
                if (entry.localReport() == LocalReport.NONE) {
                    throw new VerificationException(
                            "covered module must declare required or aggregate-only local report: "
                                    + entry.modulePath());
                }
            }
            case EXCLUDED -> {
                if (entry.localReport() != LocalReport.NONE) {
                    throw new VerificationException(
                            "excluded module must use local-report=none: " + entry.modulePath());
                }
            }
            case AGGREGATE -> {
                if (!"pom".equals(project.packaging())) {
                    throw new VerificationException(
                            "coverage aggregate module must use pom packaging: "
                                    + entry.modulePath());
                }
                if (entry.localReport() != LocalReport.NONE) {
                    throw new VerificationException(
                            "coverage aggregate module must use local-report=none: "
                                    + entry.modulePath());
                }
            }
        }
    }

    private static CoverageReport verifyReports(Policy policy) throws Exception {
        Path aggregateDirectory = policy.reportModule().resolve(
                "target/site/jacoco-aggregate");
        Path aggregateXml = aggregateDirectory.resolve("jacoco.xml");
        Path aggregateHtml = aggregateDirectory.resolve("index.html");
        requireNonEmptyFile(aggregateXml, "aggregate JaCoCo XML");
        verifyHtml(aggregateHtml, "aggregate JaCoCo HTML");

        ReportDocument aggregate = readReport(aggregateXml, AGGREGATE_TITLE, true);
        LinkedHashSet<String> expectedGroups = new LinkedHashSet<>();
        for (ScopeEntry entry : policy.coveredEntries()) {
            expectedGroups.add(entry.artifactId());
        }
        compareSets(
                "aggregate JaCoCo groups versus covered production modules",
                expectedGroups,
                aggregate.groups().keySet());
        verifyAggregateSums(aggregateXml, aggregate);

        for (ScopeEntry entry : policy.entries()) {
            Path module = ROOT_PATH.equals(entry.modulePath())
                    ? policy.root()
                    : policy.root().resolve(entry.modulePath());
            Path executionData = module.resolve("target/jacoco.exec");
            Path localDirectory = module.resolve("target/site/jacoco");
            Path localXml = localDirectory.resolve("jacoco.xml");
            Path localHtml = localDirectory.resolve("index.html");
            if (entry.localReport() == LocalReport.REQUIRED) {
                requireNonEmptyFile(executionData, "JaCoCo execution data for " + entry.artifactId());
                requireNonEmptyFile(localXml, "module JaCoCo XML for " + entry.artifactId());
                verifyHtml(localHtml, "module JaCoCo HTML for " + entry.artifactId());
                readReport(localXml, entry.artifactId(), false);
            } else {
                rejectUnexpectedFile(executionData, entry, "JaCoCo execution data");
                rejectUnexpectedFile(localXml, entry, "module JaCoCo XML");
                rejectUnexpectedFile(localHtml, entry, "module JaCoCo HTML");
            }
        }

        applyRatchet(
                AGGREGATE_NAME,
                aggregate.counters(),
                policy.ratchets().get(AGGREGATE_NAME));
        for (Map.Entry<String, Map<CounterType, Counter>> group : aggregate.groups().entrySet()) {
            applyRatchet(
                    group.getKey(),
                    group.getValue(),
                    policy.ratchets().get(group.getKey()));
        }
        return new CoverageReport(aggregate.counters());
    }

    private static void rejectUnexpectedFile(Path path, ScopeEntry entry, String subject)
            throws VerificationException {
        if (Files.exists(path)) {
            throw new VerificationException(
                    subject + " is unexpected for " + entry.artifactId() + " ("
                            + entry.localReport().externalName() + ", " + entry.rationale()
                            + "): " + path);
        }
    }

    private static ReportDocument readReport(Path xml, String expectedName, boolean aggregate)
            throws Exception {
        Document document = parseXml(xml);
        DocumentType documentType = document.getDoctype();
        if (documentType == null
                || !"report".equals(documentType.getName())
                || !"-//JACOCO//DTD Report 1.1//EN".equals(documentType.getPublicId())
                || !"report.dtd".equals(documentType.getSystemId())) {
            throw new VerificationException(
                    "JaCoCo XML has no exact Report 1.1 document type: " + xml);
        }
        Element report = document.getDocumentElement();
        if (!"report".equals(localName(report))) {
            throw new VerificationException(
                    "unexpected JaCoCo XML root in " + xml + ": " + localName(report));
        }
        String name = report.getAttribute("name").trim();
        if (!expectedName.equals(name)) {
            throw new VerificationException(
                    "unexpected JaCoCo report name in " + xml + "; expected="
                            + expectedName + ", actual=" + name);
        }
        Map<CounterType, Counter> counters = readCounters(report, xml + " report");

        LinkedHashMap<String, Map<CounterType, Counter>> groups = new LinkedHashMap<>();
        for (Element group : directChildren(report, "group")) {
            if (!aggregate) {
                throw new VerificationException("module JaCoCo XML contains a group: " + xml);
            }
            String groupName = group.getAttribute("name").trim();
            if (groupName.isEmpty()) {
                throw new VerificationException("aggregate JaCoCo group has no name: " + xml);
            }
            if (directChildren(group, "package").isEmpty()) {
                throw new VerificationException(
                        "aggregate JaCoCo group contains no packages: " + groupName);
            }
            Map<CounterType, Counter> previous = groups.putIfAbsent(
                    groupName, readCounters(group, "JaCoCo group " + groupName));
            if (previous != null) {
                throw new VerificationException(
                        "duplicate aggregate JaCoCo group: " + groupName);
            }
        }
        if (aggregate && groups.isEmpty()) {
            throw new VerificationException("aggregate JaCoCo XML contains no groups: " + xml);
        }
        if (!aggregate && directChildren(report, "package").isEmpty()) {
            throw new VerificationException("module JaCoCo XML contains no packages: " + xml);
        }
        return new ReportDocument(counters, Map.copyOf(groups));
    }

    private static Map<CounterType, Counter> readCounters(Element owner, String subject)
            throws VerificationException {
        EnumMap<CounterType, Counter> counters = new EnumMap<>(CounterType.class);
        for (Element element : directChildren(owner, "counter")) {
            CounterType type = CounterType.parse(element.getAttribute("type"), subject);
            long missed = parseNonNegativeLong(
                    subject + " " + type + " missed", element.getAttribute("missed"));
            long covered = parseNonNegativeLong(
                    subject + " " + type + " covered", element.getAttribute("covered"));
            if (counters.putIfAbsent(type, new Counter(covered, missed)) != null) {
                throw new VerificationException(
                        subject + " contains duplicate " + type + " counter");
            }
        }
        LinkedHashSet<CounterType> actualRequired = new LinkedHashSet<>(counters.keySet());
        actualRequired.remove(CounterType.BRANCH);
        compareSets(subject + " counter types", REQUIRED_COUNTERS, actualRequired);
        counters.putIfAbsent(CounterType.BRANCH, new Counter(0, 0));
        return Map.copyOf(counters);
    }

    private static void verifyAggregateSums(Path xml, ReportDocument report)
            throws VerificationException {
        for (CounterType type : CounterType.values()) {
            long covered = 0;
            long missed = 0;
            try {
                for (Map<CounterType, Counter> group : report.groups().values()) {
                    Counter counter = group.get(type);
                    covered = Math.addExact(covered, counter.covered());
                    missed = Math.addExact(missed, counter.missed());
                }
            } catch (ArithmeticException e) {
                throw new VerificationException(
                        "aggregate JaCoCo counter sum is outside the supported range: " + type);
            }
            Counter aggregate = report.counters().get(type);
            if (covered != aggregate.covered() || missed != aggregate.missed()) {
                throw new VerificationException(
                        "aggregate JaCoCo " + type + " counter differs from group sum in " + xml
                                + "; report=" + aggregate.display() + ", groups="
                                + new Counter(covered, missed).display());
            }
        }
    }

    private static void applyRatchet(
            String scope,
            Map<CounterType, Counter> actualCounters,
            Ratchet baseline)
            throws VerificationException {
        Counter actualLines = actualCounters.get(CounterType.LINE);
        Counter actualBranches = actualCounters.get(CounterType.BRANCH);
        compareRatio(scope, CounterType.LINE, actualLines, baseline.lines());
        compareRatio(scope, CounterType.BRANCH, actualBranches, baseline.branches());
        if (actualBranches.missed() > baseline.branches().missed()) {
            throw new VerificationException(
                    "coverage branch missed-count regression for " + scope + "; baseline="
                            + baseline.branches().missed() + ", actual="
                            + actualBranches.missed());
        }
        Counter instructions = actualCounters.get(CounterType.INSTRUCTION);
        if (baseline.absoluteInstructions() && instructions.missed() > baseline.instructionMissed()) {
            throw new VerificationException(
                    "coverage instruction missed-count regression for " + scope + "; baseline="
                            + baseline.instructionMissed() + ", actual=" + instructions.missed());
        }
    }

    private static void compareRatio(
            String scope,
            CounterType type,
            Counter actual,
            Counter baseline)
            throws VerificationException {
        if (baseline.total() == 0) {
            if (actual.total() != 0) {
                throw new VerificationException(
                        "coverage " + type.externalName() + " counter appeared for " + scope
                                + " after a zero-denominator baseline; review and update required");
            }
            return;
        }
        if (actual.total() == 0) {
            throw new VerificationException(
                    "coverage " + type.externalName() + " counter disappeared for " + scope);
        }
        BigInteger actualScaled = BigInteger.valueOf(actual.covered())
                .multiply(BigInteger.valueOf(baseline.total()));
        BigInteger baselineScaled = BigInteger.valueOf(baseline.covered())
                .multiply(BigInteger.valueOf(actual.total()));
        if (actualScaled.compareTo(baselineScaled) < 0) {
            throw new VerificationException(
                    "coverage " + type.externalName() + " ratio regression for " + scope
                            + "; baseline=" + baseline.display() + " ("
                            + formatPercentage(baseline) + "), actual=" + actual.display()
                            + " (" + formatPercentage(actual) + ")");
        }
    }

    private static LinkedHashMap<String, ScopeEntry> readScope(Path manifest)
            throws IOException, VerificationException {
        LinkedHashMap<String, ScopeEntry> entries = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int lineNumber = index + 1;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 5) {
                throw new VerificationException(
                        "coverage scope line " + lineNumber
                                + " must contain five tab-separated fields");
            }
            String modulePath = validateModulePath(fields[0], lineNumber);
            String artifactId = fields[1].trim();
            if (!artifactId.equals(fields[1]) || !ARTIFACT_ID.matcher(artifactId).matches()) {
                throw new VerificationException(
                        "invalid coverage artifactId at line " + lineNumber + ": " + fields[1]);
            }
            Disposition disposition = Disposition.parse(fields[2], lineNumber);
            LocalReport localReport = LocalReport.parse(fields[3], lineNumber);
            String rationale = fields[4].trim();
            if (rationale.isEmpty()) {
                throw new VerificationException(
                        "coverage scope line " + lineNumber + " has no rationale");
            }
            ScopeEntry entry = new ScopeEntry(
                    modulePath, artifactId, disposition, localReport, rationale);
            if (entries.putIfAbsent(modulePath, entry) != null) {
                throw new VerificationException(
                        "duplicate coverage module path at line " + lineNumber + ": "
                                + modulePath);
            }
        }
        if (entries.isEmpty()) {
            throw new VerificationException("coverage scope manifest contains no entries");
        }
        return entries;
    }

    private static LinkedHashMap<String, Ratchet> readRatchets(Path snapshot)
            throws IOException, VerificationException {
        LinkedHashMap<String, Ratchet> ratchets = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(snapshot, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int lineNumber = index + 1;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 8) {
                throw new VerificationException(
                        "coverage ratchet line " + lineNumber
                                + " must contain eight tab-separated fields");
            }
            String scope = fields[0].trim();
            if (!scope.equals(fields[0]) || (!AGGREGATE_NAME.equals(scope)
                    && !ARTIFACT_ID.matcher(scope).matches())) {
                throw new VerificationException(
                        "invalid coverage ratchet scope at line " + lineNumber + ": "
                                + fields[0]);
            }
            Counter linesCounter = new Counter(
                    parseNonNegativeLong("line covered at ratchet line " + lineNumber, fields[1]),
                    parseNonNegativeLong("line missed at ratchet line " + lineNumber, fields[2]));
            if (linesCounter.total() == 0) {
                throw new VerificationException(
                        "coverage line baseline must have a positive denominator: " + scope);
            }
            Counter branchesCounter = new Counter(
                    parseNonNegativeLong("branch covered at ratchet line " + lineNumber, fields[3]),
                    parseNonNegativeLong("branch missed at ratchet line " + lineNumber, fields[4]));
            long instructionMissed = parseNonNegativeLong(
                    "instruction missed at ratchet line " + lineNumber, fields[5]);
            boolean absoluteInstructions = parseInstructionPolicy(fields[6], lineNumber);
            String rationale = fields[7].trim();
            if (rationale.isEmpty()) {
                throw new VerificationException(
                        "coverage ratchet line " + lineNumber + " has no rationale");
            }
            Ratchet ratchet = new Ratchet(
                    linesCounter,
                    branchesCounter,
                    instructionMissed,
                    absoluteInstructions,
                    rationale);
            if (ratchets.putIfAbsent(scope, ratchet) != null) {
                throw new VerificationException(
                        "duplicate coverage ratchet scope at line " + lineNumber + ": " + scope);
            }
        }
        if (ratchets.isEmpty()) {
            throw new VerificationException("coverage ratchet snapshot contains no entries");
        }
        return ratchets;
    }

    private static boolean parseInstructionPolicy(String raw, int lineNumber)
            throws VerificationException {
        return switch (raw) {
            case "enforced" -> true;
            case "observed" -> false;
            default -> throw new VerificationException(
                    "invalid absolute-instructions policy at ratchet line " + lineNumber
                            + ": " + raw);
        };
    }

    private static void validateBuildConfiguration(Path rootPom, Path reportPom)
            throws Exception {
        Document rootDocument = parseXml(rootPom);
        Element rootProject = rootDocument.getDocumentElement();
        Element jacoco = requiredBuildPlugin(
                rootProject, JACOCO_GROUP, JACOCO_PLUGIN, rootPom);
        rejectCoverageFilters(jacoco, rootPom);
        rejectCoverageDisabling(jacoco, rootPom);
        Element prepare = requiredExecution(jacoco, "prepare-coverage-agent", rootPom);
        requireGoals(prepare, Set.of("prepare-agent"), rootPom);
        Element prepareConfiguration = directChild(prepare, "configuration");
        requireValue(
                "JaCoCo append",
                "true",
                requiredDirectText(prepareConfiguration, "append", rootPom));
        Element moduleReport = requiredExecution(
                jacoco, "create-module-coverage-report", rootPom);
        requireValue(
                "module JaCoCo report phase",
                "verify",
                requiredDirectText(moduleReport, "phase", rootPom));
        requireGoals(moduleReport, Set.of("report"), rootPom);
        requireFormats(moduleReport, rootPom);

        Element antrun = requiredBuildPlugin(
                rootProject, MAVEN_PLUGIN_GROUP, ANTRUN_PLUGIN, rootPom);
        Element cleanup = requiredExecution(antrun, "clean-stale-test-output", rootPom);
        requireValue(
                "coverage cleanup phase",
                "initialize",
                requiredDirectText(cleanup, "phase", rootPom));
        Element cleanupConfiguration = directChild(cleanup, "configuration");
        Element cleanupTarget = directChild(cleanupConfiguration, "target");
        if (cleanupTarget == null || !"skipTests".equals(cleanupTarget.getAttribute("unless"))) {
            throw new VerificationException(
                    "coverage cleanup must use target unless=skipTests in " + rootPom);
        }
        requireDelete(
                cleanupTarget,
                "file",
                "${project.build.directory}/jacoco.exec",
                rootPom);
        requireDelete(
                cleanupTarget,
                "dir",
                "${project.reporting.outputDirectory}/jacoco",
                rootPom);

        Document reportDocument = parseXml(reportPom);
        Element reportProject = reportDocument.getDocumentElement();
        Element reportJacoco = requiredBuildPlugin(
                reportProject, JACOCO_GROUP, JACOCO_PLUGIN, reportPom);
        rejectCoverageFilters(reportJacoco, reportPom);
        rejectCoverageDisabling(reportJacoco, reportPom);
        Element aggregate = requiredExecution(
                reportJacoco, "create-reactor-coverage-report", reportPom);
        requireValue(
                "aggregate JaCoCo report phase",
                "verify",
                requiredDirectText(aggregate, "phase", reportPom));
        requireGoals(aggregate, Set.of("report-aggregate"), reportPom);
        requireFormats(aggregate, reportPom);
        Element aggregateConfiguration = directChild(aggregate, "configuration");
        requireValue(
                "aggregate JaCoCo title",
                AGGREGATE_TITLE,
                requiredDirectText(aggregateConfiguration, "title", reportPom));

        Element reportAntrun = requiredBuildPlugin(
                reportProject, MAVEN_PLUGIN_GROUP, ANTRUN_PLUGIN, reportPom);
        Element aggregateCleanup = requiredExecution(
                reportAntrun, "clean-stale-aggregate-coverage-output", reportPom);
        requireValue(
                "aggregate coverage cleanup phase",
                "initialize",
                requiredDirectText(aggregateCleanup, "phase", reportPom));
        Element aggregateCleanupConfiguration = directChild(aggregateCleanup, "configuration");
        Element aggregateCleanupTarget = directChild(aggregateCleanupConfiguration, "target");
        if (aggregateCleanupTarget == null
                || !"skipTests".equals(aggregateCleanupTarget.getAttribute("unless"))) {
            throw new VerificationException(
                    "aggregate coverage cleanup must use target unless=skipTests in " + reportPom);
        }
        requireDelete(
                aggregateCleanupTarget,
                "dir",
                "${project.reporting.outputDirectory}/jacoco-aggregate",
                reportPom);

        Element gate = requiredExecution(reportAntrun, "verify-coverage-policy", reportPom);
        requireValue(
                "coverage policy gate phase",
                "verify",
                requiredDirectText(gate, "phase", reportPom));
        Element gateTarget = directChild(directChild(gate, "configuration"), "target");
        List<Element> javaTasks = directChildren(gateTarget, "java");
        if (javaTasks.size() != 1) {
            throw new VerificationException(
                    "coverage policy gate must contain exactly one Java task in " + reportPom);
        }
        Element javaTask = javaTasks.getFirst();
        requireValue(
                "coverage policy gate class",
                "CoverageVerifier",
                javaTask.getAttribute("classname"));
        requireValue("coverage policy gate fork", "true", javaTask.getAttribute("fork"));
        requireValue(
                "coverage policy gate failure propagation",
                "true",
                javaTask.getAttribute("failonerror"));
        List<String> expectedArguments = List.of(
                "verify-reports",
                "${maven.multiModuleProjectDirectory}",
                "${maven.multiModuleProjectDirectory}/build-support/coverage-report/coverage-scope.tsv",
                "${maven.multiModuleProjectDirectory}/build-support/coverage-report/coverage-ratchets.tsv",
                "${maven.multiModuleProjectDirectory}/build-support/coverage-report/pom.xml");
        List<String> actualArguments = directChildren(javaTask, "arg").stream()
                .map(argument -> argument.getAttribute("value"))
                .toList();
        if (!expectedArguments.equals(actualArguments)) {
            throw new VerificationException(
                    "coverage policy gate arguments differ in " + reportPom + "; expected="
                            + expectedArguments + ", actual=" + actualArguments);
        }
    }

    private static void rejectModuleCoverageFilters(Path pom, ScopeEntry entry)
            throws Exception {
        Document document = parseXml(pom);
        Element project = document.getDocumentElement();
        NodeList plugins = project.getElementsByTagNameNS("*", "plugin");
        for (int index = 0; index < plugins.getLength(); index++) {
            Element plugin = (Element) plugins.item(index);
            if (JACOCO_PLUGIN.equals(optionalDirectText(plugin, "artifactId", ""))) {
                rejectCoverageFilters(plugin, pom);
                if (entry.disposition() == Disposition.COVERED
                        && containsElement(plugin, "skip")) {
                    throw new VerificationException(
                            "covered module must not override JaCoCo skip: "
                                    + entry.modulePath());
                }
            }
        }
    }

    private static void rejectCoverageFilters(Element plugin, Path pom)
            throws VerificationException {
        if (containsElement(plugin, "includes") || containsElement(plugin, "excludes")) {
            throw new VerificationException(
                    "JaCoCo includes/excludes are not accepted in " + pom);
        }
    }

    private static void rejectCoverageDisabling(Element plugin, Path pom)
            throws VerificationException {
        if (containsElement(plugin, "skip")) {
            throw new VerificationException("JaCoCo skip overrides are not accepted in " + pom);
        }
    }

    private static boolean containsElement(Element owner, String name) {
        NodeList descendants = owner.getElementsByTagNameNS("*", name);
        return descendants.getLength() != 0;
    }

    private static Element requiredBuildPlugin(
            Element project,
            String groupId,
            String artifactId,
            Path pom)
            throws VerificationException {
        Element build = directChild(project, "build");
        Element plugins = build == null ? null : directChild(build, "plugins");
        Element match = null;
        for (Element plugin : directChildren(plugins, "plugin")) {
            if (!artifactId.equals(optionalDirectText(plugin, "artifactId", ""))) {
                continue;
            }
            requireValue(
                    artifactId + " groupId",
                    groupId,
                    optionalDirectText(plugin, "groupId", MAVEN_PLUGIN_GROUP));
            if (match != null) {
                throw new VerificationException(
                        "duplicate " + artifactId + " build plugin in " + pom);
            }
            match = plugin;
        }
        if (match == null) {
            throw new VerificationException("missing " + artifactId + " build plugin in " + pom);
        }
        return match;
    }

    private static Element requiredExecution(Element plugin, String id, Path pom)
            throws VerificationException {
        Element executions = directChild(plugin, "executions");
        Element match = null;
        for (Element execution : directChildren(executions, "execution")) {
            if (!id.equals(optionalDirectText(execution, "id", ""))) {
                continue;
            }
            if (match != null) {
                throw new VerificationException("duplicate execution " + id + " in " + pom);
            }
            match = execution;
        }
        if (match == null) {
            throw new VerificationException("missing execution " + id + " in " + pom);
        }
        return match;
    }

    private static void requireGoals(Element execution, Set<String> expected, Path pom)
            throws VerificationException {
        Element goals = directChild(execution, "goals");
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        for (Element goal : directChildren(goals, "goal")) {
            if (!actual.add(goal.getTextContent().trim())) {
                throw new VerificationException("duplicate Maven goal in " + pom);
            }
        }
        compareSets("Maven execution goals in " + pom, expected, actual);
    }

    private static void requireFormats(Element execution, Path pom)
            throws VerificationException {
        Element configuration = directChild(execution, "configuration");
        Element formats = directChild(configuration, "formats");
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        for (Element format : directChildren(formats, "format")) {
            actual.add(format.getTextContent().trim());
        }
        compareSets("JaCoCo report formats in " + pom, Set.of("HTML", "XML"), actual);
    }

    private static void requireDelete(
            Element target,
            String attribute,
            String expected,
            Path pom)
            throws VerificationException {
        for (Element delete : directChildren(target, "delete")) {
            if (expected.equals(delete.getAttribute(attribute))) {
                return;
            }
        }
        throw new VerificationException(
                "coverage cleanup in " + pom + " must delete " + attribute + "=" + expected);
    }

    private static LinkedHashSet<String> readReactorPaths(Path rootPom) throws Exception {
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
        Element project = parseXml(pom).getDocumentElement();
        return new ProjectModel(
                requiredDirectText(project, "artifactId", pom),
                optionalDirectText(project, "packaging", "jar"));
    }

    private static Set<String> readReportDependencies(Path reportPom) throws Exception {
        Element project = parseXml(reportPom).getDocumentElement();
        Element dependencies = directChild(project, "dependencies");
        if (dependencies == null) {
            throw new VerificationException("coverage report POM has no direct dependencies");
        }
        LinkedHashSet<String> artifacts = new LinkedHashSet<>();
        for (Element dependency : directChildren(dependencies, "dependency")) {
            String groupId = requiredDirectText(dependency, "groupId", reportPom);
            String artifactId = requiredDirectText(dependency, "artifactId", reportPom);
            String type = optionalDirectText(dependency, "type", "jar");
            String scope = optionalDirectText(dependency, "scope", "compile");
            String optional = optionalDirectText(dependency, "optional", "false");
            if (!PROJECT_GROUP.equals(groupId)
                    || !"jar".equals(type)
                    || !"compile".equals(scope)
                    || !"false".equals(optional)
                    || directChild(dependency, "classifier") != null) {
                throw new VerificationException(
                        "coverage ordering dependency must be a compile-scope reactor JAR: "
                                + groupId + ":" + artifactId + ":" + type + ":" + scope);
            }
            if (!artifacts.add(artifactId)) {
                throw new VerificationException(
                        "duplicate coverage report dependency: " + artifactId);
            }
        }
        return artifacts;
    }

    private static Document parseXml(Path xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (var input = Files.newInputStream(xml)) {
            try {
                var builder = factory.newDocumentBuilder();
                builder.setErrorHandler(new DefaultHandler() {
                    @Override
                    public void error(SAXParseException exception) throws SAXException {
                        throw exception;
                    }

                    @Override
                    public void fatalError(SAXParseException exception) throws SAXException {
                        throw exception;
                    }
                });
                return builder.parse(input);
            } catch (SAXException e) {
                throw new VerificationException(
                        "cannot parse XML " + xml + ": " + e.getMessage(), e);
            }
        }
    }

    private static Element directChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Element child : directChildren(parent, name)) {
            return child;
        }
        return null;
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> elements = new ArrayList<>();
        if (parent == null) {
            return elements;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && name.equals(localName(element))) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
    }

    private static String requiredDirectText(Element parent, String name, Path source)
            throws VerificationException {
        Element child = directChild(parent, name);
        if (child == null || child.getTextContent().trim().isEmpty()) {
            throw new VerificationException("missing " + name + " in " + source);
        }
        return child.getTextContent().trim();
    }

    private static String optionalDirectText(Element parent, String name, String defaultValue) {
        Element child = directChild(parent, name);
        return child == null ? defaultValue : child.getTextContent().trim();
    }

    private static String validateModulePath(String raw, int lineNumber)
            throws VerificationException {
        String value = raw.trim();
        String location = lineNumber < 0 ? "root reactor" : "coverage scope line " + lineNumber;
        if (!value.equals(raw) || !MODULE_PATH.matcher(value).matches()
                || value.contains("//") || containsTraversalSegment(value)) {
            throw new VerificationException("invalid module path at " + location + ": " + raw);
        }
        return value;
    }

    private static boolean containsTraversalSegment(String value) {
        if (ROOT_PATH.equals(value)) {
            return false;
        }
        for (String segment : value.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String toModulePath(Path path) {
        String value = path.toString().replace(path.getFileSystem().getSeparator(), "/");
        return value.isEmpty() ? ROOT_PATH : value;
    }

    private static long parseNonNegativeLong(String subject, String raw)
            throws VerificationException {
        String value = raw.trim();
        if (!value.equals(raw) || !value.matches("0|[1-9][0-9]*")) {
            throw new VerificationException(
                    subject + " must be a non-negative decimal integer, found " + raw);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new VerificationException(subject + " is outside the supported integer range");
        }
    }

    private static void requireDirectory(Path directory, String subject)
            throws VerificationException {
        if (!Files.isDirectory(directory)) {
            throw new VerificationException(subject + " is missing: " + directory);
        }
    }

    private static void requireFile(Path file, String subject) throws VerificationException {
        if (!Files.isRegularFile(file)) {
            throw new VerificationException(subject + " is missing: " + file);
        }
    }

    private static void requireNonEmptyFile(Path file, String subject)
            throws IOException, VerificationException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            throw new VerificationException(subject + " is missing or empty: " + file);
        }
    }

    private static void requireUnderRoot(Path root, Path path, String subject)
            throws VerificationException {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new VerificationException(subject + " is outside the reactor root: " + path);
        }
    }

    private static void verifyHtml(Path html, String subject)
            throws IOException, VerificationException {
        requireNonEmptyFile(html, subject);
        String content = Files.readString(html, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (!content.contains("<html")) {
            throw new VerificationException(subject + " is not an HTML document: " + html);
        }
    }

    private static void requireValue(String subject, String expected, String actual)
            throws VerificationException {
        if (!expected.equals(actual)) {
            throw new VerificationException(
                    subject + " differs; expected=" + expected + ", actual=" + actual);
        }
    }

    private static <T> void compareSets(String subject, Set<T> expected, Set<T> actual)
            throws VerificationException {
        LinkedHashSet<T> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        LinkedHashSet<T> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(expected);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new VerificationException(
                    subject + " differ; missing=" + missing + ", unexpected=" + unexpected);
        }
    }

    private static String formatPercentage(Counter counter) {
        return String.format(Locale.ROOT, "%.4f%%", counter.percentage());
    }

    private enum Mode {
        VALIDATE,
        VERIFY_REPORTS;

        static Mode parse(String raw) throws VerificationException {
            return switch (raw) {
                case "validate" -> VALIDATE;
                case "verify-reports" -> VERIFY_REPORTS;
                default -> throw new VerificationException("unsupported coverage verifier mode: " + raw);
            };
        }
    }

    private enum Disposition {
        COVERED,
        EXCLUDED,
        AGGREGATE;

        static Disposition parse(String raw, int lineNumber) throws VerificationException {
            return switch (raw) {
                case "covered" -> COVERED;
                case "excluded" -> EXCLUDED;
                case "aggregate" -> AGGREGATE;
                default -> throw new VerificationException(
                        "invalid coverage disposition at line " + lineNumber + ": " + raw);
            };
        }
    }

    private enum LocalReport {
        REQUIRED("required"),
        AGGREGATE_ONLY("aggregate-only"),
        NONE("none");

        private final String externalName;

        LocalReport(String externalName) {
            this.externalName = externalName;
        }

        String externalName() {
            return externalName;
        }

        static LocalReport parse(String raw, int lineNumber) throws VerificationException {
            for (LocalReport value : values()) {
                if (value.externalName.equals(raw)) {
                    return value;
                }
            }
            throw new VerificationException(
                    "invalid local-report disposition at line " + lineNumber + ": " + raw);
        }
    }

    private enum CounterType {
        INSTRUCTION("instruction"),
        BRANCH("branch"),
        LINE("line"),
        COMPLEXITY("complexity"),
        METHOD("method"),
        CLASS("class");

        private final String externalName;

        CounterType(String externalName) {
            this.externalName = externalName;
        }

        String externalName() {
            return externalName;
        }

        static CounterType parse(String raw, String subject) throws VerificationException {
            try {
                return CounterType.valueOf(raw);
            } catch (IllegalArgumentException e) {
                throw new VerificationException(subject + " contains unknown counter type: " + raw);
            }
        }
    }

    private record ScopeEntry(
            String modulePath,
            String artifactId,
            Disposition disposition,
            LocalReport localReport,
            String rationale) {
    }

    private record ProjectModel(String artifactId, String packaging) {
    }

    private record Counter(long covered, long missed) {

        long total() throws VerificationException {
            try {
                return Math.addExact(covered, missed);
            } catch (ArithmeticException e) {
                throw new VerificationException("coverage counter total is outside the supported range");
            }
        }

        double percentage() {
            long total = covered + missed;
            return total == 0 ? 100.0 : covered * 100.0 / total;
        }

        String display() throws VerificationException {
            return covered + "/" + total();
        }
    }

    private record Ratchet(
            Counter lines,
            Counter branches,
            long instructionMissed,
            boolean absoluteInstructions,
            String rationale) {
    }

    private record ReportDocument(
            Map<CounterType, Counter> counters,
            Map<String, Map<CounterType, Counter>> groups) {
    }

    private record CoverageReport(Map<CounterType, Counter> aggregateCounters) {
    }

    private record Policy(
            Path root,
            Path reportModule,
            List<ScopeEntry> entries,
            List<ScopeEntry> coveredEntries,
            Map<String, Ratchet> ratchets) {

        int requiredLocalReports() {
            return (int) coveredEntries.stream()
                    .filter(entry -> entry.localReport() == LocalReport.REQUIRED)
                    .count();
        }

        int aggregateOnlyReports() {
            return (int) coveredEntries.stream()
                    .filter(entry -> entry.localReport() == LocalReport.AGGREGATE_ONLY)
                    .count();
        }
    }

    private static final class VerificationException extends Exception {

        private static final long serialVersionUID = 1L;

        VerificationException(String message) {
            super(message);
        }

        VerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
