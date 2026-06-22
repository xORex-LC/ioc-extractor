package com.iocextractor.adapter.in.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health";
    }

    private static int run(String[] args, StringBuilder out) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new HealthCommand("127.0.0.1", "8081")).execute(args);
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
        assertThat(out.toString()).contains("STATUS").contains("aggregation").contains("sourcesProcessed=3");
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
    void exits_2_when_daemon_unreachable() throws Exception {
        // Start then immediately stop to obtain a definitely-closed port.
        String url = start(200, "{}");
        server.stop(0);
        server = null;
        StringBuilder out = new StringBuilder();

        int code = run(new String[]{"--url", url, "--timeout", "1"}, out);

        assertThat(code).isEqualTo(2);
    }
}
