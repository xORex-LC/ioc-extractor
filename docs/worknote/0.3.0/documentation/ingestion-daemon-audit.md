---
title: "DOC-02E — Приём документов и эксплуатация daemon"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation slice"
document_type: "Execution evidence"
source_of_truth: false
language: "ru"
---

# DOC-02E — Приём документов и эксплуатация daemon

Проверка 2026-11-05 (Asia/Irkutsk), HEAD
`4641e5ea9ff5e75edbf61cdff6581108c60f04e4`, ветка `docs/quality`.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).

Scope: dev/ingestion и guide/daemon-operations, две EN/RU-пары. Создан RU capability,
переработан существующий RU runbook, проверены факты, полнота, runnable examples,
навигация и живые источники. Применены code-documenter, java-code-review,
cli-developer и spring-boot-engineer. Ссылки на module README не означают их
полного аудита. Production/test Java, POM, scripts и analyzer policy не менялись.

На входе незакоммиченные DOC-02A/B/C/D. Их 29 содержательных документов/records,
включая KNOWN-ISSUES с TOOL-10 и OPS-10, сохранены побайтово. Общие navigation,
inventory/source sets и status дополнены новым пакетом. Нового коммита нет.

## Находки и решения

| ID | Приоритет / наблюдение | Результат |
|---|---|---|
| `DOC-N01` | M: quiet period представлялся достаточной защитой незавершённой копии | Описана проверка возраста mtime без обязательных одинаковых size/mtime samples; закрытие producer file и rename под уникальным именем остаются протоколом передачи |
| `DOC-N02` | M: journal scan можно было принять за полный backstop WatchService | Разделены полный polling по умолчанию, event scanner без periodic full listing и сканирование только уже допущенных jobs |
| `DOC-N03` | M: claim связывался с обязательным последующим переносом в failed | Разделены verified rejection, blocked pre-hash head, unclaimed saturation и pre-reservation failure; сохранён запрет ручной очистки очереди |
| `DOC-N04` | M: health exit 0 включал degraded, а bare installed commands обходили root guard | Даны sudo launcher, exact UP-only exit 0, non-UP exit 1, query/parse exit 2; component scope и curl transport exit отделены от JSON status |
| `DOC-N05` | M: failed/done/health могли читаться как полный результат транзакции документа | Указаны per-artifact commit, partial outcomes, COMPLETED_WITH_ERRORS и ING-11; managed import replay/atomicity не перенесены на документы |
| `DOC-N06` | M: последовательное ingestion описывалось как отсутствие параллельной работы | Разделены detector/promotion concurrency=1, до двух preparation workers, dispatch window и самостоятельные admission/workspace budgets |
| `DOC-N07` | M: пустые inbox/processing предлагались как достаточная точка backup/restart | Добавлены pending/blocked jobs, import/publication backlog, in-flight fetch и подтверждённая остановка сервиса; timeout не объявляется чистым drain |
| `DOC-N08` | M: DB-directory backup не учитывал файловые журналы и owned sources | Описан согласованный stopped-service комплект обеих DB/sidecars, journals, referenced sources/workspaces/snapshots/slices и конфигурации; online backup требует отдельной квалификации |
| `DOC-N09` | L: ECS file format переносился на journal, priority filter мог пропускать Java warnings | Разделены JSON rolling file и console journal; корреляция по observation/run/time и application severity вместо одного journal priority |
| `DOC-N10` | M: retry durability была привязана только к service v14 | Добавлен file journal с fsync и отдельная JDBC run saga; выбор file ledger не отключает run ledger production daemon |
| `DOC-N11` | L: retention сводился к двум политикам, архив ожидался с прежним suffix | Добавлены recovery pins и ссылки на lifecycle/import cleanup; архивный suffix может быть .sealed, TOOL-10 не скрыт |
| `DOC-N12` | M: RU capability отсутствовал, RU runbook повторял прежние ограничения | Создан перевод, согласованы заголовки, literal blocks, identifiers и normalized link targets; новый факт не вносился только в один язык |

## Живые источники

Точные дополнительные paths внесены в source set `ingestion` в
[source-sets.tsv](source-sets.tsv).

| Контракт | Владелец / проверка | Граница |
|---|---|---|
| Detection/filter/quiet period | IngestFlowConfiguration, IngestFileListFilter и IT | Poller без AcceptOnce; WatchService retry callback не является periodic full scan |
| Claim/seal/source disposition | OrderedDocumentAdmissionHandler, FileSystemSourceLifecycle, FileSourceHasher и IT | Atomic no-replace ownership, private sealed bytes, проверка evidence; не producer end-of-stream protocol |
| Durable admission/dispatch | DocumentAdmissionService, FileDocumentAdmissionJournal, DurableDocumentDispatcher | Rank/frame reference отдельно от source journal; window/byte/count limits, blocked head и fail-stop cleanup |
| Preparation/promotion/run saga | IngestionService, AppConfig, IngestRunRecoveryService | File admission journal и JDBC run ledger независимы; per-artifact writes, ING-11 открыт |
| Startup/readiness/health | CanonicalIntakeStartupCoordinator, ingestion lifecycle и capacity indicators, их tests | Recovery barrier, process-local counters, metadata-only reads; UP не равен source success |
| CLI/installed operation | HealthCommand, packaging/templates/ioc, service/env/application templates, install.sh/deploy-local.sh | Root guard и effective prefix; host systemd и installed env в этом пакете не запускались |
| End-to-end contract | DaemonIngestE2EIT, PreparedDocumentPromotionIT, NativeDeliveryLifecycleIT | Actual Spring/physical files/DB/receipts; Native suite подменяет SMB wire и UTC, не квалифицирует живой сервер или нагрузку |

Историческая идентичность и измерения Native smoke в ingestion сохранены:
на этот раздел ссылается native-capacity. Новый пакет не приписывает старому
bundle актуальную transport/capacity квалификацию.

## Выполненная проверка

Локальные логи/fixtures: `.dev/r030-doc-02e/` (ignored). Команды выполнялись
последовательно для Maven; ниже — фактически исполненные tests, не прежние XML из target.

- `make context`, `make doctor-core`: HEAD/branch подтверждены, JDK 21 доступен.
- Выбранный Surefire cohort: **79 tests, 0 failures/errors/skips**. IngestionService
  (40), DocumentAdmissionCompletion (14), IngestRunRecoveryService (4),
  IngestAdapterProperties (1), LoggingIngestionStartupObserver (5),
  CanonicalIntakeStartupCoordinator (5), IngestionLifecycleHealthIndicator (2),
  DataProcessingCapacityHealthIndicator (8).
- Выбранный Failsafe cohort: **66 tests, 0 failures/errors/skips**, восемь suites:
  FileSourceMessageHandler (28), FileSystemSourceLifecycle (17),
  IngestFileListFilter (4), FileSourceHasher (2), FileIngestionLedger (10),
  DaemonIngestE2E (3), PreparedDocumentPromotion (1), NativeDeliveryLifecycle (1).
  Пройдены очередь/давление/порядок, durable retries, pre-hash block, fatal cleanup,
  рестарт и реальная composition до canonical/publication ledgers.
- `make test-one MODULE=bootstrap/ioc-app TEST=DocumentationConventionTest`:
  **1 test, PASS**.
- Все **5 bash blocks** runbook прошли syntax check и исполнение в sandbox:
  **6 literal CLI JVM**, ещё **8 JVM** для help и health boundary cases;
  один реальный curl. Использован собственный loopback HTTP fixture и отдельные
  cwd/environment; URI/port/prefix заменены только на изолированные значения.
- Exact health outcomes: UP→0, DEGRADED/UNKNOWN/DOWN→1, invalid JSON→2,
  unreachable→2; component paths и JSON проверены. Health/help не создали DB.
  Copy/rename действительно выполнялись в sandbox, байты совпали и `.part` исчез.
  Синтетический report.docx проверял передачу, не формат DOCX/extraction.
- Systemctl/journalctl/sudo проверены argument-only wrappers; host service,
  service account, privileges и systemd EnvironmentFile не изменялись.
- Две EN/RU-пары: одинаковые уровни headings, exact fenced blocks,
  inline identifier sets и normalized link-target multisets.
- Inventory: **359 Markdown paths / 64 source sets**, current/base hashes и
  отсутствующие пары сверены; **59 missing RU pairs**: root 3, capability 5,
  ADR 49, guide 2. ADR/generated catalogs/release history/quality floors неизменны.
- `make docs`: **2636 links / 665 unique / 2416 OK / 0 errors / 220 excluded**.
  `git diff --check`: PASS.

## Границы результата

Документационный срез проверен; R030-DOC целиком не закрыт. Нового live daemon,
Samba/Windows SMB target, systemd install/stop, реального backup/restore или
whole-process load measurement не выполнялось. Детерминированный IT не заменяет
эти проверки. Runtime helpers/HTTP fixture завершены; host service не тронут.

TOOL-10 (lifecycle smoke filename), OPS-10 (nullable import status read), ING-11,
полный ING-13, OUT-4 и прочие owning obligations остаются открыты. Новый source
fix не заявляется. Полные verify и PMD не запускались; их последние PASS относятся
к `191e74146dddeeab36bf59d9d09020a92bef0620`, `verify.fresh=false`,
`pmd.fresh=false`. Выбранные тесты не обновляют эти markers.
