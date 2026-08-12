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
 * Verifies fail-closed SpotBugs, CPD and PMD scope registries and reports.
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
    private static final String ANTRUN_PLUGIN = "maven-antrun-plugin";
    private static final String CPD_EXECUTION = "create-repository-cpd-report";
    private static final String PMD_PROFILE = "pmd-analysis";
    private static final String PMD_WATCHLIST_PROFILE = "pmd-watchlist";
    private static final String PMD_EXECUTION = "create-repository-pmd-report";
    private static final String PMD_CLEAN_EXECUTION = "clean-stale-pmd-output";
    private static final String PMD_VERIFY_EXECUTION = "verify-pmd-report-integrity";
    private static final String PMD_ENGINE_VERSION = "7.26.0";
    private static final String PMD_RULESET_PROPERTY = "${ioc.pmd.ruleset}";
    private static final String PMD_REPORT_DIRECTORY_PROPERTY = "${ioc.pmd.reportDirectory}";
    private static final String PMD_REPORT_KIND_PROPERTY = "${ioc.pmd.reportKind}";
    private static final String PMD_POLICY_RULESET = "${project.basedir}/pmd-ruleset.xml";
    private static final String PMD_WATCHLIST_RULESET =
            "${project.basedir}/pmd-watchlist-ruleset.xml";
    private static final String CPD_NAMESPACE = "https://pmd-code.org/schema/cpd-report";
    private static final String PMD_REPORT_NAMESPACE =
            "http://pmd.sourceforge.net/report/2.0.0";
    private static final String PMD_RULESET_NAMESPACE =
            "http://pmd.sourceforge.net/ruleset/2.0.0";
    private static final String SOURCE_ROOT_PREFIX = "${maven.multiModuleProjectDirectory}/";
    private static final String SOURCE_ROOT_SUFFIX = "/src/main/java";
    private static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern PMD_SUPPRESSION = Pattern.compile(
            "\\bNOPMD\\b|@SuppressWarnings\\s*\\((?s:[^)]*?\\bPMD(?:\\.[A-Za-z0-9]+)?\\b[^)]*?)\\)");
    static final Set<String> PMD_POLICY_RULES = Set.of(
            "category/java/bestpractices.xml/UnusedAssignment",
            "category/java/bestpractices.xml/UnusedFormalParameter",
            "category/java/bestpractices.xml/UnusedLocalVariable",
            "category/java/bestpractices.xml/UnusedPrivateField",
            "category/java/bestpractices.xml/UnusedPrivateMethod",
            "category/java/bestpractices.xml/RelianceOnDefaultCharset",
            "category/java/bestpractices.xml/UseStandardCharsets",
            "category/java/errorprone.xml/EmptyCatchBlock",
            "category/java/errorprone.xml/DoNotThrowExceptionInFinally",
            "category/java/errorprone.xml/ReturnFromFinallyBlock",
            "category/java/errorprone.xml/BrokenNullCheck",
            "category/java/errorprone.xml/MisplacedNullCheck",
            "category/java/errorprone.xml/UnusedNullCheckInEquals",
            "category/java/errorprone.xml/UselessPureMethodCall",
            "category/java/errorprone.xml/InvalidLogMessageFormat",
            "category/java/errorprone.xml/UseLocaleWithCaseConversions",
            "category/java/design.xml/CognitiveComplexity",
            "category/java/design.xml/NPathComplexity",
            "category/java/design.xml/ExcessiveParameterList",
            "category/java/performance.xml/InefficientStringBuffering",
            "category/java/performance.xml/StringInstantiation",
            "category/java/performance.xml/UselessStringValueOf");
    static final Set<String> PMD_WATCHLIST_RULES = Set.of(
            "category/java/bestpractices.xml/PreserveStackTrace",
            "category/java/errorprone.xml/CloseResource",
            "category/java/design.xml/NcssCount");
    private static final Map<String, Map<String, String>> PMD_POLICY_RULE_PROPERTIES = Map.of(
            "category/java/design.xml/CognitiveComplexity",
            Map.of("reportLevel", "16"),
            "category/java/design.xml/ExcessiveParameterList",
            Map.of("minimum", "13"));

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
            if (args.length < 2) {
                throw new VerificationException(
                        "usage: BuildQualityVerifier <spotbugs|cpd|pmd> "
                                + "<validate|verify-reports> "
                                + "<reactor-root> <scope-manifest> <report-pom> "
                                + "[policy|watchlist]");
            }

            Control control = Control.parse(args[0]);
            Mode mode = Mode.parse(args[1]);
            boolean requiresPmdReportKind = control == Control.PMD
                    && mode == Mode.VERIFY_REPORTS;
            int expectedArguments = requiresPmdReportKind ? 6 : 5;
            if (args.length != expectedArguments) {
                throw new VerificationException(
                        "usage: BuildQualityVerifier <spotbugs|cpd|pmd> "
                                + "<validate|verify-reports> "
                                + "<reactor-root> <scope-manifest> <report-pom> "
                                + "[policy|watchlist]");
            }
            Path root = Path.of(args[2]).toAbsolutePath().normalize();
            Path manifest = Path.of(args[3]).toAbsolutePath().normalize();
            Path reportPom = Path.of(args[4]).toAbsolutePath().normalize();
            Registry registry = validateScope(control, root, manifest, reportPom);

            if (mode == Mode.VERIFY_REPORTS) {
                PmdReportKind pmdReportKind = requiresPmdReportKind
                        ? PmdReportKind.parse(args[5])
                        : null;
                verifyReports(control, registry, pmdReportKind);
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
        } else if (control == Control.CPD) {
            CpdConfiguration configuration = readCpdConfiguration(reportPom);
            compareSets(
                    "analyzed scope versus configured CPD source roots",
                    configuredSourceRoots,
                    configuration.sourceRoots());
            validateCpdConfiguration(configuration);
        } else {
            PmdConfiguration configuration = readPmdConfiguration(reportPom);
            compareSets(
                    "analyzed scope versus configured PMD source roots",
                    configuredSourceRoots,
                    configuration.sourceRoots());
            validatePmdConfiguration(configuration, reportPom, root.resolve("pom.xml"));
            validateNoPmdSourceSuppressions(collectExpectedSources(analyzedSourceRoots));
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

    private static void validatePmdConfiguration(
            PmdConfiguration configuration,
            Path reportPom,
            Path rootPom)
            throws Exception {
        requireValue("PMD execution phase", "verify", configuration.phase());
        compareSets(
                "PMD execution goals",
                Set.of("aggregate-pmd-no-fork"),
                configuration.goals());
        requireValue("PMD skip", "false", configuration.skip());
        requireValue("PMD language", "java", configuration.language());
        requireValue("PMD targetJdk", "${java.version}", configuration.targetJdk());
        requireValue("PMD typeResolution", "true", configuration.typeResolution());
        requireValue("PMD includeTests", "false", configuration.includeTests());
        requireValue("PMD analysisCache", "false", configuration.analysisCache());
        requireValue("PMD skipPmdError", "false", configuration.skipPmdError());
        requireValue(
                "PMD renderProcessingErrors",
                "true",
                configuration.renderProcessingErrors());
        requireValue("PMD skipEmptyReport", "false", configuration.skipEmptyReport());
        requireValue("PMD linkXRef", "false", configuration.linkXRef());
        requireValue("PMD format", "xml", configuration.format());
        requireValue(
                "PMD targetDirectory",
                PMD_REPORT_DIRECTORY_PROPERTY,
                configuration.targetDirectory());
        requireValue(
                "PMD outputDirectory",
                PMD_REPORT_DIRECTORY_PROPERTY,
                configuration.outputDirectory());
        compareSets(
                "PMD ruleset configuration",
                Set.of(PMD_RULESET_PROPERTY),
                configuration.rulesets());
        compareSets(
                "PMD generated/vendor excludes",
                Set.of("**/generated/**", "**/vendor/**"),
                configuration.excludes());
        compareSets(
                "PMD engine plugin dependencies",
                Set.of(
                        "net.sourceforge.pmd:pmd-core:${pmd.version}",
                        "net.sourceforge.pmd:pmd-java:${pmd.version}"),
                configuration.pluginDependencies());

        Document rootDocument = parseXml(rootPom);
        Element properties = directChild(rootDocument.getDocumentElement(), "properties");
        requireValue(
                "reactor source encoding",
                "UTF-8",
                requiredDirectText(properties, "project.build.sourceEncoding", rootPom));
        requireValue(
                "reactor reporting output encoding",
                "UTF-8",
                requiredDirectText(properties, "project.reporting.outputEncoding", rootPom));
        requireValue(
                "PMD engine version property",
                PMD_ENGINE_VERSION,
                requiredDirectText(properties, "pmd.version", rootPom));

        validatePmdProfileProperties(reportPom);

        Path policyRuleset = reportPom.getParent().resolve("pmd-ruleset.xml");
        requireFile(policyRuleset, "PMD policy ruleset");
        validatePmdRuleset(
                policyRuleset,
                "policy",
                PMD_POLICY_RULES,
                PMD_POLICY_RULE_PROPERTIES);

        Path watchlistRuleset = reportPom.getParent().resolve("pmd-watchlist-ruleset.xml");
        requireFile(watchlistRuleset, "PMD watchlist ruleset");
        validatePmdRuleset(
                watchlistRuleset,
                "watchlist",
                PMD_WATCHLIST_RULES,
                Map.of());
    }

    private static void validatePmdRuleset(
            Path ruleset,
            String rulesetName,
            Set<String> expectedRules,
            Map<String, Map<String, String>> expectedProperties)
            throws Exception {
        Document document = parseXml(ruleset);
        Element root = document.getDocumentElement();
        if (!"ruleset".equals(root.getLocalName())
                || !PMD_RULESET_NAMESPACE.equals(root.getNamespaceURI())) {
            throw new VerificationException(
                    "unexpected PMD ruleset root: {" + root.getNamespaceURI()
                            + "}" + root.getLocalName());
        }

        if (document.getElementsByTagNameNS(PMD_RULESET_NAMESPACE, "exclude").getLength() != 0
                || document.getElementsByTagNameNS(
                        PMD_RULESET_NAMESPACE,
                        "exclude-pattern").getLength() != 0
                || document.getElementsByTagNameNS(
                        PMD_RULESET_NAMESPACE,
                        "include-pattern").getLength() != 0) {
            throw new VerificationException(
                    "PMD " + rulesetName
                            + " ruleset must not contain source filters or exclusions");
        }

        LinkedHashSet<String> rules = new LinkedHashSet<>();
        LinkedHashMap<String, Map<String, String>> propertiesByRule = new LinkedHashMap<>();
        NodeList ruleElements = document.getElementsByTagNameNS(PMD_RULESET_NAMESPACE, "rule");
        for (int index = 0; index < ruleElements.getLength(); index++) {
            Element rule = (Element) ruleElements.item(index);
            String reference = rule.getAttribute("ref").trim();
            if (reference.isEmpty()) {
                throw new VerificationException(
                        "PMD " + rulesetName + " ruleset contains a rule without ref");
            }
            int separator = reference.indexOf(".xml/");
            if (separator < 0 || separator + 5 >= reference.length()) {
                throw new VerificationException(
                        "PMD " + rulesetName
                                + " rule must reference one exact rule: " + reference);
            }
            if (!rules.add(reference)) {
                throw new VerificationException(
                        "duplicate PMD " + rulesetName + " rule reference: " + reference);
            }
            if (rule.getAttributes().getLength() != 1
                    || rule.getAttributes().getNamedItem("ref") == null) {
                throw new VerificationException(
                        "PMD " + rulesetName
                                + " rule must contain only the exact ref attribute: "
                                + reference);
            }

            LinkedHashMap<String, String> properties = new LinkedHashMap<>();
            Element propertiesElement = null;
            NodeList ruleChildren = rule.getChildNodes();
            for (int childIndex = 0; childIndex < ruleChildren.getLength(); childIndex++) {
                Node child = ruleChildren.item(childIndex);
                if (child instanceof Element element) {
                    if (!"properties".equals(element.getLocalName()) || propertiesElement != null) {
                        throw new VerificationException(
                                "PMD " + rulesetName
                                        + " rule contains unsupported configuration: "
                                        + reference + " has " + element.getLocalName());
                    }
                    propertiesElement = element;
                }
            }
            if (propertiesElement != null) {
                if (propertiesElement.getAttributes().getLength() != 0) {
                    throw new VerificationException(
                            "PMD " + rulesetName
                                    + " properties element must not contain attributes: "
                                    + reference);
                }
                List<Element> propertyElements = new ArrayList<>();
                NodeList propertyChildren = propertiesElement.getChildNodes();
                for (int childIndex = 0; childIndex < propertyChildren.getLength(); childIndex++) {
                    Node child = propertyChildren.item(childIndex);
                    if (!(child instanceof Element element)) {
                        continue;
                    }
                    if (!"property".equals(element.getLocalName())) {
                        throw new VerificationException(
                                "PMD " + rulesetName
                                        + " rule properties contain an unsupported element: "
                                        + reference + "/" + element.getLocalName());
                    }
                    propertyElements.add(element);
                }
                for (Element property : propertyElements) {
                    String name = property.getAttribute("name").trim();
                    String value = property.getAttribute("value").trim();
                    if (name.isEmpty() || value.isEmpty()) {
                        throw new VerificationException(
                                "PMD " + rulesetName
                                        + " rule property must have name and value: " + reference);
                    }
                    if (property.getAttributes().getLength() != 2
                            || property.getAttributes().getNamedItem("name") == null
                            || property.getAttributes().getNamedItem("value") == null) {
                        throw new VerificationException(
                                "PMD " + rulesetName
                                        + " rule property must contain only name and value: "
                                        + reference + "/" + name);
                    }
                    if (property.getChildNodes().getLength() != 0) {
                        throw new VerificationException(
                                "PMD " + rulesetName
                                        + " rule property must use an exact value attribute: "
                                        + reference + "/" + name);
                    }
                    if (properties.putIfAbsent(name, value) != null) {
                        throw new VerificationException(
                                "duplicate PMD " + rulesetName + " rule property: "
                                        + reference + "/" + name);
                    }
                }
            }
            propertiesByRule.put(reference, Map.copyOf(properties));
        }
        compareSets("PMD " + rulesetName + " rules", expectedRules, rules);
        for (String rule : expectedRules) {
            Map<String, String> expected = expectedProperties.getOrDefault(rule, Map.of());
            Map<String, String> actual = propertiesByRule.getOrDefault(rule, Map.of());
            if (!expected.equals(actual)) {
                throw new VerificationException(
                        "PMD " + rulesetName + " rule properties differ for " + rule
                                + "; expected=" + expected + ", actual=" + actual);
            }
        }
    }

    private static void validatePmdProfileProperties(Path reportPom)
            throws Exception {
        Document document = parseXml(reportPom);
        Element project = document.getDocumentElement();
        Element properties = directChild(project, "properties");
        if (properties == null) {
            throw new VerificationException("PMD report POM has no policy properties");
        }
        requireValue(
                "PMD default ruleset",
                PMD_POLICY_RULESET,
                requiredDirectText(properties, "ioc.pmd.ruleset", reportPom));
        requireValue(
                "PMD default report directory",
                "${project.build.directory}/pmd",
                requiredDirectText(properties, "ioc.pmd.reportDirectory", reportPom));
        requireValue(
                "PMD default report kind",
                "policy",
                requiredDirectText(properties, "ioc.pmd.reportKind", reportPom));

        Element profiles = directChild(project, "profiles");
        Element watchlistProfile = null;
        for (Element profile : directChildren(profiles, "profile")) {
            if (!PMD_WATCHLIST_PROFILE.equals(optionalDirectText(profile, "id", ""))) {
                continue;
            }
            if (watchlistProfile != null) {
                throw new VerificationException("duplicate PMD watchlist profile");
            }
            watchlistProfile = profile;
        }
        if (watchlistProfile == null) {
            throw new VerificationException(
                    "PMD report POM has no " + PMD_WATCHLIST_PROFILE + " profile");
        }
        LinkedHashSet<String> profileChildren = new LinkedHashSet<>();
        NodeList profileNodes = watchlistProfile.getChildNodes();
        for (int index = 0; index < profileNodes.getLength(); index++) {
            Node child = profileNodes.item(index);
            if (child instanceof Element element
                    && !profileChildren.add(element.getLocalName())) {
                throw new VerificationException(
                        "duplicate PMD watchlist profile element: " + element.getLocalName());
            }
        }
        compareSets(
                "PMD watchlist profile elements",
                Set.of("id", "properties"),
                profileChildren);

        Element watchlistProperties = directChild(watchlistProfile, "properties");
        if (watchlistProperties == null) {
            throw new VerificationException("PMD watchlist profile has no properties");
        }
        LinkedHashSet<String> propertyNames = new LinkedHashSet<>();
        NodeList children = watchlistProperties.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element) {
                if (!propertyNames.add(element.getLocalName())) {
                    throw new VerificationException(
                            "duplicate PMD watchlist profile property: "
                                    + element.getLocalName());
                }
            }
        }
        compareSets(
                "PMD watchlist profile properties",
                Set.of("ioc.pmd.ruleset", "ioc.pmd.reportDirectory", "ioc.pmd.reportKind"),
                propertyNames);
        requireValue(
                "PMD watchlist ruleset",
                PMD_WATCHLIST_RULESET,
                requiredDirectText(watchlistProperties, "ioc.pmd.ruleset", reportPom));
        requireValue(
                "PMD watchlist report directory",
                "${project.build.directory}/pmd-watchlist",
                requiredDirectText(watchlistProperties, "ioc.pmd.reportDirectory", reportPom));
        requireValue(
                "PMD watchlist report kind",
                "watchlist",
                requiredDirectText(watchlistProperties, "ioc.pmd.reportKind", reportPom));
    }

    private static void validateNoPmdSourceSuppressions(Set<Path> sources)
            throws IOException, VerificationException {
        for (Path source : sources) {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            if (PMD_SUPPRESSION.matcher(content).find()) {
                throw new VerificationException(
                        "PMD source suppression marker is forbidden: " + source);
            }
        }
    }

    private static void requireValue(String subject, String expected, String actual)
            throws VerificationException {
        if (!expected.equals(actual)) {
            throw new VerificationException(
                    subject + " must be " + expected + ", found " + actual);
        }
    }

    private static void verifyReports(
            Control control,
            Registry registry,
            PmdReportKind pmdReportKind)
            throws Exception {
        if (control == Control.SPOTBUGS) {
            verifySpotBugsReports(registry);
        } else if (control == Control.CPD) {
            verifyCpdReports(registry);
        } else {
            verifyPmdReports(registry, pmdReportKind);
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

    private static void verifyPmdReports(Registry registry, PmdReportKind reportKind)
            throws Exception {
        if (reportKind == null) {
            throw new VerificationException("PMD report kind is required");
        }
        Path reportDirectory = registry.reportModuleDirectory().resolve(reportKind.reportDirectory());
        Path xml = reportDirectory.resolve("pmd.xml");
        Path html = reportDirectory.resolve("pmd.html");
        requireNonEmptyReport(xml, "PMD XML");
        requireNonEmptyReport(html, "PMD HTML");

        Set<Path> expectedSources = collectExpectedSources(registry.analyzedSourceRoots());
        readPmdReport(registry.root(), xml, expectedSources, reportKind.ruleNames());
        verifyPmdHtml(html);
    }

    private static void readPmdReport(
            Path root,
            Path xml,
            Set<Path> expectedSources,
            Set<String> expectedRuleNames)
            throws Exception {
        Document document = parseXml(xml);
        Element report = document.getDocumentElement();
        if (!"pmd".equals(report.getLocalName())
                || !PMD_REPORT_NAMESPACE.equals(report.getNamespaceURI())) {
            throw new VerificationException(
                    "unexpected PMD XML root: {" + report.getNamespaceURI()
                            + "}" + report.getLocalName());
        }
        requireValue("PMD report engine version", PMD_ENGINE_VERSION, report.getAttribute("version"));

        int processingErrors = report
                .getElementsByTagNameNS(PMD_REPORT_NAMESPACE, "error")
                .getLength();
        int configurationErrors = report
                .getElementsByTagNameNS(PMD_REPORT_NAMESPACE, "configerror")
                .getLength();
        if (processingErrors != 0 || configurationErrors != 0) {
            throw new VerificationException(
                    "PMD XML contains analyzer errors: processing=" + processingErrors
                            + ", configuration=" + configurationErrors);
        }

        NodeList violations = report.getElementsByTagNameNS(PMD_REPORT_NAMESPACE, "violation");
        for (int index = 0; index < violations.getLength(); index++) {
            Element violation = (Element) violations.item(index);
            String rule = violation.getAttribute("rule");
            if (!expectedRuleNames.contains(rule)) {
                throw new VerificationException(
                        "PMD XML contains a rule outside the selected policy: " + rule);
            }
        }

        NodeList files = report.getElementsByTagNameNS(PMD_REPORT_NAMESPACE, "file");
        for (int index = 0; index < files.getLength(); index++) {
            Element file = (Element) files.item(index);
            String rawPath = file.getAttribute("name");
            if (rawPath.isBlank()) {
                throw new VerificationException("PMD XML contains a file without name");
            }
            Path source = Path.of(rawPath);
            if (!source.isAbsolute()) {
                source = root.resolve(source);
            }
            source = source.toAbsolutePath().normalize();
            requireUnderRoot(root, source, "PMD XML source path");
            if (!expectedSources.contains(source)) {
                throw new VerificationException(
                        "PMD XML references a source outside the analyzed inventory: " + source);
            }
        }
    }

    private static void verifyPmdHtml(Path html)
            throws IOException, VerificationException {
        String content = Files.readString(html, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        if (!content.contains("<html") || !content.contains("pmd results")) {
            throw new VerificationException(
                    "PMD HTML does not contain the expected report document: " + html);
        }
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
            throw new VerificationException("analyzed source scope contains no Java sources");
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

    private static PmdConfiguration readPmdConfiguration(Path reportPom)
            throws Exception {
        Document document = parseXml(reportPom);
        Element project = document.getDocumentElement();
        Element profiles = directChild(project, "profiles");
        if (profiles == null) {
            throw new VerificationException("PMD report POM has no profiles");
        }

        Element analysisProfile = null;
        for (Element profile : directChildren(profiles, "profile")) {
            if (!PMD_PROFILE.equals(optionalDirectText(profile, "id", ""))) {
                continue;
            }
            if (analysisProfile != null) {
                throw new VerificationException("duplicate PMD analysis profile");
            }
            analysisProfile = profile;
        }
        if (analysisProfile == null) {
            throw new VerificationException(
                    "PMD report POM has no " + PMD_PROFILE + " profile");
        }

        Element build = directChild(analysisProfile, "build");
        Element plugins = build == null ? null : directChild(build, "plugins");
        if (plugins == null) {
            throw new VerificationException("PMD analysis profile has no build plugins");
        }

        validatePmdLifecycleWiring(plugins, reportPom);

        Element pmdPlugin = null;
        for (Element plugin : directChildren(plugins, "plugin")) {
            if (PMD_PLUGIN.equals(optionalDirectText(plugin, "artifactId", ""))) {
                if (pmdPlugin != null) {
                    throw new VerificationException(
                            "PMD analysis profile has duplicate Maven PMD Plugin entries");
                }
                pmdPlugin = plugin;
            }
        }
        if (pmdPlugin == null) {
            throw new VerificationException(
                    "PMD analysis profile has no Maven PMD Plugin");
        }

        LinkedHashSet<String> pluginDependencies = new LinkedHashSet<>();
        Element dependencies = directChild(pmdPlugin, "dependencies");
        if (dependencies != null) {
            for (Element dependency : directChildren(dependencies, "dependency")) {
                String coordinate = requiredDirectText(dependency, "groupId", reportPom)
                        + ":" + requiredDirectText(dependency, "artifactId", reportPom)
                        + ":" + requiredDirectText(dependency, "version", reportPom);
                if (!pluginDependencies.add(coordinate)) {
                    throw new VerificationException(
                            "duplicate PMD engine plugin dependency: " + coordinate);
                }
            }
        }

        Element executions = directChild(pmdPlugin, "executions");
        Element pmdExecution = null;
        if (executions != null) {
            for (Element execution : directChildren(executions, "execution")) {
                if (!PMD_EXECUTION.equals(optionalDirectText(execution, "id", ""))) {
                    continue;
                }
                if (pmdExecution != null) {
                    throw new VerificationException("duplicate PMD report execution");
                }
                pmdExecution = execution;
            }
        }
        if (pmdExecution == null) {
            throw new VerificationException(
                    "PMD report POM has no " + PMD_EXECUTION + " execution");
        }

        Element configuration = directChild(pmdExecution, "configuration");
        if (configuration == null) {
            throw new VerificationException("PMD execution has no configuration");
        }

        LinkedHashSet<String> goals = readDirectTextSet(
                directChild(pmdExecution, "goals"),
                "goal",
                "PMD execution goal");
        LinkedHashSet<String> sourceRoots = readDirectTextSet(
                directChild(configuration, "compileSourceRoots"),
                "compileSourceRoot",
                "configured PMD source root");
        LinkedHashSet<String> excludes = readDirectTextSet(
                directChild(configuration, "excludes"),
                "exclude",
                "configured PMD exclude");
        LinkedHashSet<String> rulesets = readDirectTextSet(
                directChild(configuration, "rulesets"),
                "ruleset",
                "configured PMD ruleset");

        return new PmdConfiguration(
                requiredDirectText(pmdExecution, "phase", reportPom),
                Set.copyOf(goals),
                requiredDirectText(configuration, "skip", reportPom),
                requiredDirectText(configuration, "language", reportPom),
                requiredDirectText(configuration, "targetJdk", reportPom),
                requiredDirectText(configuration, "typeResolution", reportPom),
                requiredDirectText(configuration, "includeTests", reportPom),
                requiredDirectText(configuration, "analysisCache", reportPom),
                requiredDirectText(configuration, "skipPmdError", reportPom),
                requiredDirectText(configuration, "renderProcessingErrors", reportPom),
                requiredDirectText(configuration, "skipEmptyReport", reportPom),
                requiredDirectText(configuration, "linkXRef", reportPom),
                requiredDirectText(configuration, "format", reportPom),
                requiredDirectText(configuration, "targetDirectory", reportPom),
                requiredDirectText(configuration, "outputDirectory", reportPom),
                Set.copyOf(rulesets),
                Set.copyOf(sourceRoots),
                Set.copyOf(excludes),
                Set.copyOf(pluginDependencies));
    }

    private static void validatePmdLifecycleWiring(Element plugins, Path reportPom)
            throws VerificationException {
        Element antrunPlugin = null;
        for (Element plugin : directChildren(plugins, "plugin")) {
            if (!ANTRUN_PLUGIN.equals(optionalDirectText(plugin, "artifactId", ""))) {
                continue;
            }
            if (antrunPlugin != null) {
                throw new VerificationException(
                        "PMD analysis profile has duplicate Maven AntRun Plugin entries");
            }
            antrunPlugin = plugin;
        }
        if (antrunPlugin == null) {
            throw new VerificationException(
                    "PMD analysis profile has no Maven AntRun Plugin integrity wiring");
        }

        Element executions = directChild(antrunPlugin, "executions");
        LinkedHashMap<String, Element> byId = new LinkedHashMap<>();
        if (executions != null) {
            for (Element execution : directChildren(executions, "execution")) {
                String id = requiredDirectText(execution, "id", reportPom);
                Element previous = byId.putIfAbsent(id, execution);
                if (previous != null) {
                    throw new VerificationException(
                            "duplicate PMD lifecycle execution: " + id);
                }
            }
        }
        compareSets(
                "PMD lifecycle execution IDs",
                Set.of(PMD_CLEAN_EXECUTION, PMD_VERIFY_EXECUTION),
                byId.keySet());

        Element clean = byId.get(PMD_CLEAN_EXECUTION);
        validateExecutionPhaseAndGoal(clean, reportPom, PMD_CLEAN_EXECUTION, "initialize");
        Element cleanTarget = requiredDirectChild(
                requiredDirectChild(clean, "configuration", reportPom),
                "target",
                reportPom);
        Element delete = requiredDirectChild(cleanTarget, "delete", reportPom);
        requireValue(
                "PMD stale-output directory",
                PMD_REPORT_DIRECTORY_PROPERTY,
                delete.getAttribute("dir"));
        requireValue("PMD stale-output delete quiet", "true", delete.getAttribute("quiet"));

        Element verify = byId.get(PMD_VERIFY_EXECUTION);
        validateExecutionPhaseAndGoal(verify, reportPom, PMD_VERIFY_EXECUTION, "verify");
        Element verifyTarget = requiredDirectChild(
                requiredDirectChild(verify, "configuration", reportPom),
                "target",
                reportPom);
        Element command = requiredDirectChild(verifyTarget, "exec", reportPom);
        requireValue(
                "PMD report verifier executable",
                "${java.home}/bin/java",
                command.getAttribute("executable"));
        requireValue(
                "PMD report verifier failonerror",
                "true",
                command.getAttribute("failonerror"));

        List<String> arguments = new ArrayList<>();
        for (Element argument : directChildren(command, "arg")) {
            String value = argument.getAttribute("value");
            if (value.isBlank()) {
                throw new VerificationException("PMD report verifier argument must not be blank");
            }
            arguments.add(value);
        }
        List<String> expectedArguments = List.of(
                "${maven.multiModuleProjectDirectory}/build-support/build-quality/BuildQualityVerifier.java",
                "pmd",
                "verify-reports",
                "${maven.multiModuleProjectDirectory}",
                "${project.basedir}/pmd-scope.tsv",
                "${project.basedir}/pom.xml",
                PMD_REPORT_KIND_PROPERTY);
        if (!expectedArguments.equals(arguments)) {
            throw new VerificationException(
                    "PMD report verifier arguments differ; expected=" + expectedArguments
                            + ", actual=" + arguments);
        }
    }

    private static void validateExecutionPhaseAndGoal(
            Element execution,
            Path pom,
            String executionId,
            String expectedPhase)
            throws VerificationException {
        requireValue(
                executionId + " phase",
                expectedPhase,
                requiredDirectText(execution, "phase", pom));
        compareSets(
                executionId + " goals",
                Set.of("run"),
                readDirectTextSet(
                        directChild(execution, "goals"),
                        "goal",
                        executionId + " goal"));
    }

    private static Element requiredDirectChild(Element parent, String name, Path file)
            throws VerificationException {
        Element child = directChild(parent, name);
        if (child == null) {
            throw new VerificationException(
                    "missing " + name + " in " + file + " under " + parent.getLocalName());
        }
        return child;
    }

    private static LinkedHashSet<String> readDirectTextSet(
            Element parent,
            String childName,
            String subject)
            throws VerificationException {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (parent == null) {
            return values;
        }
        for (Element child : directChildren(parent, childName)) {
            String value = child.getTextContent().trim();
            if (value.isEmpty()) {
                throw new VerificationException(subject + " must not be blank");
            }
            if (!values.add(value)) {
                throw new VerificationException("duplicate " + subject + ": " + value);
            }
        }
        return values;
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
        CPD("cpd", "CPD"),
        PMD("pmd", "PMD");

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

    private enum PmdReportKind {
        POLICY("policy", "target/pmd", PMD_POLICY_RULES),
        WATCHLIST("watchlist", "target/pmd-watchlist", PMD_WATCHLIST_RULES);

        private final String externalName;
        private final String reportDirectory;
        private final Set<String> ruleNames;

        PmdReportKind(
                String externalName,
                String reportDirectory,
                Set<String> ruleReferences) {
            this.externalName = externalName;
            this.reportDirectory = reportDirectory;
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (String reference : ruleReferences) {
                names.add(reference.substring(reference.lastIndexOf('/') + 1));
            }
            this.ruleNames = Set.copyOf(names);
        }

        static PmdReportKind parse(String value) throws VerificationException {
            for (PmdReportKind kind : values()) {
                if (kind.externalName.equals(value)) {
                    return kind;
                }
            }
            throw new VerificationException("unknown PMD report kind: " + value);
        }

        String reportDirectory() {
            return reportDirectory;
        }

        Set<String> ruleNames() {
            return ruleNames;
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

    private record PmdConfiguration(
            String phase,
            Set<String> goals,
            String skip,
            String language,
            String targetJdk,
            String typeResolution,
            String includeTests,
            String analysisCache,
            String skipPmdError,
            String renderProcessingErrors,
            String skipEmptyReport,
            String linkXRef,
            String format,
            String targetDirectory,
            String outputDirectory,
            Set<String> rulesets,
            Set<String> sourceRoots,
            Set<String> excludes,
            Set<String> pluginDependencies) {
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
