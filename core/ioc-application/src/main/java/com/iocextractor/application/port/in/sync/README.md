# application.port.in.sync

## Назначение

Inbound-порты для ручного CLI и daemon lifecycle синхронизации. Порты описывают
операторские use cases, не transport details.

## Структура

| Файл | Ответственность |
|---|---|
| `RemoteFetchUseCase` | Запуск remote → inbox цикла |
| `ArtifactPublishUseCase` | Запуск publish/reconcile completed export slices |
| `*Command` / `*Result` | Небольшие DTO на границе driving adapters |

## Инварианты

- CLI/daemon вызывают use-case ports, не outbound adapters напрямую.
- `dryRun` проходит через command, чтобы application layer владел побочными эффектами.
- Profile filter в publish optional; неизвестные profile валидируются в use-case/bootstrap catalog.
- Publish cycle reconciles local verified slices with configured targets before remote writes.
- Fetch and publish result models are intentionally small and CLI-friendly.

## Зависимости

**Зависит от:** JDK.

**Не импортируется:** Spring, picocli, transport adapters, JDBC.
