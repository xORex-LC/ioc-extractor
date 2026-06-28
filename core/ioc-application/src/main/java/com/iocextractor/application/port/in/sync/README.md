# application.port.in.sync

## Назначение

Inbound-порты для ручного CLI и daemon lifecycle синхронизации. Порты описывают
операторские use cases, не transport details.

## Структура

| Файл | Ответственность |
|---|---|
| `RemoteFetchUseCase` | Запуск remote → inbox цикла со source-фильтром для CLI/scheduler isolation |
| `ArtifactPublishUseCase` | Раздельные reconcile и publish операции с profile/target-фильтрами |
| `*Command` / `*Result` | Небольшие DTO на границе driving adapters |

## Инварианты

- CLI/daemon вызывают use-case ports, не outbound adapters напрямую.
- `dryRun` проходит через command, чтобы application layer владел побочными эффектами.
- Source/profile/target filters optional; неизвестные значения отвергаются до transport IO.
- Startup reconcile materializes missing pairs без remote IO; обычный publish сначала
  делает тот же discovery, затем обрабатывает retryable pair state.
- `dryRun` не меняет inbox, remote storage или ledgers.
- Fetch and publish result models are intentionally small and CLI-friendly.

## Зависимости

**Зависит от:** JDK.

**Не импортируется:** Spring, picocli, transport adapters, JDBC.
