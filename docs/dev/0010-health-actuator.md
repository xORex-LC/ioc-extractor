# 0010 — Health / Actuator HTTP-контур демона

## Статус

Реализовано (ING-3). Закрывает «health-indicators зарегистрированы, но не
экспонированы (`web-application-type: none`)». Заодно — первый камень под web
driving-adapter (ING-8).

## Контекст

В демоне есть три `HealthIndicator` (`AggregationHealthIndicator`,
`ArtifactStorageHealthIndicator`, `IngestionLedgerHealthIndicator`) и
`spring-boot-starter-actuator` на classpath, но приложение запускалось как
non-web (`spring.main.web-application-type=none`), поэтому HTTP-сервера не было и
индикаторы **никто не опрашивал**. Снаружи (systemd/мониторинг) узнать состояние
демона было нельзя.

Ограничение топологии: **oneshot/CLI не должен поднимать веб-сервер** — это
короткоживущая команда (`extract … → exit`), сервер бы только мешал и держал
процесс. Значит web нужен **исключительно в daemon-режиме**.

Дополнительная сложность: режим задаётся `ioc.runtime.mode`, а **не** Spring-профилем.
systemd-юнит передаёт только `--ioc.runtime.mode=daemon` (профиль `daemon` для
observability может не совпадать). Гейтить web по профилю — ненадёжно.

## Решения

**1. Зависимость.** Добавлен `spring-boot-starter-web` (Tomcat). По умолчанию
`spring.main.web-application-type=none` в `application.yml` — даже с web на
classpath oneshot/CLI остаётся non-web.

**2. Гейт по `runtime.mode` через `EnvironmentPostProcessor`.**
`DaemonWebEnvironmentPostProcessor` (зарегистрирован в
`META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`)
при `ioc.runtime.mode=daemon` добавляет property-source с
`spring.main.web-application-type=servlet`.
- Работает, потому что `spring.main.*` биндится из окружения **после** EPP;
  `runtime.mode` из command-line args виден EPP (command-line property source
  добавляется до EPP).
- `Ordered.LOWEST_PRECEDENCE` — чтобы видеть и `runtime.mode`, заданный в
  `application.yml` (после config-data processing).
- Отклонено: гейт по Spring-профилю (систем­ный юнит профиль не ставит);
  программная установка типа в `main()` (не видит yml-конфиг, только args).

**3. Поверхность и безопасность.** Actuator на **основном** сервере (не отдельный
management-порт — отдельный всё равно потянул бы основной). По умолчанию:
- `server.address=127.0.0.1`, `server.port=8081` — **loopback-bind**;
- `management.endpoints.web.exposure.include=health,info`, `health.show-details=always`;
- **нет** `shutdown` и прочих мутирующих эндпоинтов.

**4. Прижатый пул Tomcat.** `server.tomcat.threads.max=8`, `min-spare=1`,
`accept-count=10`: сервер отдаёт только `/actuator/**`, дефолтные 200 потоков
зря ели бы память и упирались в systemd `TasksMax`/`MemoryMax`.

**5. systemd-hardening под слушающий сокет.** В юните сделано явным:
`RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6`, `IPAddressAllow=localhost`,
`IPAddressDeny=any`. Капабилити не нужны: порт 8081 > 1024, `CAP_NET_BIND_SERVICE`
не требуется. (Под ING-8 с исходящим/удалённым трафиком эти ограничения ослабить.)

## Следствия

- Файлы: `DaemonWebEnvironmentPostProcessor` + `.imports`, блоки `server`/`management`
  в `application.yml`, `spring-boot-starter-web` в `bootstrap/ioc-app/pom.xml`,
  hardening в `packaging/templates/ioc-extractor.service`.
- Тесты: oneshot-контексты (`ApplicationContextTest`, `GoldenPipelineTest`,
  `DaemonRuntimeModeTest`) закреплены `webEnvironment=NONE`; `DaemonManagementEndpointTest`
  (RANDOM_PORT) реально дёргает `/actuator/health`; `DaemonWebEnvironmentPostProcessorTest`
  проверяет гейтинг.
- **Тест-нюанс:** `@SpringBootTest` inlined-`properties` применяются **после** EPP,
  поэтому в `DaemonManagementEndpointTest` web включается явным
  `spring.main.web-application-type=servlet` в свойствах теста; в реальном запуске
  это не нужно (mode приходит args). Гейтинг покрыт отдельным юнит-тестом EPP.
- При старте демона `APP_START` логирует адрес health (`ApplicationLifecycleLogger`).

## Как смотреть индикаторы

Сервер слушает **только loopback**, поэтому смотреть с того же хоста (или через
SSH-туннель). Порт по умолчанию `8081` (`server.port`).

**Сводный health** (с деталями — `show-details: always`):
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

**Отдельный компонент** (имя = бин минус суффикс `HealthIndicator`):
```bash
curl -s http://127.0.0.1:8081/actuator/health/aggregation     | jq
curl -s http://127.0.0.1:8081/actuator/health/artifactStorage | jq
curl -s http://127.0.0.1:8081/actuator/health/ingestionLedger | jq
```

| Компонент | Бин | UP когда | DOWN когда |
|---|---|---|---|
| `aggregation` | `AggregationHealthIndicator` | последний прогон агрегации успешен (или ещё не было) | последний прогон упал (`error` в details) |
| `artifactStorage` | `ArtifactStorageHealthIndicator` | каталоги партиций/артефактов доступны на запись | каталог недоступен |
| `ingestionLedger` | `IngestionLedgerHealthIndicator` | каталог ledger существует и доступен на запись | не создаётся/не доступен |

**Семантика HTTP-кода:** `UP` → `200`, любой `DOWN` → `503` (тело с деталями
есть в обоих случаях). Удобно для liveness/readiness-проб.

**info:**
```bash
curl -s http://127.0.0.1:8081/actuator/info | jq
```

> Где живёт адрес: при старте демона `ApplicationLifecycleLogger` пишет в лог
> строку `application started; health at http://127.0.0.1:8081/actuator/health`.
> Экспонируются только `health,info` (`management.endpoints.web.exposure.include`);
> `shutdown` и мутирующие эндпоинты намеренно закрыты.

## Открытые вопросы

- **ING-8:** REST-эндпоинты (ингест/запросы/TAXII) живут в отдельном `adapter-web`
  и **обязаны** открывать `MdcScope` (run-id), как CLI/демон, иначе теряется
  корреляция логов. Тогда же — пересмотр bind/IP-ограничений и, возможно, auth.
- Метрики Prometheus (`/actuator/prometheus`) — не включены; добавить при
  появлении внешнего мониторинга.
- HTTP-health для не-loopback потребителей (контейнерный orchestrator) —
  решение по bind/сети на стороне деплоя.
