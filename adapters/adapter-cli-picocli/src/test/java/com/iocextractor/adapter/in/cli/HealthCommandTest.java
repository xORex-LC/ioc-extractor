package com.iocextractor.adapter.in.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HealthCommandTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(int status, String body) throws Exception {
        return start(status, body, new AtomicReference<>());
    }

    private String start(int status, String body, AtomicReference<String> requestedPath) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health";
    }

    private static int run(String[] args, StringBuilder out) {
        return run(new HealthCommand("127.0.0.1", "8081"), args, out);
    }

    private static int run(HealthCommand command, String[] args, StringBuilder out) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int code = new CommandLine(command).execute(args);
            out.append(buffer.toString(StandardCharsets.UTF_8));
            return code;
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void exits_0_and_renders_table_when_up() throws Exception {
        String url = start(200, "{\"status\":\"UP\",\"components\":{"
                + "\"aggregation\":{\"status\":\"UP\",\"details\":{\"sourcesProcessed\":3}}}}");
        StringBuilder out = new StringBuilder();

        int code = run(new String[]{"--url", url}, out);

        assertThat(code).isZero();
        assertThat(out.toString()).contains("status").contains("aggregation").contains("sourcesProcessed  3");
    }

    @Test
    void exits_1_when_down() throws Exception {
        String url = start(503, "{\"status\":\"DOWN\",\"components\":{"
                + "\"ingestionLedger\":{\"status\":\"DOWN\",\"details\":{\"error\":\"boom\"}}}}");
        StringBuilder out = new StringBuilder();

        int code = run(new String[]{"--url", url}, out);

        assertThat(code).isEqualTo(1);
        assertThat(out.toString()).contains("ingestionLedger");
    }

    @Test
    void json_flag_prints_raw_body() throws Exception {
        String url = start(200, "{\"status\":\"UP\",\"components\":{\"ping\":{\"status\":\"UP\"}}}");
        StringBuilder out = new StringBuilder();

        int code = run(new String[]{"--url", url, "--json"}, out);

        assertThat(code).isZero();
        assertThat(out.toString()).contains("\"components\"").contains("\"ping\"");
    }

    @Test
    void renders_nested_details_as_readable_tree() throws Exception {
        String url = start(200, "{\"status\":\"UP\",\"components\":{\"sync\":{\"status\":\"UP\",\"details\":{"
                + "\"remoteChangeWatch\":{\"incoming-ioc\":{\"status\":\"ACTIVE\",\"signals\":1}},"
                + "\"publishPending\":0}}}}");
        StringBuilder out = new StringBuilder();

        int code = run(new String[]{"--url", url}, out);

        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("sync")
                .contains("remoteChangeWatch")
                .contains("incoming-ioc")
                .contains("status   ACTIVE")
                .contains("signals  1")
                .contains("publishPending")
                .doesNotContain("remoteChangeWatch={");
    }

    @Test
    void component_option_queries_actuator_component_and_renders_top_level_details() throws Exception {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        String url = start(200, "{\"status\":\"UP\",\"details\":{"
                + "\"remoteChangeWatch\":{\"incoming-ioc\":{\"status\":\"ACTIVE\",\"signals\":1}}}}", requestedPath);
        StringBuilder out = new StringBuilder();

        int code = run(new String[]{"--url", url, "--component", "sync"}, out);

        assertThat(code).isZero();
        assertThat(requestedPath).hasValue("/actuator/health/sync");
        assertThat(out.toString())
                .contains("remoteChangeWatch")
                .contains("incoming-ioc")
                .contains("signals  1");
    }

    @Test
    void resolves_default_and_trimmed_host_port_endpoints() throws Exception {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        start(200, "{\"status\":\"UP\"}", requestedPath);
        int serverPort = server.getAddress().getPort();
        StringBuilder defaultOut = new StringBuilder();
        StringBuilder overrideOut = new StringBuilder();

        int defaultCode = run(
                new HealthCommand("127.0.0.1", String.valueOf(serverPort)),
                new String[]{"--component", " "},
                defaultOut);
        int overrideCode = run(
                new String[]{"--url", " ", "--host", " 127.0.0.1 ", "--port", String.valueOf(serverPort)},
                overrideOut);

        assertThat(defaultCode).isZero();
        assertThat(overrideCode).isZero();
        assertThat(requestedPath).hasValue("/actuator/health");
        assertThat(defaultOut).contains("http://127.0.0.1:" + serverPort + "/actuator/health");
        assertThat(overrideOut).contains("http://127.0.0.1:" + serverPort + "/actuator/health");
    }

    @Test
    void rejects_malformed_and_statusless_actuator_responses() throws Exception {
        String malformedUrl = start(502, "{not-json");
        StringBuilder malformedOut = new StringBuilder();

        int malformedCode = run(new String[]{"--url", malformedUrl}, malformedOut);

        server.stop(0);
        server = null;
        String statuslessUrl = start(200, "{\"details\":{}}");
        StringBuilder statuslessOut = new StringBuilder();

        int statuslessCode = run(new String[]{"--url", statuslessUrl}, statuslessOut);

        assertThat(malformedCode).isEqualTo(2);
        assertThat(statuslessCode).isEqualTo(2);
        assertThat(malformedOut).isEmpty();
        assertThat(statuslessOut).isEmpty();
    }

    @Test
    void renders_empty_and_nested_collection_details_and_unknown_components() throws Exception {
        String url = start(200, "{\"status\":\"UP\",\"components\":{"
                + "\"emptyMap\":{\"status\":\"UP\",\"details\":{}},"
                + "\"emptyList\":{\"status\":\"UP\",\"details\":[]},"
                + "\"tree\":{\"status\":\"UP\",\"details\":{"
                + "\"values\":[\"one\",{\"nested\":\"two\"},[\"three\"]],"
                + "\"emptyValues\":[],\"emptyObject\":{}}},"
                + "\"plain\":\"not-a-component\"}}");
        StringBuilder out = new StringBuilder();

        int code = run(new String[]{"--url", url}, out);

        assertThat(code).isZero();
        assertThat(out)
                .contains("emptyMap", "{}", "emptyList", "[]", "tree", "values")
                .contains("- one", "nested  two", "- three")
                .contains("plain", "UNKNOWN");
    }

    @Test
    void reports_invalid_request_uri_as_unreachable() {
        StringBuilder out = new StringBuilder();

        int code = run(new String[]{"--url", ":::"}, out);

        assertThat(code).isEqualTo(2);
        assertThat(out).isEmpty();
    }

    @Test
    void exits_2_when_daemon_unreachable() throws Exception {
        // Start then immediately stop to obtain a definitely-closed port.
        String url = start(200, "{}");
        server.stop(0);
        server = null;
        StringBuilder out = new StringBuilder();

        int code = run(new String[]{"--url", url, "--timeout", "1"}, out);

        assertThat(code).isEqualTo(2);
    }

    @Test
    void malformedJsonUsesNarrowFailSoftFallbacks() {
        var command = new HealthCommand("127.0.0.1", "8081");

        assertThat(command.parse("{not-json")).isNull();
        assertThat(command.prettyOrRaw("{not-json")).isEqualTo("{not-json");
    }
}
