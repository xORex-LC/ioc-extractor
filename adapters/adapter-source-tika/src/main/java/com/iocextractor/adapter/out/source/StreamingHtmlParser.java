package com.iocextractor.adapter.out.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractEncodingDetectorParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.DefaultHtmlMapper;
import org.apache.tika.parser.html.HtmlMapper;
import org.apache.tika.sax.XHTMLContentHandler;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.StreamParser;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/** Progressive HTML5 DOM pruning with an admitted limit between disposable subtrees. */
final class StreamingHtmlParser extends AbstractEncodingDetectorParser {
    private static final long serialVersionUID = 1L;
    // Repairs in these regions can move text before earlier siblings. Emit the whole region.
    private static final Set<String> REPAIR_REGIONS = Set.of("table", "b", "i", "a", "strong", "em", "font", "template");
    private final int maximumRegionCharacters;

    StreamingHtmlParser(int maximumRegionCharacters) { this.maximumRegionCharacters = maximumRegionCharacters; }
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return Set.of(MediaType.text("html"), MediaType.application("xhtml+xml"),
                MediaType.application("vnd.wap.xhtml+xml"), MediaType.application("x-asp"));
    }
    public void parse(InputStream input, ContentHandler handler, Metadata metadata, ParseContext context)
            throws IOException, SAXException {
        EncodingDetector detector = context.get(EncodingDetector.class, getEncodingDetector());
        var charset = detector.detect(input, metadata);
        if (charset == null) { charset = StandardCharsets.US_ASCII; }
        try (var reader = new RegionReader(new InputStreamReader(input, charset), maximumRegionCharacters);
             var parser = new StreamParser(htmlParser()).parse(reader, "")) {
            var output = new HtmlText(handler, metadata);
            output.start();
            var opened = new ArrayList<Element>();
            var iterator = parser.iterator();
            while (iterator.hasNext()) {
                var element = iterator.next();
                validateRegion(element);
                if (insideRepairRegion(element)) { continue; }
                emitCompleted(element, opened, output);
                reader.progress();
            }
            var document = parser.document();
            // StreamParser emits parents closed at EOF too; their start/end pairs are already balanced.
            emitChildren(document, output);
            output.finish();
        }
    }
    private static org.jsoup.parser.Parser htmlParser() throws IOException {
        var tags = org.jsoup.parser.TagSet.Html();
        // Tika's existing HTML parser permits the XML-style empty form for these tags.
        try (var input = org.apache.tika.parser.html.JSoupParser.class.getResourceAsStream("self-closeable-tags.txt");
             var lines = new java.io.BufferedReader(new InputStreamReader(
                     java.util.Objects.requireNonNull(input, "Tika self-closeable tags"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) { continue; }
                tags.valueOf(name, org.jsoup.parser.Parser.NamespaceHtml).set(org.jsoup.parser.Tag.SelfClose);
            }
        }
        return org.jsoup.parser.Parser.htmlParser().tagSet(tags).setMaxDepth(257);
    }
    private void validateRegion(Element element) throws SAXException {
        int ancestors = 0;
        for (Element parent = element; parent != null; parent = parent.parent()) {
            if (++ancestors > 256) { throw new SAXException("HTML nesting exceeds admitted parser working limit"); }
        }
        long characters = 0;
        int nodes = 0;
        var iterator = element.nodeStream().iterator();
        while (iterator.hasNext()) {
            var node = iterator.next();
            if (++nodes > maximumRegionCharacters / 4 + 1) {
                throw new SAXException("HTML node count exceeds admitted parser working limit");
            }
            characters += retainedCharacters(node);
            if (characters > maximumRegionCharacters) {
                throw new SAXException("HTML region exceeds admitted parser working limit");
            }
        }
    }
    private static long retainedCharacters(Node node) {
        if (node instanceof TextNode text) { return text.getWholeText().length(); }
        if (node instanceof DataNode data) { return data.getWholeData().length(); }
        long characters = 0;
        if (node instanceof Element element) {
            for (var attribute : element.attributes()) { characters += attribute.getKey().length() + attribute.getValue().length(); }
        }
        return characters;
    }
    private static boolean insideRepairRegion(Element element) {
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            if (REPAIR_REGIONS.contains(parent.normalName())) { return true; }
        }
        return false;
    }
    private static void emitCompleted(Element element, List<Element> opened, HtmlText output) throws SAXException {
        var ancestors = new ArrayList<Element>();
        for (Element parent = element.parent(); parent != null && !(parent instanceof Document); parent = parent.parent()) {
            ancestors.addFirst(parent);
        }
        int common = 0;
        while (common < opened.size() && common < ancestors.size() && opened.get(common) == ancestors.get(common)) { common++; }
        if (common < opened.size() && opened.get(common) != element) {
            throw new SAXException("HTML repair changed an already emitted ancestor");
        }
        for (int index = common; index < ancestors.size(); index++) {
            var ancestor = ancestors.get(index);
            emitBefore(ancestor.parent(), ancestor, output);
            output.begin(ancestor); opened.add(ancestor);
        }
        if (opened.isEmpty() || opened.getLast() != element) {
            emitBefore(element.parent(), element, output); output.begin(element);
        } else { opened.removeLast(); }
        emitChildren(element, output); output.end(element); element.remove();
    }
    private static void emitBefore(Node parent, Node child, HtmlText output) throws SAXException {
        if (parent == null) { return; }
        Node previous;
        // Each caller supplies child.parent(): the child remains until its preceding siblings are removed.
        while ((previous = parent.firstChild()) != child) {
            emitNode(previous, output); previous.remove();
        }
    }
    private static void emitChildren(Node parent, HtmlText output) throws SAXException {
        Node child;
        while ((child = parent.firstChild()) != null) { emitNode(child, output); child.remove(); }
    }
    private static void emitNode(Node node, HtmlText output) throws SAXException {
        if (node instanceof Element element) {
            output.begin(element); emitChildren(element, output); output.end(element);
        } else if (node instanceof TextNode text) { output.characters(text.getWholeText()); }
        else if (node instanceof DataNode data) { output.characters(data.getWholeData()); }
    }
    static final class RegionReader extends Reader {
        private final Reader reader;
        private final int maximum;
        private int sinceProgress;
        RegionReader(Reader reader, int maximum) { this.reader = reader; this.maximum = maximum; }
        public int read(char[] chars, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, chars.length);
            if (Thread.currentThread().isInterrupted()) { throw new IOException("HTML parsing interrupted"); }
            int allowance = maximum - sinceProgress;
            if (length == 0) { return 0; }
            if (allowance <= 0) {
                if (reader.read() == -1) { return -1; }
                throw new IOException("HTML region exceeds admitted parser working limit");
            }
            int read = reader.read(chars, offset, Math.min(length, allowance));
            if (read > 0) { sinceProgress += read; }
            return read;
        }
        void progress() { sinceProgress = 0; }
        public void close() throws IOException { reader.close(); }
    }
    private static final class HtmlText {
        private final XHTMLContentHandler output;
        private final HtmlMapper mapper = DefaultHtmlMapper.INSTANCE;
        private int bodyDepth;
        private int discardDepth;
        private HtmlText(ContentHandler handler, Metadata metadata) { output = new XHTMLContentHandler(handler, metadata); }
        private void start() throws SAXException { output.startDocument(); }
        private void finish() throws SAXException { output.endDocument(); }
        private void begin(Element element) throws SAXException {
            String name = element.normalName().toUpperCase(java.util.Locale.ROOT);
            if (name.equals("BODY") || name.equals("FRAMESET") || bodyDepth > 0) { bodyDepth++; }
            if (mapper.isDiscardElement(name) || discardDepth > 0) { discardDepth++; }
            if (element.hasAttr("srcdoc") || element.attr("src").stripLeading().regionMatches(true, 0, "data:", 0, 5)) {
                throw new SAXException("Embedded HTML attributes require a bounded separate document");
            }
            if (bodyDepth > 0 && discardDepth == 0) {
                String safe = mapper.mapSafeElement(name);
                if (safe != null) {
                    output.startElement(safe, attributes(element, safe));
                }
            }
        }
        private AttributesImpl attributes(Element element, String safe) {
            var attrs = new AttributesImpl();
            for (var attribute : element.attributes()) {
                String mapped = mapper.mapSafeAttribute(safe, attribute.getKey());
                if (mapped != null) { attrs.addAttribute("", mapped, mapped, "CDATA", attribute.getValue()); }
            }
            if (safe.equals("img") && attrs.getValue("alt") == null) { attrs.addAttribute("", "alt", "alt", "CDATA", ""); }
            return attrs;
        }
        private void end(Element element) throws SAXException {
            String name = element.normalName().toUpperCase(java.util.Locale.ROOT);
            if (bodyDepth > 0 && discardDepth == 0) {
                String safe = mapper.mapSafeElement(name);
                if (safe != null) { output.endElement(safe); }
                else if (XHTMLContentHandler.ENDLINE.contains(element.normalName())) { output.newline(); }
            }
            if (bodyDepth > 0) { bodyDepth--; }
            if (discardDepth > 0) { discardDepth--; }
        }
        private void characters(String text) throws SAXException {
            if (bodyDepth > 0 && discardDepth == 0) { char[] chars = text.toCharArray(); output.characters(chars, 0, chars.length); }
        }
    }
}
