package com.iocextractor.adapter.in.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Inbound (driving) adapter: the {@code health} CLI command. Queries the running
 * daemon's actuator health endpoint over loopback and renders it as a table (or
 * raw JSON with {@code --json}). The process exit code mirrors health, so it
 * doubles as a scriptable probe: {@code 0} UP, {@code 1} DOWN, {@code 2} unreachable.
 *
 * <p>This talks to a separate process (the daemon); it does not read in-process
 * state. Host/port default to the same {@code server.*} config the daemon binds.
 */
@Component
@Command(
        name = "health",
        mixinStandardHelpOptions = true,
        description = "Show the running daemon's health (queries its actuator endpoint).")
public final class HealthCommand implements Callable<Integer> {

    private static final int EXIT_UP = 0;
    private static final int EXIT_DOWN = 1;
    private static final int EXIT_UNREACHABLE = 2;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String defaultHost;
    private final String defaultPort;

    @Option(names = "--host", description = "Daemon host (default: server.address or 127.0.0.1).")
    private String host;

    @Option(names = "--port", description = "Daemon port (default: server.port or 8081).")
    private Integer port;

    @Option(names = "--url", description = "Full health URL; overrides --host/--port.")
    private String url;

    @Option(names = "--timeout", description = "Request timeout in seconds (default: 5).")
    private int timeoutSeconds = 5;

    @Option(names = "--json", description = "Print the raw health JSON instead of a table.")
    private boolean json;

    public HealthCommand(@Value("${server.address:127.0.0.1}") String defaultHost,
                         @Value("${server.port:8081}") String defaultPort) {
        this.defaultHost = defaultHost;
        this.defaultPort = defaultPort;
    }

    @Override
    public Integer call() {
        String endpoint = resolveEndpoint();
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(endpoint))
                                    .timeout(Duration.ofSeconds(timeoutSeconds))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            System.err.println("daemon unreachable at " + endpoint + " (is it running?)");
            return EXIT_UNREACHABLE;
        } catch (Exception e) {
            System.err.println("failed to query " + endpoint + ": " + e.getMessage());
            return EXIT_UNREACHABLE;
        }

        Map<String, Object> health = parse(response.body());
        if (health == null || health.get("status") == null) {
            System.err.println("unexpected response from " + endpoint + " (HTTP " + response.statusCode() + ")");
            return EXIT_UNREACHABLE;
        }

        if (json) {
            System.out.println(prettyOrRaw(response.body()));
        } else {
            renderTable(endpoint, health);
        }
        return "UP".equals(String.valueOf(health.get("status"))) ? EXIT_UP : EXIT_DOWN;
    }

    private String resolveEndpoint() {
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        String h = host != null && !host.isBlank() ? host.trim() : defaultHost;
        String p = port != null ? String.valueOf(port) : defaultPort;
        return "http://" + h + ":" + p + "/actuator/health";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String body) {
        try {
            return mapper.readValue(body, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String prettyOrRaw(String body) {
        try {
            Object tree = mapper.readValue(body, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (Exception e) {
            return body;
        }
    }

    @SuppressWarnings("unchecked")
    private void renderTable(String endpoint, Map<String, Object> health) {
        String overall = String.valueOf(health.get("status"));
        System.out.println(Ansi.AUTO.string("ioc-extractor @ " + endpoint));
        System.out.println();
        System.out.println("  STATUS  " + badge(overall));

        Object componentsRaw = health.get("components");
        if (!(componentsRaw instanceof Map)) {
            return;
        }
        Map<String, Object> components = (Map<String, Object>) componentsRaw;
        int nameWidth = Math.max("COMPONENT".length(),
                components.keySet().stream().mapToInt(String::length).max().orElse(9));

        System.out.println();
        System.out.printf("  %-" + nameWidth + "s  %-8s  %s%n", "COMPONENT", "STATUS", "DETAILS");
        components.forEach((name, value) -> {
            Map<String, Object> component = value instanceof Map ? (Map<String, Object>) value : Map.of();
            String status = String.valueOf(component.getOrDefault("status", "UNKNOWN"));
            System.out.printf("  %-" + nameWidth + "s  %s  %s%n",
                    name, badgePadded(status), details(component.get("details")));
        });
    }

    private String details(Object detailsRaw) {
        if (!(detailsRaw instanceof Map<?, ?> details) || details.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        details.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    /** Colored badge: green for UP, red otherwise. Auto-disabled on non-TTY. */
    private String badge(String status) {
        String marker = "● " + status;
        return Ansi.AUTO.string("UP".equals(status) ? "@|bold,green " + marker + "|@" : "@|bold,red " + marker + "|@");
    }

    /** Badge padded to a stable visual width (8) ignoring ANSI codes. */
    private String badgePadded(String status) {
        String plain = "● " + status;
        String padding = " ".repeat(Math.max(0, 8 - plain.length()));
        return badge(status) + padding;
    }
}
