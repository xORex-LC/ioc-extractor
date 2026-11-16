> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0010-health-actuator.md) (relative link addresses rebased on relocation).

<a id="0010--health--actuator-http-контур-демона"></a>
# 0010 — Daemon Health / Actuator HTTP boundary

<a id="статус"></a>
## Status

Implemented (ING-3). Resolves “health indicators registered but not exposed
(`web-application-type: none`)”. Also lays the first foundation for a web driving
adapter (ING-8).

<a id="контекст"></a>
## Context

The daemon has three `HealthIndicator`s (`AggregationHealthIndicator`,
`ArtifactStorageHealthIndicator`, `IngestionLedgerHealthIndicator`) and
`spring-boot-starter-actuator` on the classpath, but started as non-web
(`spring.main.web-application-type=none`). Therefore no HTTP server existed and
**nothing queried** the indicators. External systems (systemd/monitoring) could
not inspect daemon state.

Topology constraint: **oneshot/CLI must not start a web server**. It is a short-lived
command (`extract … → exit`); a server would interfere and keep the process alive.
Thus web is needed **exclusively in daemon mode**.

Another complication: mode is set by `ioc.runtime.mode`, **not** a Spring profile.
The systemd unit passes only `--ioc.runtime.mode=daemon` (the observability `daemon`
profile may differ). Gating web by profile is unreliable.

<a id="решения"></a>
## Decisions

**1. Dependency.** Added `spring-boot-starter-web` (Tomcat). The default
`spring.main.web-application-type=none` in `application.yml` keeps oneshot/CLI
non-web even with web on the classpath.

**2. Gate on `runtime.mode` through `EnvironmentPostProcessor`.**
`DaemonWebEnvironmentPostProcessor` (registered in `META-INF/spring.factories`)
adds a property source with `spring.main.web-application-type=servlet` when
`ioc.runtime.mode=daemon`.
- This works because `spring.main.*` binds from the environment **after** EPP;
  command-line `runtime.mode` is visible to EPP (the command-line property source
  is added before EPP).
- `Ordered.LOWEST_PRECEDENCE` also sees `runtime.mode` set in `application.yml`
  (after ConfigData processing).
- Rejected: Spring-profile gate (system unit does not set a profile); setting the
  type programmatically in `main()` (sees args, not YAML configuration).

**3. Surface and security.** Actuator uses the **main** server, not a separate
management port (which would still bring up the main server). Defaults:
- `server.address=127.0.0.1`, `server.port=8081`: **loopback bind**;
- `management.endpoints.web.exposure.include=health,info`, `health.show-details=always`;
- **no** `shutdown` or other mutating endpoints.

**4. Small Tomcat pool.** `server.tomcat.threads.max=8`, `min-spare=1`,
`accept-count=10`: the server serves only `/actuator/**`; the default 200 threads
would waste memory and collide with systemd `TasksMax`/`MemoryMax`.

**5. systemd hardening for the network surface.** The unit explicitly sets
`RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6`; `server.address` keeps Actuator
bound to loopback. After implementation of 0011, generic `IPAddressDeny=any` was
removed: the daemon must allow operator-configured outbound SMB connections
(usually TCP/445), including DNS. Capabilities remain unnecessary: Actuator uses
unprivileged port 8081, and SMB uses an ordinary outbound TCP socket. A host-specific
egress allowlist remains the responsibility of a firewall/systemd drop-in.

<a id="следствия"></a>
## Consequences

- Files: `DaemonWebEnvironmentPostProcessor` + `.imports`, `server`/`management`
  sections in `application.yml`, `spring-boot-starter-web` in
  `bootstrap/ioc-app/pom.xml`, hardening in `packaging/templates/ioc-extractor.service`.
- Tests: oneshot contexts (`ApplicationContextTest`, `GoldenPipelineTest`,
  `DaemonRuntimeModeTest`) pin `webEnvironment=NONE`; `DaemonManagementEndpointTest`
  (RANDOM_PORT) actually calls `/actuator/health`; `DaemonWebEnvironmentPostProcessorTest`
  checks gating.
- **Test nuance:** `@SpringBootTest` inline `properties` apply **after** EPP, so
  `DaemonManagementEndpointTest` explicitly enables web with
  `spring.main.web-application-type=servlet` in test properties. Real launches
  do not need this (mode comes from args). A separate EPP unit test covers gating.
- On daemon startup, `APP_START` logs the health address (`ApplicationLifecycleLogger`).

<a id="как-смотреть-индикаторы"></a>
## Inspecting indicators

The server listens **only on loopback**, so inspect it from the same host (or
through an SSH tunnel). Default port: `8081` (`server.port`).

**Through CLI (recommended):** the same binary's `health` subcommand renders a
component table and obtains its address from configuration (`server.*`); its exit
code reflects state (`0` UP, `1` DOWN, `2` daemon unreachable), making it a probe:
```bash
ioc health                 # цветная таблица компонентов
ioc health --json          # сырой JSON для скриптов
ioc health --host h --port 9090   # или --url http://…/actuator/health
```
Implementation: `HealthCommand` (picocli), HTTP through JDK `HttpClient`, no web
stack on the CLI side. Command Javadoc describes rendering.

**Through curl** (if CLI is unavailable), aggregate health (details: `show-details: always`):
```bash
curl -s http://127.0.0.1:8081/actuator/health | jq
```
```json
{
  "status": "UP",
  "components": {
    "aggregation":     { "status": "UP", "details": { "updatedAt": "…", "sourcesProcessed": 3 } },
    "artifactStorage": { "status": "UP", "details": { "partitionsDir": "…" } },
    "ingestionLedger": { "status": "UP", "details": { "ledgerDir": "…" } },
    "diskSpace":       { "status": "UP" },
    "ping":            { "status": "UP" }
  }
}
```

**Individual component** (name = bean minus the `HealthIndicator` suffix):
```bash
curl -s http://127.0.0.1:8081/actuator/health/aggregation     | jq
curl -s http://127.0.0.1:8081/actuator/health/artifactStorage | jq
curl -s http://127.0.0.1:8081/actuator/health/ingestionLedger | jq
```

| Component | Bean | UP when | DOWN when |
|---|---|---|---|
| `aggregation` | `AggregationHealthIndicator` | Last aggregation run succeeded (or no run yet) | Last run failed (`error` in details) |
| `artifactStorage` | `ArtifactStorageHealthIndicator` | Partition/artifact directories writable | Directory unavailable |
| `ingestionLedger` | `IngestionLedgerHealthIndicator` | Ledger directory exists and is writable | Cannot be created / unavailable |

**HTTP status semantics:** `UP` → `200`, any `DOWN` → `503` (body contains details
in both cases). Useful for liveness/readiness probes.

**info:**
```bash
curl -s http://127.0.0.1:8081/actuator/info | jq
```

> Address location: on daemon startup, `ApplicationLifecycleLogger` logs
> `application started; health at http://127.0.0.1:8081/actuator/health`.
> Only `health,info` are exposed (`management.endpoints.web.exposure.include`);
> `shutdown` and mutating endpoints are deliberately closed.

<a id="открытые-вопросы"></a>
## Open questions

- **ING-8:** REST endpoints (ingest/queries/TAXII) live in a separate `adapter-web`
  and **must** open `MdcScope` (run ID), like CLI/daemon, or log correlation is lost.
  Revisit bind/IP restrictions and possibly auth at that point.
- Prometheus metrics (`/actuator/prometheus`) are disabled; add when external
  monitoring appears.
- HTTP health for non-loopback consumers (container orchestrator): deployment
  owns the bind/network decision.
