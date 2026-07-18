package com.iocextractor;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protects the reactor's Maven CI-friendly version contract. The root
 * {@code revision} property is the only editable product version; child parent
 * declarations use {@code ${revision}}, while managed reactor dependencies
 * follow the effective {@code ${project.version}}.
 */
class MavenVersionConventionTest {

    private static final String REVISION_EXPRESSION = "${revision}";
    private static final String PROJECT_VERSION_EXPRESSION = "${project.version}";
    private static final String PROJECT_GROUP = "com.iocextractor";
    private static final String SEMVER =
            "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?";

    @Test
    void root_uses_one_ci_friendly_product_version() {
        Element project = parsePom(reactorRoot().resolve("pom.xml"));
        Element properties = directChild(project, "properties");
        List<Element> revisions = directChildren(properties, "revision");

        assertThat(text(project, "version"))
                .as("root project version")
                .isEqualTo(REVISION_EXPRESSION);
        assertThat(revisions)
                .as("root revision properties")
                .singleElement()
                .satisfies(revision -> assertThat(revision.getTextContent().trim())
                        .as("single editable product version in root properties")
                        .matches(SEMVER));
    }

    @Test
    void child_modules_reference_the_ci_friendly_parent_version() {
        Path root = reactorRoot();
        List<String> violations = modulePomPaths(root).stream()
                .map(pom -> new VersionDeclaration(
                        root.relativize(pom).toString(),
                        text(directChild(parsePom(pom), "parent"), "version")))
                .filter(declaration -> !REVISION_EXPRESSION.equals(declaration.version()))
                .map(declaration -> declaration.pom() + " -> " + declaration.version())
                .toList();

        assertThat(violations)
                .as("child parent versions must use ${revision}")
                .isEmpty();
    }

    @Test
    void managed_reactor_dependencies_follow_the_effective_project_version() {
        Element project = parsePom(reactorRoot().resolve("pom.xml"));
        Element dependencies = directChild(directChild(project, "dependencyManagement"), "dependencies");
        List<Element> internalDependencies = directChildren(dependencies, "dependency").stream()
                .filter(dependency -> PROJECT_GROUP.equals(text(dependency, "groupId")))
                .toList();
        List<String> violations = internalDependencies.stream()
                .filter(dependency -> !PROJECT_VERSION_EXPRESSION.equals(text(dependency, "version")))
                .map(dependency -> text(dependency, "artifactId") + " -> " + text(dependency, "version"))
                .toList();

        assertThat(internalDependencies)
                .as("managed reactor dependencies")
                .isNotEmpty();
        assertThat(violations)
                .as("managed reactor dependency versions must use ${project.version}")
                .isEmpty();
    }

    private static List<Path> modulePomPaths(Path root) {
        Element project = parsePom(root.resolve("pom.xml"));
        Element modules = directChild(project, "modules");
        return directChildren(modules, "module").stream()
                .map(module -> root.resolve(module.getTextContent().trim()).resolve("pom.xml"))
                .toList();
    }

    private static Element parsePom(Path pom) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            return document.getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("Cannot parse Maven POM " + pom, e);
        }
    }

    private static String text(Element parent, String childName) {
        return directChild(parent, childName).getTextContent().trim();
    }

    private static Element directChild(Element parent, String childName) {
        return directChildren(parent, childName).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing <" + childName + "> below <" + parent.getLocalName() + ">"));
    }

    private static List<Element> directChildren(Element parent, String childName) {
        NodeList nodes = parent.getChildNodes();
        List<Element> children = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element child && childName.equals(child.getLocalName())) {
                children.add(child);
            }
        }
        return children;
    }

    /** Walks up from the test working directory to the Maven reactor root. */
    private static Path reactorRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path pom = directory.resolve("pom.xml");
            if (Files.isRegularFile(pom)
                    && Files.isDirectory(directory.resolve("platform"))
                    && Files.isDirectory(directory.resolve("core"))
                    && Files.isDirectory(directory.resolve("adapters"))
                    && Files.isDirectory(directory.resolve("bootstrap"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("reactor root not found from " + Path.of("").toAbsolutePath());
    }

    private record VersionDeclaration(String pom, String version) {
    }
}
