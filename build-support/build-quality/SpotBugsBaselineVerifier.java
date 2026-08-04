import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Enforces the reviewed SpotBugs finding baseline against unfiltered analyzer output.
 *
 * <p>The checked-in accepted-findings document is the only suppression source of truth.
 * {@code validate} materializes the narrow operational {@code FindBugsFilter};
 * {@code verify} compares raw findings exactly before checking filtered and aggregate
 * report integrity; {@code propose} writes a non-accepting delta document under
 * {@code target/}. The tool deliberately uses only JDK APIs so it can run during Maven
 * {@code validate} without introducing a build-plugin module.</p>
 */
public final class SpotBugsBaselineVerifier {

    private static final String ROOT_PATH = ".";
    private static final String BASELINE_ROOT = "spotbugs-accepted-findings";
    private static final String BASELINE_SCHEMA_VERSION = "2";
    private static final String PROPOSAL_ROOT = "spotbugs-baseline-proposal";
    private static final String PROPOSAL_SCHEMA_VERSION = "1";
    private static final Pattern ID = Pattern.compile(
            "[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-[0-9]{2,4}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{1,32}");
    private static final Pattern TOKEN = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern MODULE_PATH = Pattern.compile("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*");
    private static final Pattern REVIEW_TRIGGER_ID = Pattern.compile(
            "[a-z][a-z0-9]*(?:-[a-z0-9]+)+");
    private static final Set<String> INVALID_REVIEW_TRIGGER_TEXT = Set.of(
            "review when code changes",
            "review when analyzer changes",
            "review when code or analyzer changes",
            "review when the boundary contract changes");
    private static final Set<String> FINDING_ATTRIBUTES = Set.of(
            "id",
            "module",
            "type",
            "hash",
            "occurrence",
            "priority",
            "rank",
            "category",
            "disposition",
            "owner",
            "evidence",
            "review");

    private SpotBugsBaselineVerifier() {
    }

    public static void main(String[] args) {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] args, PrintStream standardOutput, PrintStream errorOutput) {
        try {
            if (args.length != 5) {
                throw new BaselineException(
                        "usage: SpotBugsBaselineVerifier <validate|verify|propose> <reactor-root> "
                                + "<scope-manifest> <accepted-findings> <mode-output>");
            }

            Mode mode = Mode.parse(args[0]);
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            Path scopeManifest = Path.of(args[2]).toAbsolutePath().normalize();
            Path baselineFile = Path.of(args[3]).toAbsolutePath().normalize();
            Path finalPath = Path.of(args[4]).toAbsolutePath().normalize();
            requireUnderRoot(root, finalPath, "baseline verifier output/report path");
            if (mode == Mode.PROPOSE) {
                prepareProposalOutput(root, finalPath);
            }
            Scope scope = readScope(root, scopeManifest);
            Baseline baseline = readBaseline(root, baselineFile, scope);

            if (mode == Mode.VALIDATE) {
                writeGeneratedFilter(finalPath, baseline);
                standardOutput.printf(
                        Locale.ROOT,
                        "[spotbugs-baseline] validate completed: %d findings, %d selectors%n",
                        baseline.findings().size(),
                        uniqueSuppressions(baseline.findings()).size());
            } else if (mode == Mode.VERIFY) {
                verifyReports(root, scope, baseline, finalPath);
                standardOutput.printf(
                        Locale.ROOT,
                        "[spotbugs-baseline] verify completed: %d accepted, 0 visible%n",
                        baseline.findings().size());
            } else {
                ProposalSummary summary = writeProposal(root, scope, baseline, finalPath);
                standardOutput.printf(
                        Locale.ROOT,
                        "[spotbugs-baseline] proposal completed: %d new, %d stale; output=%s%n",
                        summary.newFindings(),
                        summary.staleAcceptances(),
                        finalPath);
            }
            return 0;
        } catch (BaselineException e) {
            errorOutput.println("[spotbugs-baseline] ERROR: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            errorOutput.println(
                    "[spotbugs-baseline] ERROR: unexpected verifier failure: " + e.getMessage());
            e.printStackTrace(errorOutput);
            return 1;
        }
    }

    private static Scope readScope(Path root, Path manifest)
            throws IOException, BaselineException {
        requireDirectory(root, "reactor root");
        requireFile(manifest, "SpotBugs scope manifest");
        requireUnderRoot(root, manifest, "SpotBugs scope manifest");

        LinkedHashSet<String> analyzed = new LinkedHashSet<>();
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        String aggregate = null;
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 4) {
                throw new BaselineException(
                        "scope manifest line " + (index + 1)
                                + " must contain four tab-separated fields");
            }
            String module = requireModulePath(fields[0], "scope manifest line " + (index + 1));
            switch (fields[2]) {
                case "analyzed" -> analyzed.add(module);
                case "excluded" -> excluded.add(module);
                case "aggregate" -> {
                    if (aggregate != null) {
                        throw new BaselineException(
                                "scope manifest contains more than one aggregate module");
                    }
                    aggregate = module;
                }
                default -> throw new BaselineException(
                        "unknown scope disposition at line " + (index + 1) + ": " + fields[2]);
            }
        }
        if (analyzed.isEmpty() || aggregate == null) {
            throw new BaselineException(
                    "scope manifest requires analyzed modules and one aggregate module");
        }
        return new Scope(Set.copyOf(analyzed), Set.copyOf(excluded), aggregate);
    }

    private static Baseline readBaseline(Path root, Path file, Scope scope)
            throws Exception {
        requireFile(file, "SpotBugs accepted-findings baseline");
        requireUnderRoot(root, file, "SpotBugs accepted-findings baseline");
        Element documentRoot = parseXml(file).getDocumentElement();
        if (!BASELINE_ROOT.equals(documentRoot.getTagName())) {
            throw new BaselineException(
                    "unexpected accepted-findings root: " + documentRoot.getTagName());
        }
        requireOnlyAttributes(documentRoot, Set.of("schemaVersion", "engineVersion"), "baseline root");
        requireValue(
                "baseline schemaVersion",
                BASELINE_SCHEMA_VERSION,
                requiredAttribute(documentRoot, "schemaVersion", "baseline root"));
        String engineVersion = requiredAttribute(documentRoot, "engineVersion", "baseline root");

        LinkedHashMap<String, String> reviewTriggers = new LinkedHashMap<>();
        List<Element> findingElements = new ArrayList<>();
        for (Element element : directChildElements(documentRoot)) {
            if ("review-trigger".equals(element.getTagName())) {
                Map.Entry<String, String> trigger = readReviewTrigger(element);
                if (reviewTriggers.putIfAbsent(trigger.getKey(), trigger.getValue()) != null) {
                    throw new BaselineException("duplicate review trigger: " + trigger.getKey());
                }
            } else if ("finding".equals(element.getTagName())) {
                findingElements.add(element);
            } else {
                throw new BaselineException(
                        "unexpected element in accepted-findings baseline: "
                                + element.getTagName());
            }
        }
        if (reviewTriggers.isEmpty()) {
            throw new BaselineException("accepted-findings baseline contains no review triggers");
        }

        List<AcceptedFinding> findings = new ArrayList<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        LinkedHashSet<FindingKey> keys = new LinkedHashSet<>();
        LinkedHashSet<String> usedReviewTriggers = new LinkedHashSet<>();
        for (Element element : findingElements) {
            AcceptedFinding finding = readAcceptedFinding(element, scope, reviewTriggers);
            if (!ids.add(finding.id())) {
                throw new BaselineException("duplicate accepted finding id: " + finding.id());
            }
            if (!keys.add(finding.key())) {
                throw new BaselineException(
                        "duplicate accepted finding identity: " + finding.key().summary());
            }
            findings.add(finding);
            usedReviewTriggers.add(finding.reviewTrigger());
        }
        if (findings.isEmpty()) {
            throw new BaselineException("accepted-findings baseline contains no findings");
        }
        validateOccurrenceSequences(findings.stream().map(AcceptedFinding::key).toList(), "baseline");
        Set<String> unusedReviewTriggers = new TreeSet<>(reviewTriggers.keySet());
        unusedReviewTriggers.removeAll(usedReviewTriggers);
        if (!unusedReviewTriggers.isEmpty()) {
            throw new BaselineException("unused review triggers: " + unusedReviewTriggers);
        }
        return new Baseline(engineVersion, List.copyOf(findings));
    }

    private static Map.Entry<String, String> readReviewTrigger(Element element)
            throws BaselineException {
        requireOnlyAttributes(element, Set.of("id"), "review trigger");
        if (!directChildElements(element).isEmpty()) {
            throw new BaselineException("review trigger must contain text only");
        }
        String id = requiredAttribute(element, "id", "review trigger");
        if (!REVIEW_TRIGGER_ID.matcher(id).matches()) {
            throw new BaselineException("invalid review trigger id: " + id);
        }
        String text = element.getTextContent().trim();
        String normalized = text.toLowerCase(Locale.ROOT);
        if (!text.startsWith("Review when ") || text.length() < 40) {
            throw new BaselineException(
                    "review trigger " + id + " must be a concrete 'Review when ...' condition");
        }
        if (INVALID_REVIEW_TRIGGER_TEXT.contains(normalized)
                || normalized.contains("code or analyzer")
                || normalized.contains("analyzer or code")
                || normalized.contains("any code change")
                || normalized.contains("todo")
                || normalized.contains("tbd")
                || normalized.contains("placeholder")
                || normalized.contains("unreviewed")) {
            throw new BaselineException("review trigger " + id + " is generic or incomplete");
        }
        return Map.entry(id, text);
    }

    private static AcceptedFinding readAcceptedFinding(
            Element element,
            Scope scope,
            Map<String, String> reviewTriggers)
            throws BaselineException {
        requireOnlyAttributes(element, FINDING_ATTRIBUTES, "finding");
        String id = requiredAttribute(element, "id", "finding");
        if (!ID.matcher(id).matches()) {
            throw new BaselineException("invalid accepted finding id: " + id);
        }
        String module = requireModulePath(
                requiredAttribute(element, "module", "finding " + id), "finding " + id);
        if (!scope.analyzedModules().contains(module)) {
            throw new BaselineException(
                    "accepted finding " + id + " belongs to non-analyzed module: " + module);
        }
        String type = requireToken(requiredAttribute(element, "type", "finding " + id), "type", id);
        String hash = requiredAttribute(element, "hash", "finding " + id);
        if (!HASH.matcher(hash).matches()) {
            throw new BaselineException("invalid instance hash for " + id + ": " + hash);
        }
        int occurrence = requireInteger(element, "occurrence", 0, Integer.MAX_VALUE, id);
        int priority = requireInteger(element, "priority", 1, 3, id);
        int rank = requireInteger(element, "rank", 1, 20, id);
        String category = requireToken(
                requiredAttribute(element, "category", "finding " + id), "category", id);
        Disposition disposition = Disposition.parse(
                requiredAttribute(element, "disposition", "finding " + id), id);
        String owner = requiredAttribute(element, "owner", "finding " + id);
        String evidence = requiredAttribute(element, "evidence", "finding " + id);
        String reviewTrigger = requiredAttribute(element, "review", "finding " + id);
        if (!reviewTriggers.containsKey(reviewTrigger)) {
            throw new BaselineException(
                    "accepted finding " + id
                            + " references unknown review trigger: " + reviewTrigger);
        }

        Map<String, Element> children = uniqueDirectChildren(
                element,
                Set.of("class", "method", "field", "anchor", "suppression", "rationale"),
                "finding " + id);
        Element classElement = requiredChild(children, "class", id);
        requireOnlyAttributes(classElement, Set.of("name"), "class of " + id);
        String className = requiredAttribute(classElement, "name", "class of " + id);

        Element method = children.get("method");
        Element field = children.get("field");
        if ((method == null) == (field == null)) {
            throw new BaselineException(
                    "finding " + id + " must contain exactly one primary method or field");
        }
        Member member = readMember(method != null ? method : field, method != null, id);
        Anchor anchor = readAnchor(requiredChild(children, "anchor", id), id);
        Suppression suppression = readSuppression(requiredChild(children, "suppression", id), id);
        String rationale = requiredChild(children, "rationale", id).getTextContent().trim();
        if (rationale.isBlank()) {
            throw new BaselineException("missing rationale for accepted finding " + id);
        }
        if (!suppression.bugType().equals(type) || !suppression.className().equals(className)) {
            throw new BaselineException(
                    "suppression type/class must equal finding identity for " + id);
        }
        FindingKey key = new FindingKey(
                module,
                type,
                hash,
                occurrence,
                priority,
                rank,
                category,
                className,
                member.kind(),
                member.name(),
                member.signature(),
                anchor.sourcePath(),
                anchor.bytecode());
        return new AcceptedFinding(
                id,
                key,
                anchor.startLine(),
                disposition,
                owner,
                evidence,
                reviewTrigger,
                suppression,
                rationale);
    }

    private static Member readMember(Element element, boolean method, String id)
            throws BaselineException {
        requireOnlyAttributes(element, Set.of("name", "signature"), "primary member of " + id);
        String name = requiredAttribute(element, "name", "primary member of " + id);
        String signature = requiredAttribute(element, "signature", "primary member of " + id);
        if (method && !signature.startsWith("(")) {
            throw new BaselineException("method signature must start with '(' for " + id);
        }
        return new Member(method ? MemberKind.METHOD : MemberKind.FIELD, name, signature);
    }

    private static Anchor readAnchor(Element element, String id)
            throws BaselineException {
        requireOnlyAttributes(element, Set.of("sourcePath", "startLine", "bytecode"), "anchor of " + id);
        String sourcePath = element.getAttribute("sourcePath");
        String startLineText = element.getAttribute("startLine");
        String bytecodeText = element.getAttribute("bytecode");
        if (sourcePath.isBlank()) {
            if (!startLineText.isBlank() || !bytecodeText.isBlank()) {
                throw new BaselineException(
                        "anchor without sourcePath cannot declare line/bytecode for " + id);
            }
            return new Anchor("", null, null);
        }
        Path path = Path.of(sourcePath);
        if (path.isAbsolute() || sourcePath.contains("\\") || !path.normalize().toString().replace('\\', '/').equals(sourcePath)) {
            throw new BaselineException("unsafe sourcePath for " + id + ": " + sourcePath);
        }
        Integer startLine = parseOptionalInteger(startLineText, 1, Integer.MAX_VALUE, "startLine", id);
        Integer bytecode = parseOptionalInteger(bytecodeText, 0, Integer.MAX_VALUE, "bytecode", id);
        return new Anchor(sourcePath, startLine, bytecode);
    }

    private static Suppression readSuppression(Element element, String id)
            throws BaselineException {
        requireOnlyAttributes(element, Set.of(), "suppression of " + id);
        Map<String, Element> children = uniqueDirectChildren(
                element, Set.of("Bug", "Class", "Method", "Field"), "suppression of " + id);
        Element bug = requiredChild(children, "Bug", id);
        Element classElement = requiredChild(children, "Class", id);
        requireOnlyAttributes(bug, Set.of("pattern"), "suppression Bug of " + id);
        requireOnlyAttributes(classElement, Set.of("name"), "suppression Class of " + id);
        String method = readOptionalSuppressionMember(children.get("Method"), "Method", id);
        String field = readOptionalSuppressionMember(children.get("Field"), "Field", id);
        if (method == null && field == null) {
            throw new BaselineException(
                    "suppression must contain an exact Method and/or Field for " + id);
        }
        return new Suppression(
                requiredAttribute(bug, "pattern", "suppression Bug of " + id),
                requiredAttribute(classElement, "name", "suppression Class of " + id),
                method,
                field);
    }

    private static String readOptionalSuppressionMember(Element element, String tag, String id)
            throws BaselineException {
        if (element == null) {
            return null;
        }
        requireOnlyAttributes(element, Set.of("name"), "suppression " + tag + " of " + id);
        return requiredAttribute(element, "name", "suppression " + tag + " of " + id);
    }

    private static void prepareProposalOutput(Path root, Path target)
            throws IOException, BaselineException {
        Path proposalRoot = root.resolve("target").toAbsolutePath().normalize();
        requireUnderRoot(proposalRoot, target, "SpotBugs proposal output");
        if (target.equals(proposalRoot)) {
            throw new BaselineException("SpotBugs proposal output must be a file under target/");
        }
        Files.deleteIfExists(target);
    }

    private static ProposalSummary writeProposal(
            Path root,
            Scope scope,
            Baseline baseline,
            Path target)
            throws Exception {
        List<ObservedFinding> observed = readModuleRawFindings(root, scope, baseline.engineVersion());
        Map<FindingKey, ObservedFinding> observedByKey = new LinkedHashMap<>();
        for (ObservedFinding finding : observed) {
            ObservedFinding previous = observedByKey.putIfAbsent(finding.key(), finding);
            if (previous != null) {
                throw new BaselineException(
                        "duplicate raw finding identity: " + finding.key().summary());
            }
        }
        Map<FindingKey, AcceptedFinding> acceptedByKey = new LinkedHashMap<>();
        for (AcceptedFinding finding : baseline.findings()) {
            acceptedByKey.put(finding.key(), finding);
        }

        Set<FindingKey> newFindings = new TreeSet<>(FindingKey.ORDER);
        newFindings.addAll(observedByKey.keySet());
        newFindings.removeAll(acceptedByKey.keySet());
        Set<FindingKey> staleAcceptances = new TreeSet<>(FindingKey.ORDER);
        staleAcceptances.addAll(acceptedByKey.keySet());
        staleAcceptances.removeAll(observedByKey.keySet());

        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "spotbugs-proposal-", ".xml");
        try {
            XMLOutputFactory factory = XMLOutputFactory.newFactory();
            try (OutputStream output = Files.newOutputStream(temporary)) {
                XMLStreamWriter writer = factory.createXMLStreamWriter(
                        output, StandardCharsets.UTF_8.name());
                writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
                writer.writeCharacters("\n");
                writer.writeStartElement(PROPOSAL_ROOT);
                writer.writeAttribute("schemaVersion", PROPOSAL_SCHEMA_VERSION);
                writer.writeAttribute("engineVersion", baseline.engineVersion());
                writer.writeAttribute("accepted", Integer.toString(baseline.findings().size()));
                writer.writeAttribute("observed", Integer.toString(observed.size()));
                writer.writeAttribute("new", Integer.toString(newFindings.size()));
                writer.writeAttribute("stale", Integer.toString(staleAcceptances.size()));
                writer.writeCharacters("\n");
                for (FindingKey key : newFindings) {
                    writeProposalCandidate(writer, observedByKey.get(key));
                }
                for (FindingKey key : staleAcceptances) {
                    writeStaleAcceptance(writer, acceptedByKey.get(key));
                }
                writer.writeEndElement();
                writer.writeCharacters("\n");
                writer.writeEndDocument();
                writer.close();
            }
            moveAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new ProposalSummary(newFindings.size(), staleAcceptances.size());
    }

    private static List<ObservedFinding> readModuleRawFindings(
            Path root,
            Scope scope,
            String engineVersion)
            throws Exception {
        List<ObservedFinding> findings = new ArrayList<>();
        for (String module : scope.analyzedModules().stream().sorted().toList()) {
            SpotBugsReport report = readReport(
                    root.resolve(module).resolve("target/spotbugs-raw/spotbugs-raw.xml"),
                    module,
                    engineVersion,
                    true);
            requireCleanAnalyzer(report, "raw module " + module);
            findings.addAll(report.findings());
        }
        return List.copyOf(findings);
    }

    private static void writeProposalCandidate(
            XMLStreamWriter writer,
            ObservedFinding finding)
            throws Exception {
        FindingKey key = finding.key();
        writer.writeCharacters("    ");
        writer.writeStartElement("candidate");
        writeAttribute(writer, "module", key.module());
        writeAttribute(writer, "type", key.type());
        writeAttribute(writer, "hash", key.hash());
        writeAttribute(writer, "occurrence", key.occurrence());
        writeAttribute(writer, "priority", key.priority());
        writeAttribute(writer, "rank", key.rank());
        writeAttribute(writer, "category", key.category());
        writer.writeCharacters("\n        ");
        writer.writeEmptyElement("class");
        writeAttribute(writer, "name", key.className());
        writer.writeCharacters("\n        ");
        writer.writeEmptyElement(key.memberKind() == MemberKind.METHOD ? "method" : "field");
        writeAttribute(writer, "name", key.memberName());
        writeAttribute(writer, "signature", key.signature());
        writer.writeCharacters("\n        ");
        writer.writeEmptyElement("anchor");
        if (!key.sourcePath().isBlank()) {
            writeAttribute(writer, "sourcePath", key.sourcePath());
        }
        if (finding.sourceLine() != null) {
            writeAttribute(writer, "startLine", finding.sourceLine());
        }
        if (key.bytecode() != null) {
            writeAttribute(writer, "bytecode", key.bytecode());
        }
        writer.writeCharacters("\n    ");
        writer.writeEndElement();
        writer.writeCharacters("\n");
    }

    private static void writeStaleAcceptance(
            XMLStreamWriter writer,
            AcceptedFinding finding)
            throws Exception {
        FindingKey key = finding.key();
        writer.writeCharacters("    ");
        writer.writeEmptyElement("stale-acceptance");
        writeAttribute(writer, "id", finding.id());
        writeAttribute(writer, "module", key.module());
        writeAttribute(writer, "type", key.type());
        writeAttribute(writer, "hash", key.hash());
        writeAttribute(writer, "occurrence", key.occurrence());
        writer.writeCharacters("\n");
    }

    private static void writeAttribute(
            XMLStreamWriter writer,
            String name,
            Object value)
            throws Exception {
        writer.writeAttribute(name, Objects.toString(value));
    }

    private static void writeGeneratedFilter(Path target, Baseline baseline)
            throws Exception {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "spotbugs-filter-", ".xml");
        try {
            XMLOutputFactory factory = XMLOutputFactory.newFactory();
            try (OutputStream output = Files.newOutputStream(temporary)) {
                XMLStreamWriter writer = factory.createXMLStreamWriter(output, StandardCharsets.UTF_8.name());
                writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
                writer.writeCharacters("\n");
                writer.writeStartElement("FindBugsFilter");
                writer.writeCharacters("\n");
                Map<Suppression, List<String>> selectors = new TreeMap<>(Suppression.ORDER);
                for (AcceptedFinding finding : baseline.findings()) {
                    selectors.computeIfAbsent(finding.suppression(), ignored -> new ArrayList<>())
                            .add(finding.id());
                }
                for (Map.Entry<Suppression, List<String>> entry : selectors.entrySet()) {
                    entry.getValue().sort(String::compareTo);
                    writer.writeCharacters("    ");
                    writer.writeComment(" accepted: " + String.join(", ", entry.getValue()) + " ");
                    writer.writeCharacters("\n    ");
                    writer.writeStartElement("Match");
                    writeFilterElement(writer, "Bug", "pattern", entry.getKey().bugType());
                    writeFilterElement(writer, "Class", "name", entry.getKey().className());
                    if (entry.getKey().methodName() != null) {
                        writeFilterElement(writer, "Method", "name", entry.getKey().methodName());
                    }
                    if (entry.getKey().fieldName() != null) {
                        writeFilterElement(writer, "Field", "name", entry.getKey().fieldName());
                    }
                    writer.writeCharacters("\n    ");
                    writer.writeEndElement();
                    writer.writeCharacters("\n");
                }
                writer.writeEndElement();
                writer.writeCharacters("\n");
                writer.writeEndDocument();
                writer.close();
            }
            moveAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeFilterElement(
            XMLStreamWriter writer, String element, String attribute, String value)
            throws Exception {
        writer.writeCharacters("\n        ");
        writer.writeEmptyElement(element);
        writer.writeAttribute(attribute, value);
    }

    private static void moveAtomically(Path source, Path target)
            throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verifyReports(Path root, Scope scope, Baseline baseline, Path reportModule)
            throws Exception {
        requireDirectory(reportModule, "SpotBugs report module");
        requireUnderRoot(root, reportModule, "SpotBugs report module");
        Path expectedReportModule = root.resolve(scope.aggregateModule()).toAbsolutePath().normalize();
        if (!reportModule.equals(expectedReportModule)) {
            throw new BaselineException(
                    "report module path must equal aggregate scope entry: expected "
                            + expectedReportModule + ", found " + reportModule);
        }
        List<String> problems = new ArrayList<>();
        List<ObservedFinding> moduleRawFindings = new ArrayList<>();

        for (String module : scope.analyzedModules().stream().sorted().toList()) {
            Path directory = root.resolve(module).resolve("target");
            try {
                SpotBugsReport raw = readReport(
                        directory.resolve("spotbugs-raw/spotbugs-raw.xml"),
                        module,
                        baseline.engineVersion(),
                        true);
                SpotBugsReport filtered = readReport(
                        directory.resolve("spotbugs/spotbugs.xml"),
                        module,
                        baseline.engineVersion(),
                        true);
                requireCleanAnalyzer(raw, "raw module " + module);
                requireCleanAnalyzer(filtered, "filtered module " + module);
                if (!filtered.findings().isEmpty()) {
                    throw new BaselineException(
                            "filtered report still contains " + filtered.findings().size()
                                    + " findings for " + module);
                }
                moduleRawFindings.addAll(raw.findings());
            } catch (Exception e) {
                problems.add(e.getMessage());
            }
        }

        for (String module : scope.excludedModules()) {
            Path directory = ROOT_PATH.equals(module) ? root : root.resolve(module);
            Path raw = directory.resolve("target/spotbugs-raw/spotbugs-raw.xml");
            if (Files.exists(raw)) {
                problems.add("excluded module produced raw SpotBugs report: " + raw);
            }
        }

        try {
            compareBaseline(baseline, moduleRawFindings);
        } catch (BaselineException e) {
            problems.add(e.getMessage());
        }

        try {
            SpotBugsReport rawAggregate = readReport(
                    reportModule.resolve("target/spotbugs-raw/spotbugs-raw.xml"),
                    "<aggregate>", baseline.engineVersion(), false);
            SpotBugsReport filteredAggregate = readReport(
                    reportModule.resolve("target/spotbugs/spotbugs.xml"),
                    "<aggregate>", baseline.engineVersion(), false);
            requireCleanAnalyzer(rawAggregate, "raw aggregate");
            requireCleanAnalyzer(filteredAggregate, "filtered aggregate");
            if (!filteredAggregate.findings().isEmpty()) {
                throw new BaselineException(
                        "filtered aggregate still contains "
                                + filteredAggregate.findings().size() + " findings");
            }
            compareAggregate(moduleRawFindings, rawAggregate.findings());
            requireNonEmptyFile(
                    reportModule.resolve("target/spotbugs-raw/spotbugs.html"),
                    "raw aggregate HTML");
        } catch (Exception e) {
            problems.add(e.getMessage());
        }

        if (!problems.isEmpty()) {
            throw new BaselineException(
                    "SpotBugs baseline/report verification failed:\n - "
                            + String.join("\n - ", problems));
        }
    }

    private static SpotBugsReport readReport(
            Path file, String module, String engineVersion, boolean requireVersion)
            throws Exception {
        requireNonEmptyFile(file, "SpotBugs XML for " + module);
        Element root = parseXml(file).getDocumentElement();
        if (!"BugCollection".equals(root.getTagName())) {
            throw new BaselineException(
                    "unexpected SpotBugs XML root for " + module + ": " + root.getTagName());
        }
        String reportVersion = root.getAttribute("version");
        if (requireVersion && reportVersion.isBlank()) {
            throw new BaselineException("SpotBugs XML has no engine version for " + module);
        }
        if (!reportVersion.isBlank() && !engineVersion.equals(reportVersion)) {
            throw new BaselineException(
                    "SpotBugs engine version drift for " + module + ": expected "
                            + engineVersion + ", found " + reportVersion);
        }

        Element errors = requiredDirectChild(root, "Errors", "SpotBugs XML for " + module);
        int errorCount = parseIntegerAttribute(errors, "errors", 0, Integer.MAX_VALUE, module);
        int missingClasses = parseIntegerAttribute(
                errors, "missingClasses", 0, Integer.MAX_VALUE, module);
        List<ObservedFinding> findings = new ArrayList<>();
        for (Element bug : directChildren(root, "BugInstance")) {
            findings.add(readObservedFinding(bug, module));
        }
        Element summary = requiredDirectChild(root, "FindBugsSummary", "SpotBugs XML for " + module);
        int totalBugs = parseIntegerAttribute(summary, "total_bugs", 0, Integer.MAX_VALUE, module);
        if (totalBugs != findings.size()) {
            throw new BaselineException(
                    "SpotBugs summary count mismatch for " + module + ": summary="
                            + totalBugs + ", instances=" + findings.size());
        }
        validateOccurrenceSequences(findings.stream().map(ObservedFinding::key).toList(), module);
        validateOccurrenceMax(findings, module);
        return new SpotBugsReport(errorCount, missingClasses, List.copyOf(findings));
    }

    private static ObservedFinding readObservedFinding(Element bug, String module)
            throws BaselineException {
        String type = requiredAttribute(bug, "type", "BugInstance");
        String hash = requiredAttribute(bug, "instanceHash", "BugInstance");
        int occurrence = parseIntegerAttribute(
                bug, "instanceOccurrenceNum", 0, Integer.MAX_VALUE, module);
        int occurrenceMax = parseIntegerAttribute(
                bug, "instanceOccurrenceMax", occurrence, Integer.MAX_VALUE, module);
        int priority = parseIntegerAttribute(bug, "priority", 1, 3, module);
        int rank = parseIntegerAttribute(bug, "rank", 1, 20, module);
        String category = requiredAttribute(bug, "category", "BugInstance");
        Element primaryClass = uniquePrimaryChild(bug, "Class", true, module);
        String className = requiredAttribute(primaryClass, "classname", "primary Class");
        Element primaryMethod = uniquePrimaryChild(bug, "Method", false, module);
        Element primaryField = uniquePrimaryChild(bug, "Field", false, module);
        if (primaryMethod == null && primaryField == null) {
            throw new BaselineException(
                    "BugInstance must have a primary Method or Field for "
                            + module + ":" + type + ":" + hash + "/" + occurrence);
        }
        // SpotBugs legitimately marks both annotations primary for findings such as
        // EI_EXPOSE_REP and VO_VOLATILE_INCREMENT. Method is the stable canonical
        // identity when present; field remains available to suppression matching.
        Element member = primaryMethod != null ? primaryMethod : primaryField;
        MemberKind kind = primaryMethod != null ? MemberKind.METHOD : MemberKind.FIELD;
        String memberName = requiredAttribute(member, "name", "primary member");
        String signature = requiredAttribute(member, "signature", "primary member");
        Element source = uniquePrimaryChild(bug, "SourceLine", false, module);
        String sourcePath = source == null ? "" : source.getAttribute("sourcepath");
        Integer line = source == null
                ? null
                : parseOptionalInteger(source.getAttribute("start"), 1, Integer.MAX_VALUE, "start", module);
        Integer bytecode = source == null
                ? null
                : parseOptionalInteger(
                        source.getAttribute("startBytecode"), 0, Integer.MAX_VALUE, "startBytecode", module);

        Set<String> methodNames = new LinkedHashSet<>();
        for (Element method : directChildren(bug, "Method")) {
            methodNames.add(requiredAttribute(method, "name", "Method annotation"));
        }
        Set<String> fieldNames = new LinkedHashSet<>();
        for (Element field : directChildren(bug, "Field")) {
            fieldNames.add(requiredAttribute(field, "name", "Field annotation"));
        }
        FindingKey key = new FindingKey(
                module,
                type,
                hash,
                occurrence,
                priority,
                rank,
                category,
                className,
                kind,
                memberName,
                signature,
                sourcePath,
                bytecode);
        return new ObservedFinding(
                key,
                occurrenceMax,
                line,
                Set.copyOf(methodNames),
                Set.copyOf(fieldNames));
    }

    private static Element uniquePrimaryChild(
            Element parent, String tag, boolean required, String subject)
            throws BaselineException {
        Element primary = null;
        for (Element child : directChildren(parent, tag)) {
            if (!"true".equals(child.getAttribute("primary"))) {
                continue;
            }
            if (primary != null) {
                throw new BaselineException("multiple primary " + tag + " elements for " + subject);
            }
            primary = child;
        }
        if (required && primary == null) {
            throw new BaselineException("missing primary " + tag + " for " + subject);
        }
        return primary;
    }

    private static void requireCleanAnalyzer(SpotBugsReport report, String subject)
            throws BaselineException {
        if (report.errors() != 0 || report.missingClasses() != 0) {
            throw new BaselineException(
                    subject + " reports analyzer errors=" + report.errors()
                            + ", missingClasses=" + report.missingClasses());
        }
    }

    private static void compareBaseline(Baseline baseline, List<ObservedFinding> actual)
            throws BaselineException {
        Map<FindingKey, AcceptedFinding> expectedByKey = new LinkedHashMap<>();
        for (AcceptedFinding finding : baseline.findings()) {
            expectedByKey.put(finding.key(), finding);
        }
        Map<FindingKey, ObservedFinding> actualByKey = new LinkedHashMap<>();
        for (ObservedFinding finding : actual) {
            ObservedFinding previous = actualByKey.putIfAbsent(finding.key(), finding);
            if (previous != null) {
                throw new BaselineException(
                        "duplicate raw finding identity: " + finding.key().summary());
            }
        }

        Set<FindingKey> newFindings = new TreeSet<>(FindingKey.ORDER);
        newFindings.addAll(actualByKey.keySet());
        newFindings.removeAll(expectedByKey.keySet());
        Set<FindingKey> stale = new TreeSet<>(FindingKey.ORDER);
        stale.addAll(expectedByKey.keySet());
        stale.removeAll(actualByKey.keySet());
        if (!newFindings.isEmpty() || !stale.isEmpty()) {
            throw new BaselineException(
                    "accepted baseline differs from raw findings; new="
                            + summaries(newFindings) + ", stale=" + summaries(stale));
        }

        for (AcceptedFinding accepted : baseline.findings()) {
            ObservedFinding observed = actualByKey.get(accepted.key());
            if (!accepted.suppression().matches(observed)) {
                throw new BaselineException(
                        "suppression selector for " + accepted.id()
                                + " does not match its exact raw finding");
            }
        }
    }

    private static List<String> summaries(Collection<FindingKey> keys) {
        return keys.stream().limit(10).map(FindingKey::summary).toList();
    }

    private static void compareAggregate(
            List<ObservedFinding> moduleFindings, List<ObservedFinding> aggregateFindings)
            throws BaselineException {
        Map<AggregateKey, Integer> expected = aggregateCounts(moduleFindings);
        Map<AggregateKey, Integer> actual = aggregateCounts(aggregateFindings);
        if (!expected.equals(actual)) {
            Set<AggregateKey> missing = new TreeSet<>(AggregateKey.ORDER);
            missing.addAll(expected.keySet());
            missing.removeAll(actual.keySet());
            Set<AggregateKey> unexpected = new TreeSet<>(AggregateKey.ORDER);
            unexpected.addAll(actual.keySet());
            unexpected.removeAll(expected.keySet());
            throw new BaselineException(
                    "raw aggregate differs from module raw union; expected="
                            + moduleFindings.size() + ", actual=" + aggregateFindings.size()
                            + ", missing=" + missing.stream().limit(10).toList()
                            + ", unexpected=" + unexpected.stream().limit(10).toList());
        }
    }

    private static Map<AggregateKey, Integer> aggregateCounts(List<ObservedFinding> findings) {
        Map<AggregateKey, Integer> counts = new HashMap<>();
        for (ObservedFinding finding : findings) {
            counts.merge(AggregateKey.from(finding.key()), 1, Integer::sum);
        }
        return counts;
    }

    private static void validateOccurrenceSequences(List<FindingKey> keys, String subject)
            throws BaselineException {
        Map<String, TreeSet<Integer>> occurrences = new HashMap<>();
        for (FindingKey key : keys) {
            occurrences.computeIfAbsent(
                    key.module() + "\u0000" + key.hash(), ignored -> new TreeSet<>())
                    .add(key.occurrence());
        }
        for (Map.Entry<String, TreeSet<Integer>> entry : occurrences.entrySet()) {
            int expected = 0;
            for (int occurrence : entry.getValue()) {
                if (occurrence != expected++) {
                    throw new BaselineException(
                            "non-contiguous instanceOccurrenceNum in " + subject + ": "
                                    + entry.getValue());
                }
            }
        }
    }

    private static void validateOccurrenceMax(List<ObservedFinding> findings, String subject)
            throws BaselineException {
        Map<String, List<ObservedFinding>> groups = new HashMap<>();
        for (ObservedFinding finding : findings) {
            groups.computeIfAbsent(
                    finding.key().module() + "\u0000" + finding.key().hash(),
                    ignored -> new ArrayList<>())
                    .add(finding);
        }
        for (List<ObservedFinding> group : groups.values()) {
            int expectedMax = group.size() - 1;
            for (ObservedFinding finding : group) {
                if (finding.occurrenceMax() != expectedMax) {
                    throw new BaselineException(
                            "instanceOccurrenceMax mismatch in " + subject + " for "
                                    + finding.key().hash() + ": expected " + expectedMax
                                    + ", found " + finding.occurrenceMax());
                }
            }
        }
    }

    private static Set<Suppression> uniqueSuppressions(List<AcceptedFinding> findings) {
        LinkedHashSet<Suppression> suppressions = new LinkedHashSet<>();
        for (AcceptedFinding finding : findings) {
            suppressions.add(finding.suppression());
        }
        return Set.copyOf(suppressions);
    }

    private static Document parseXml(Path file)
            throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (InputStream input = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Map<String, Element> uniqueDirectChildren(
            Element parent, Set<String> allowed, String subject)
            throws BaselineException {
        Map<String, Element> result = new LinkedHashMap<>();
        for (Element child : directChildElements(parent)) {
            if (!allowed.contains(child.getTagName())) {
                throw new BaselineException(
                        "unexpected <" + child.getTagName() + "> in " + subject);
            }
            if (result.putIfAbsent(child.getTagName(), child) != null) {
                throw new BaselineException(
                        "duplicate <" + child.getTagName() + "> in " + subject);
            }
        }
        return result;
    }

    private static List<Element> directChildElements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static List<Element> directChildren(Element parent, String tag) {
        return directChildElements(parent).stream()
                .filter(element -> tag.equals(element.getTagName()))
                .toList();
    }

    private static Element requiredDirectChild(Element parent, String tag, String subject)
            throws BaselineException {
        List<Element> elements = directChildren(parent, tag);
        if (elements.size() != 1) {
            throw new BaselineException(
                    subject + " must contain exactly one <" + tag + ">");
        }
        return elements.getFirst();
    }

    private static Element requiredChild(Map<String, Element> children, String tag, String id)
            throws BaselineException {
        Element element = children.get(tag);
        if (element == null) {
            throw new BaselineException("missing <" + tag + "> for accepted finding " + id);
        }
        return element;
    }

    private static void requireOnlyAttributes(Element element, Set<String> allowed, String subject)
            throws BaselineException {
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            String name = element.getAttributes().item(index).getNodeName();
            if (!allowed.contains(name)) {
                throw new BaselineException("unexpected attribute " + name + " in " + subject);
            }
        }
    }

    private static String requiredAttribute(Element element, String name, String subject)
            throws BaselineException {
        String value = element.getAttribute(name);
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new BaselineException("missing or invalid " + name + " in " + subject);
        }
        return value;
    }

    private static int requireInteger(
            Element element, String attribute, int minimum, int maximum, String id)
            throws BaselineException {
        return parseInteger(
                requiredAttribute(element, attribute, "finding " + id),
                minimum,
                maximum,
                attribute,
                id);
    }

    private static int parseIntegerAttribute(
            Element element, String attribute, int minimum, int maximum, String subject)
            throws BaselineException {
        return parseInteger(
                requiredAttribute(element, attribute, subject),
                minimum,
                maximum,
                attribute,
                subject);
    }

    private static Integer parseOptionalInteger(
            String value, int minimum, int maximum, String attribute, String subject)
            throws BaselineException {
        return value.isBlank() ? null : parseInteger(value, minimum, maximum, attribute, subject);
    }

    private static int parseInteger(
            String value, int minimum, int maximum, String attribute, String subject)
            throws BaselineException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BaselineException(
                    "invalid " + attribute + " for " + subject + ": " + value);
        }
    }

    private static String requireToken(String value, String field, String id)
            throws BaselineException {
        if (!TOKEN.matcher(value).matches()) {
            throw new BaselineException("invalid " + field + " for " + id + ": " + value);
        }
        return value;
    }

    private static String requireModulePath(String value, String subject)
            throws BaselineException {
        if (ROOT_PATH.equals(value)) {
            return value;
        }
        if (!MODULE_PATH.matcher(value).matches()) {
            throw new BaselineException("invalid module path in " + subject + ": " + value);
        }
        return value;
    }

    private static void requireValue(String subject, String expected, String actual)
            throws BaselineException {
        if (!expected.equals(actual)) {
            throw new BaselineException(subject + " must be " + expected + ", found " + actual);
        }
    }

    private static void requireDirectory(Path path, String subject)
            throws BaselineException {
        if (!Files.isDirectory(path)) {
            throw new BaselineException(subject + " does not exist: " + path);
        }
    }

    private static void requireFile(Path path, String subject)
            throws BaselineException {
        if (!Files.isRegularFile(path)) {
            throw new BaselineException(subject + " does not exist: " + path);
        }
    }

    private static void requireNonEmptyFile(Path path, String subject)
            throws IOException, BaselineException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) {
            throw new BaselineException(subject + " is missing or empty: " + path);
        }
    }

    private static void requireUnderRoot(Path root, Path path, String subject)
            throws BaselineException {
        if (root == null || !path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new BaselineException(subject + " escapes reactor root: " + path);
        }
    }

    private enum Mode {
        VALIDATE("validate"),
        VERIFY("verify"),
        PROPOSE("propose");

        private final String externalName;

        Mode(String externalName) {
            this.externalName = externalName;
        }

        static Mode parse(String value) throws BaselineException {
            for (Mode mode : values()) {
                if (mode.externalName.equals(value)) {
                    return mode;
                }
            }
            throw new BaselineException("unknown baseline verifier mode: " + value);
        }
    }

    private enum Disposition {
        FALSE_POSITIVE("false-positive"),
        POLICY_NOISE("policy-noise"),
        ACCEPTED_LEGACY("accepted-legacy");

        private final String externalName;

        Disposition(String externalName) {
            this.externalName = externalName;
        }

        static Disposition parse(String value, String id) throws BaselineException {
            for (Disposition disposition : values()) {
                if (disposition.externalName.equals(value)) {
                    return disposition;
                }
            }
            throw new BaselineException("invalid disposition for " + id + ": " + value);
        }
    }

    private enum MemberKind {
        METHOD,
        FIELD
    }

    private record Scope(
            Set<String> analyzedModules,
            Set<String> excludedModules,
            String aggregateModule) {
    }

    private record Baseline(
            String engineVersion,
            List<AcceptedFinding> findings) {
    }

    private record AcceptedFinding(
            String id,
            FindingKey key,
            Integer sourceLine,
            Disposition disposition,
            String owner,
            String evidence,
            String reviewTrigger,
            Suppression suppression,
            String rationale) {
    }

    private record Member(MemberKind kind, String name, String signature) {
    }

    private record Anchor(String sourcePath, Integer startLine, Integer bytecode) {
    }

    private record Suppression(
            String bugType,
            String className,
            String methodName,
            String fieldName) {

        private static final Comparator<Suppression> ORDER = Comparator
                .comparing(Suppression::bugType)
                .thenComparing(Suppression::className)
                .thenComparing(Suppression::methodName, Comparator.nullsFirst(String::compareTo))
                .thenComparing(Suppression::fieldName, Comparator.nullsFirst(String::compareTo));

        boolean matches(ObservedFinding finding) {
            return bugType.equals(finding.key().type())
                    && className.equals(finding.key().className())
                    && (methodName == null || finding.methodNames().contains(methodName))
                    && (fieldName == null || finding.fieldNames().contains(fieldName));
        }
    }

    private record FindingKey(
            String module,
            String type,
            String hash,
            int occurrence,
            int priority,
            int rank,
            String category,
            String className,
            MemberKind memberKind,
            String memberName,
            String signature,
            String sourcePath,
            Integer bytecode) {

        private static final Comparator<FindingKey> ORDER = Comparator
                .comparing(FindingKey::module)
                .thenComparing(FindingKey::type)
                .thenComparing(FindingKey::className)
                .thenComparing(FindingKey::memberName)
                .thenComparing(FindingKey::hash)
                .thenComparingInt(FindingKey::occurrence)
                .thenComparing(FindingKey::sourcePath)
                .thenComparing(FindingKey::bytecode, Comparator.nullsFirst(Integer::compareTo));

        String summary() {
            return module + ":" + type + ":" + className + "#" + memberName
                    + ":" + hash + "/" + occurrence
                    + (bytecode == null ? "" : "@" + bytecode);
        }
    }

    private record ObservedFinding(
            FindingKey key,
            int occurrenceMax,
            Integer sourceLine,
            Set<String> methodNames,
            Set<String> fieldNames) {
    }

    private record SpotBugsReport(
            int errors,
            int missingClasses,
            List<ObservedFinding> findings) {
    }

    private record ProposalSummary(int newFindings, int staleAcceptances) {
    }

    private record AggregateKey(
            String type,
            String hash,
            int occurrence,
            int priority,
            int rank,
            String category,
            String className,
            MemberKind memberKind,
            String memberName,
            String signature,
            String sourcePath,
            Integer bytecode) {

        private static final Comparator<AggregateKey> ORDER = Comparator
                .comparing(AggregateKey::type)
                .thenComparing(AggregateKey::className)
                .thenComparing(AggregateKey::memberName)
                .thenComparing(AggregateKey::hash)
                .thenComparingInt(AggregateKey::occurrence)
                .thenComparing(AggregateKey::bytecode, Comparator.nullsFirst(Integer::compareTo));

        static AggregateKey from(FindingKey key) {
            return new AggregateKey(
                    key.type(),
                    key.hash(),
                    key.occurrence(),
                    key.priority(),
                    key.rank(),
                    key.category(),
                    key.className(),
                    key.memberKind(),
                    key.memberName(),
                    key.signature(),
                    key.sourcePath(),
                    key.bytecode());
        }
    }

    private static final class BaselineException extends Exception {
        BaselineException(String message) {
            super(Objects.requireNonNullElse(message, "unknown baseline verification failure"));
        }
    }
}
