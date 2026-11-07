# Известные проблемы и технический долг

[English version](../KNOWN-ISSUES.md)

Реестр разделяет действующие дефекты и недостающую квалификацию, сознательно
отложенные расширения и завершённые работы. ID стабильны и не используются
повторно. Устройство возможностей описывается в capability docs, решения — в ADR,
результаты конкретных кандидатов — в release records; этот документ определяет
оставшуюся проблему, последствия и условие завершения.

Последний аудит — **2026-11-07**, по исходникам `0.3.0-SNAPSHOT` и сохранённым
свидетельствам квалификации. Проверка кода, точечный тест или исторический
нагрузочный результат не подтверждают свежий полный релизный gate либо другое
развёртывание.

## Как пользоваться реестром

| Состояние | Значение |
|---|---|
| `open` | Остаётся доказанный дефект или принятая задача реализации |
| `partial` | Часть реализована; указанный остаток ещё открыт |
| `qualification` | Реализация есть, но нет названной проверки цели или приёмки |
| `deferred` | Сознательно отложенное улучшение; начинать по указанному триггеру |
| `closed` | Исходный объём реализован и подтверждён; последующие ограничения учитываются отдельно |
| `out-of-scope` | Предложение явно исключено из текущего объёма продукта |

Владельцы обозначают ответственность, а не новые назначения или сроки.
Прежние оценки трудоёмкости не являются текущим расписанием. Не закрывайте пункт
только по успеху постороннего gate или наличию интерфейса. При закрытии сохраните
ID и перенесите результат со свидетельствами в архив. Ссылка на пункт использует
его ID строчными буквами, например `KNOWN-ISSUES.md#ops-11`.

Сначала устраняйте возможную потерю/пропуск входа и несогласованную активацию
([OPS-11](#ops-11), [OPS-12](#ops-12), [OPS-13](#ops-13), [OPS-14](#ops-14)),
параллельно рассматривая безопасность [SRC-3](#src-3). Затем — отказы повторов/
статуса и оставшуюся ресурсную/эксплуатационную квалификацию. Новое обогащение,
форматы и инструменты не заменяют эту работу.

## Дефекты ingestion и канонической записи

<a id="1-демон--ингест-ing"></a>
<a id="ing-11"></a>
### ING-11 — Возобновление частично завершённого ingest run

**Partial · владелец: ingestion/run-ledger.** Повторы сохраняют document admission,
наблюдение/ранг и backoff, но подготовка может создать ещё один ingest run.
После canonical commit ошибка проекции/архивации может оставить прежний run
в `DB_COMMITTED`, пока источник ещё claimed. При fixed lifecycle receipts могут
устранить повторный парсинг и воспроизвести принятые строки; без пригодной receipt
повтор снова извлекает IOC. Это не полный протокол возобновления между admission,
source ledger и run-ledger; возможны повторная работа и оставшиеся частичные runs.

Закрытие: спроектированный checkpoint/resume-контракт, который после доказанного
commit выполняет только недостающие projection/archive/finalization, согласует
три журнала и проверяет отказы/restart с включённым/выключенным lifecycle
и имеющимися/истёкшими receipts. Воспроизведение receipt не равно возобновлению
только проекции. См. [ingestion](../dev/ru/ingestion.md#receipts-и-повторы) и
[IngestionService](../../core/ioc-application/src/main/java/com/iocextractor/application/ingest/IngestionService.java).

<a id="ing-13"></a>
### ING-13 — Физическая судьба отказов до достоверного ключа содержимого

**Partial · владелец: document admission/source lifecycle.** Исчерпание повторов
и подавление повторных terminal-логов реализованы. Отказ до резервирования может
оставить вход в inbox; отклонение только через ledger может сохранить пути
`unknown`. Зарезервированный отказ до хеширования, напротив, сохраняет свой token,
блокирует дальнейшую promotion и переводит capacity health в DOWN. Общей
поддерживаемой команды clear/requeue нет. Это разные окна отказа, а не один
бесконечно повторяющийся poll-лог.

Исследуйте исходный файл/token и используйте согласованное recovery/restore;
не очищайте SQLite или приватные свидетельства вручную. Закрытие: явная
идемпотентная quarantine/disposition для каждого pre-claim/pre-hash окна,
достоверные сведения об источнике и поддерживаемый интерфейс восстановления.
См. [эксплуатацию демона](../guides/ru/daemon-operations.md) и
[ingestion](../dev/ru/ingestion.md#receipts-и-повторы).

<a id="2-обогащение-вывода-out"></a>
<a id="out-3"></a>
### OUT-3 — Коллизии конечных ключей legacy-маршрута с fixed lifecycle

**Open · владелец: processing/canonical writer.** Подготовка legacy KEEP_FIRST
сохраняет принятые mapped rows. При активном lifecycle повтор конечного ключа
может дать `SINK.WRITE_FAILED` до резервирования ID/commit: например, при
выключенном pipeline dedup или разных retained IOC, отображённых в один ключ.
Без lifecycle действуют прежние canonical uniqueness/provenance keep-first.

Закрытие: контракт подтверждения lifecycle для повторов либо проверяемое
ограничение конфигурации. Раннее схлопывание не должно молча менять резервирование,
occurrences или provenance. Регрессия
`GoldenPipelineIT.originalRouteMappedCollisionsPreserveReservationAndProvenanceInBothWritePaths`
фиксирует текущее ограничение. См. [обработку](../dev/ru/processing.md).

<a id="ops-14"></a>
### OPS-14 — Импортируемое поле из пробелов обходит ordered guard

**Open · владелец: managed import/canonical field mutation.** В
`JdbcCanonicalImportWriter` пустой VALUE не считается ordered candidate, но обычный
merge всё же может его записать. Воспроизведение packaged local daemon со штатным
`ioc-aggregate-v1` заменило `Feed-A` тремя пробелами, сохранив четыре carrier-строки;
ранг field-origin не изменился. В этой ветке обходятся ожидаемое сохранение
прежнего значения и полномочия упорядоченного обновления поля.

Для отсутствующего имени используйте пустую CSV-ячейку/NULL, не пробелы;
preview не гарантирует результат merge. Закрытие: единая blank-политика до обычной
мутации и тесты nonblank/blank/NULL, обоих порядков регистрации, новых/активных
lifecycle и реального aggregate import. См. [эксплуатацию aggregate](../guides/ru/ioc-aggregate.md)
и [import writer](../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriter.java).

## Дефекты sync, статуса и развёртывания

<a id="7-экспорт--упаковка--ops-exp--ops"></a>
<a id="ops-10"></a>
### OPS-10 — Import status падает на NULL retry deadline

**Open · владелец: JDBC status/bootstrap health.** `JdbcImportStatusReader` читает
SQL NULL через `getObject("next_attempt_at_ms", Long.class)`, что отвергает выбранный
SQLite driver. Реальный CLI после replay в `SNAPSHOT_PINNED` получил
`Bad value for type Long`. `DataframeImportHealthIndicator` использует тот же
reader; этот отказ статуса не доказывает неуспешность canonical commit.

Закрытие: nullable read и регрессии для пустой очереди, головы с NULL/ненулевым
deadline и реальных CLI/health на nonterminal work. Не подменяйте timestamps
в БД для сокрытия ошибки. См. [reader](../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcImportStatusReader.java)
и [эксплуатацию import](../guides/ru/dataframe-import.md).

<a id="ops-11"></a>
### OPS-11 — Fetch identity не включает пространство имён источника/endpoint

**Open · владелец: fetch identity/ledger.** `RemoteObjectIdentity`, локальный
in-flight registry и JDBC primary key используют только `(path,size,mtime)`.
Два endpoint с одинаковым tuple, но разным содержимым воспроизводят тихий пропуск
второго без GET. Polling/reconcile не исправляет неверную идентичность.

До исправления используйте непересекающиеся remote paths/имена; это ограничение,
а не межпроцессный fencing. Закрытие: явный namespace-контракт, совместимая
миграция ledger/in-flight и регрессии двух endpoint с одинаковым tuple и restart.
См. [identity](../../core/ioc-application/src/main/java/com/iocextractor/application/sync/RemoteObjectIdentity.java)
и [JDBC ledger](../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcRemoteFetchLedger.java).

<a id="ops-12"></a>
### OPS-12 — Fetch landing может заменить конкурентный вход

**Open · владелец: fetch/local landing.** `finalPathFor` проверяет отсутствие
до скачивания; `Files.move(ATOMIC_MOVE)` не гарантирует no-replace при позднем
появлении цели. На Linux две синхронизированные загрузки разных identities
дали две записи FETCHED и один inbox-файл. Это доказанное окно потери входа.

Используйте различные конечные имена и согласуйте всех inbox writers; отдельный
CLI не разделяет полосу демона. Закрытие: атомарное владение/no-replace landing,
детерминированные регрессии endpoint/внешнего writer и сохранение crash-window
идемпотентности. См. [fetch service](../../core/ioc-application/src/main/java/com/iocextractor/application/sync/RemoteFetchService.java)
и [remote operations](../guides/ru/remote-storage-sync.md).

<a id="ops-13"></a>
### OPS-13 — Неуспешный preflight оставляет новые host-файлы

**Open · владелец: локальная активация/packaging.** `deploy-local-root.sh` обновляет
systemd unit и `bin/ioc{,-config}` до semantic preflight. Явный `die` обходит
ERR trap восстановления. Процесс, `current` и базы остаются прежними, но последующий
restart/CLI может совместить новые host-файлы со старым JAR. Воспроизведение
использовало реальный участок исходника с подменёнными привилегиями/systemd.

До restart сравните unit с `backups/<failed-release-id>-unit.service`, восстановите
соответствующие unit/helpers из rollback point и перечитайте systemd. Закрытие:
единый путь отказа либо staging до preflight, тесты rejection/restart с проверкой
unit/helpers/current/баз. См. [скрипт активации](../../packaging/deploy-local-root.sh)
и [развёртывание](../guides/ru/deployment.md).

## Ресурсы, безопасность и квалификация цели

<a id="8-source-parsing--dependency-surface-src"></a>
<a id="src-3"></a>
### SRC-3 — Совпадения с advisories требуют security disposition

**Open · владелец: зависимости/security review и затронутые адаптеры.** Сохранённый
JAR содержит Jackson core/databind 2.21.5 и 3.1.5, Junrar 7.6.0, Guava 33.3.1-jre
и jsoup 1.23.1. Обновлённый OSV screen 2026-11-07 по его 171 внешней координате
вернул 11 advisory ID для этих семи координат. Это проверка затронутых версий,
не доказательство достижимости каждого уязвимого API в IOC.
Примеры: [Guava](https://github.com/advisories/GHSA-xxph-c9ww-hj94),
[jsoup](https://github.com/advisories/GHSA-65r4-943x-97jj),
[Jackson](https://github.com/advisories/GHSA-cxp5-3px4-pw24).

Закрытие: рассмотрение достижимости/disposition каждого совпадения владельцем
либо совместимые обновления, regression/consumer checks и свежий релизный security
gate. Health развёртывания не является security-результатом; бессрочный suppression
не заменяет disposition. См. [политику безопасности](../SECURITY-ENGINEERING.md)
и [зависимости развёртывания](../guides/ru/deployment.md).

<a id="src-2"></a>
### SRC-2 — Parser budgets покрывают не все поддерживаемые пути входа

**Partial · владелец: source parsing/bootstrap security.** Старое описание
неограниченного буфера устарело: production document path использует дисковые
cursors, потоковые HTML/DOCX, ограниченные workspaces и daemon source admission.
Области HTML repair также ограничены. Однако нет единого операторского контракта
для ratio/depth распаковки и wall-clock cancellation всех Tika-форматов и путей
oneshot/daemon. Удобный String-returning API ещё материализует текст;
ограничения managed CSV import являются отдельным контрактом.

Закрытие: budgets входа/парсера/времени, cancellation и типизированные отказы
заявленных путей, затем проверки вредоносных/чрезмерных форматов без частичной
канонической записи. Не обобщайте HTML/DOCX bounds на любой автоматически
определённый формат. См. [обработку](../dev/ru/processing.md#подготовка-и-ограниченные-данные),
[ресурсы](../dev/ru/native-capacity.md) и [модель угроз](../THREAT-MODEL.md).

<a id="ing-15"></a>
### ING-15 — Ресурсная приёмка полного цикла больших документов

**Partial · владелец: source/ingestion/private reducer/canonical storage.** Потоковая
обработка устранила исторический source OOM миллионных HTML/DOCX; фатальный отказ
dispatcher видим и восстанавливаем. На прежнем профиле 512 MiB миллионные прогоны
затем исчерпали квоту приватного SQLite до canonical commit; 100k превысили бюджеты
времени/writer. Поздние миллионный DOCX/серия 100 документов Native завершились
на явно расширенном профиле: heap 2 GiB, workspace 8 GiB, cgroup 6 GiB.

Это разные измеренные профили, не универсальная приёмка прежних defaults
или любых документов. Закрытие: согласованные ресурсные/SLO-пределы полного цикла
и квалификация их нагрузок/конкуренции. Исторические отказы остаются отказами.
См. [измерения](../dev/ru/native-capacity.md) и [sizing](../guides/ru/bulk-resource-sizing.md).

<a id="ing-16"></a>
### ING-16 — Расхождение UTC и долговечного high-water

**Partial · владелец: service coordination/canonical clock/operations.** Admission,
import retry, terminalization и retention progress теперь ограничивают timestamps
сохранённым `updatedAt`; обратный UTC больше не ломает эти переходы. Независимая
canonical safe-clock всё ещё отвергает небезопасный skew. Причина исторического
расхождения raw UTC/high-water не установлена; логи не доказывают перевод часов
хоста. Исходный отказ 2.061 s и последующие 2.073–2.363 s остаются историческими
неуспешными результатами.

Закрытие остатка: свидетельства clock/progress, disposition причины и нагрузочная
проверка принятой clock policy. Исправление координации не разрешает ослаблять
canonical safety. См. [ingestion](../dev/ru/ingestion.md#receipts-и-повторы)
и [lifecycle](../dev/ru/canonical-record-lifecycle.md).

<a id="out-4"></a>
### OUT-4 — Ресурсный бюджет selected processing не принят

**Qualification · владелец: processing/application.** Исторические измерения
selected path сохранили корректность, но превысили бюджеты allocations/heap:
около 4.04× caller allocations на 100k/20 keys и около 1.255×/1.294× sampled heap
при 50%/0% повторов против границы 1.25×. Поздняя URL→host/IP нагрузка сохранила
occurrence-proportional mapping и около 3.67 GB caller allocations. Это датированные
измерения, не свежее сравнение каждого последующего cleanup.

Закрытие: согласованные пределы нагрузок/ресурсов и измеренное улучшение
с сохранением diagnostics, ID, provenance и import participants, включая SLO
конкурентного приложения. Зелёных build gates недостаточно.
См. [обработку](../dev/ru/processing.md) и [историю измерений](../dev/ru/native-capacity.md).

<a id="ops-8"></a>
### OPS-8 — Managed SMB import требует квалификации каждой цели

**Qualification · владелец: SMB import/целевое развёртывание.** Исторический P8
на Samba не подтверждает поздний двухпользовательский контракт ADR-0025 для любого
семейства серверов. Каждая заявленная цель требует разных producer/service
identities, capability gate, producer-denial, server no-replace rename/share modes,
stable file ID, disconnect recovery, exact terminal purge и `CHANGE_NOTIFY`.
Polling заменяет потерянные notifications, но не ownership/retention checks.

Сохраняйте managed SMB import выключенным до квалификации настроенного источника.
Закрытие — по каждому заявленному семейству с полными свидетельствами; offline skip
или один family не квалифицирует другой. Этот ID заменил ошибочно повторно
использованный active OPS-7; исторический fast-path OPS-7 остаётся закрытым отдельно.
См. [эксплуатацию import](../guides/ru/dataframe-import.md) и
[ADR 0025](../ADR/0025-managed-import-smb-service-namespace-and-terminal-retention.md).

## Действующие задачи библиотек и инструментария

<a id="9-developer-tooling--build-tool"></a>
<a id="tool-6"></a>
### TOOL-6 — Автоматическое сравнение опубликованных API

**Open · владелец: library API/Maven/CI.** Текущие потребители не сравнивают весь
public/protected API с выпущенной версией: удаление невызванного метода может
пройти CI и сломать другой сервис. Теперь объём включает все шесть допущенных
библиотек, а не только concurrency/diagnostics. Начинать до следующего выпуска,
особенно при изменении API; поведенческие review/тесты остаются отдельными
от сравнения исходной и бинарной совместимости.

Для завершения нужны:

- Явная опубликованная база, проверяемый API, воспроизводимая команда и отчёт
  исходной/бинарной совместимости каждого компонента.
- CI/release-проверка, отвергающая неожиданную несовместимость или недоступную
  базу; получение базы не добавляет обязательную сеть в offline `make verify`.
- Регрессии удаления/изменения метода, даже если текущий consumer его не вызывает,
  и успех для совместимого внутреннего изменения.
- Рассмотренные намеренные несовместимости по [правилам версий](RELEASE-PROCESS.md#версии-переиспользуемых-библиотек),
  включая minor/migration notes в 0.x. Нельзя молча обновлять базу ради успеха.
  Версии POM/CI остаются общими до TOOL-9; events сохраняет точную версию семейства.

[japicmp](https://github.com/siom79/japicmp) — кандидат, не принятый gate.
См. [разработку](../guides/ru/libraries/development.md) и [публикацию](../guides/ru/library-publication.md).

<a id="tool-7"></a>
### TOOL-7 — Необъяснённый отказ временного GPG-ключа теста

**Open · владелец: publication tooling.** В E4 2026-10-25 logging signing fixture
получил код 2 из `--quick-generate-key`, stderr был потерян. Теперь тест сохраняет
безопасный stderr; отдельная генерация и поздние полные наборы проходили,
но первоначальная причина неизвестна. Автоматический retry не введён.

Закрытие: при воспроизведении сохранить stderr/версию GnuPG/условия, найти
и устранить причину, выполнить полный набор без retries. Одного зелёного повтора
недостаточно. См. [offline-тесты](../../tools/tests/library-publication-test.py)
и [свидетельства релиза](../../.github/release-notes/ioc-events-spring-0.3.0-rc.1.md#packaged-consumers-and-retained-evidence).

<a id="tool-8"></a>
### TOOL-8 — Нет защищённой CI-переквалификации Central configuration consumer

**Qualification · владелец: publication tooling.** `ioc-config-spring-boot:0.3.0-rc.1`
опубликован в обоих репозиториях. Запуск `40088095516` прошёл GitHub consumption,
но ошибка Central routing была скрыта `tee`. Исправленный локальный consumer
Central прошёл; routing/pipefail fixes интегрированы. Более позднего protected
recovery result не записано; просмотр списка Actions 2026-11-07 не нашёл
publication run после 2026-10-25.

Закрытие: protected recovery с исходной validation `40087127800`, теми же тегом,
подписанными файлами и deployment `90e22519-418d-4b74-ac5a-cadcc3d2c559`, без
пересборки/переподписи. Рассмотреть реальные логи, хеши и источники обоих
CI consumers. Локальный успех пункт не закрывает.
См. [запись компонента](../../.github/release-notes/ioc-config-spring-boot-0.3.0-rc.1.md)
и [consumer](../../tools/fixtures/configuration-consumer/README.md).

<a id="tool-9"></a>
### TOOL-9 — Активация независимых версий в Maven и CI

**Open · владелец: Maven/publication tooling.** [ADR 0048](../ADR/0048-independent-library-versions-and-reuse.md)
принимает отдельные версии concurrency/diagnostics/logging/configuration
и одну версию семейства events core/adapter. Текущие POM dependencies и допуск
по `release-X.Y.Z` ещё используют общую revision; глобальный `-Drevision`
не обеспечивает независимости.

Закрытие — все [критерии активации](RELEASE-PROCESS.md#активация-независимых-версий):
раздельные источники версий, product/library-only сценарии, неизменяемая source/tag/
recovery identity, точная версия events family и потребление сервисом без подмены
локальным кодом реактора. Триггер — до первого независимо версионированного
выпуска. Одной правки политики недостаточно.

<a id="tool-10"></a>
### TOOL-10 — Lifecycle smoke/load ждёт устаревшее terminal filename

**Open · владелец: lifecycle developer tooling.** `lifecycle-smoke.sh` ждёт
`*-initial.html`/`*-reappearance.html`; Native архивирует sealed-вход как
`.html.sealed`. Smoke 1000 строк 2026-11-04 остановился на первом ожидании при уже
существующем done-файле; проверки expiry/retention/non-reuse не исполнились.
`make lifecycle-load` использует тот же harness.

Закрытие: ожидание по terminal delivery без ложного совпадения с другим файлом,
регрессионный fixture и полный isolated smoke без retries; нагрузка отдельно
квалифицируется со своими порогами. Точечный JUnit этого не заменяет.
См. [harness](../../tools/dev/lifecycle-smoke.sh) и
[эксплуатацию lifecycle](../guides/ru/canonical-record-lifecycle.md).

## Отложенные улучшения и расширения

Эти пункты не обещают реализацию или дату выпуска. Само наличие порта, маленького
модуля или настройки не является дефектом. При активации сохраняйте направление
зависимостей внутрь и владение политиками у сервиса.

<a id="3-экстракция--корпус-ext"></a>
<a id="4-наблюдаемость-obs"></a>
<a id="5-код-смелл-code"></a>
<a id="6-архитектура--модульность-arch"></a>

| ID | Состояние и остаток | Триггер / завершение | Владелец и ссылка |
|---|---|---|---|
| <a id="ing-7"></a>ING-7 | `deferred`: При обновлении мутабельная проекция ещё перечитывает и переписывает весь активный артефакт, O(N); immutable export имеет отдельный cadence | Измеренная стоимость оправдывает incremental/cache или generation policy; сохранить snapshot/expiry/recovery consistency | Хранение/проекция; [экспорт](../dev/ru/artifact-export.md) |
| <a id="ing-8"></a>ING-8 | `deferred`: HTTP driving-adapter use cases; loopback actuator не является REST ingest/TAXII/BFF | Реальный API-потребитель; отдельный адаптер, корреляция запросов и аутентификация non-loopback health/details | Application/web; [архитектура](ARCHITECTURE.md) |
| <a id="out-1"></a>OUT-1 | `deferred`: Общих enrichment providers нет; shipped metadata по умолчанию NULL, но constants/import могут заполнять настроенные поля | Контракт обогащения для потребителя; публичные first/last-seen нельзя смешивать с внутренней lifecycle validity | Processing/domain; [контракт CSV](../dev/ru/csv-output-format.md) |
| <a id="out-2"></a>OUT-2 | `deferred`: CSV quoting не нейтрализует spreadsheet formulas; штатные source markers ограничивают опасные префиксы, будущий произвольный текст может их допустить | Расширенная marker/free-text policy; validate/reject либо явное spreadsheet encoding без скрытого изменения machine-feed values | Вывод/безопасность; [threat model](../THREAT-MODEL.md) |
| <a id="ext-1"></a>EXT-1 | `deferred`: Типов IPv6/email и их сквозных контрактов нет; одного regex недостаточно | Нужен поддерживаемый потребитель; определить тип, нормализацию, классификацию, carrier/export/import и тесты | Domain/processing; [processing](../dev/ru/processing.md) |
| <a id="ext-2"></a>EXT-2 | `partial`: Общий поиск доменов может выделять `.onion`, но точные границы v3 из 56 base32-символов не проверяются | Принять строгую onion validation с положительными/отрицательными границами на обоих regex engines | Извлечение/классификация; [контракт паттернов](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/PatternEngineConfigurationTest.java) |
| <a id="ext-3"></a>EXT-3 | `partial`: Есть golden, boundary и engine-parity корпуса; представительные provider/non-PSL ожидания неполны | Независимые ожидаемые результаты и явная host/provider semantics; ручные примеры вариантов 1/3 не являются oracle варианта 2 | Domain/fixtures; [тестирование](TESTING.md) |
| <a id="obs-4"></a>OBS-4 | `deferred`: Долговечные diagnostic occurrences/report/quarantine сверх синхронной best-effort доставки | Реальная потребность audit/reprocess; определить identity/retention/влияние report failure на outcome; не выводить durable ID из MDC | Diagnostics/application; [наблюдаемость](../dev/ru/observability.md) |
| <a id="obs-5"></a>OBS-5 | `deferred`: Генерируемые Elasticsearch mappings и rollover/reindex migration | Первый поддерживаемый ES/data-stream consumer; mappings из field metadata до индексации, producer fix не исправляет прежний mapping | Observability/consumer; [журналирование](../guides/ru/libraries/logging-slf4j.md) |
| <a id="obs-6"></a>OBS-6 | `deferred`: Неверный тип поля намеренно бросает исключение до проверки уровня; отказоустойчивая logging policy не выбрана | Потребность сервиса в изоляции: видимый ограниченный отказ без рекурсии/раскрытия значения. Текущая строгость — поддерживаемый контракт | Logging/service integration; [тесты LogEvent](../../platform/platform-logging-slf4j/src/test/java/com/iocextractor/platform/logging/LogEventTest.java) |
| <a id="code-1"></a>CODE-1 | `partial`: Convenience constructors удалены; factory/production constructor extraction ещё имеют длинный список зависимостей | Осмысленная связная граница композиции, не объект-параметр ради уменьшения числа аргументов | Composition root; [модули](MODULARIZATION.md) |
| <a id="code-5"></a>CODE-5 | `deferred`: Нет единого явного nullness-контракта/build analyzer; JDT automatic выбирает аннотации по classpath модуля | Принять один постепенный контракт/анализатор с явной nullable-границей; сохранить полезные IDE diagnostics | Java/build; [настройки JDT](../../tools/eclipse-jdt.prefs) |
| <a id="arch-1"></a>ARCH-1 | `deferred`: Рассматривать гранулярность platform-модулей только при доказанной цене зависимостей/выпусков; малый размер не нарушение | Измеренная стоимость сопровождения и сохранение границ; не сливать независимые интеграционные семейства по числу строк | Архитектура/platform; [карта модулей](MODULARIZATION.md) |
| <a id="arch-2"></a>ARCH-2 | `deferred`: PatternEngine остаётся внутренним domain port, не общим platform regex API | Реальный общий внешний потребитель; framework dependencies остаются снаружи | Domain/platform; [архитектура](ARCHITECTURE.md) |
| <a id="arch-3"></a>ARCH-3 | `deferred`: Spring Modulith/canvas — вариант оценки, не установленный seam | Конкретная потребность анализа и полезный сигнал сверх существующих reactor/ArchUnit проверок | Архитектура/build; [тестирование](TESTING.md) |
| <a id="exp-1"></a>EXP-1 | `deferred`: STIX/OpenIOC adapter отсутствует; stixType() metadata не является полной wire model | Реальный потребитель обмена; mapping, identity, version и независимые consumer fixtures | Экспорт/domain; [архитектура](ARCHITECTURE.md) |
| <a id="exp-2"></a>EXP-2 | `deferred`: Нет lifecycle `.deb`; shell installer/deployment поддерживаются | Требование распространения; upgrade/config/permissions/rollback semantics и package tests | Упаковка; [развёртывание](../guides/ru/deployment.md) |
| <a id="ops-2"></a>OPS-2 | `deferred`: Нет поставляемой проектом OS logrotate policy; packaged daemon использует Logback rolling files и journald | Требование host retention; исключить конкурирующих владельцев ротации, проверить permissions/reopen | Эксплуатация/упаковка; [наблюдаемость](../dev/ru/observability.md) |
| <a id="exp-3"></a>EXP-3 | `deferred`: Активные appliance/API-коннекторы сверх файловой SMB-публикации срезов | Конкретный appliance; delivery ledger/retention view потребителя, AND-композиция guards и отдельный driven-adapter | Экспорт/delivery; [ADR 0014](../ADR/0014-event-driven-ingest-to-delivery.md) |
| <a id="exp-4"></a>EXP-4 | `deferred`: DuckDB/Parquet analytics — вариант, не принятый движок | Columnar/analytics consumer и измеренная польза; canonical write authority остаётся у SQLite | Read/export adapters; [хранение](../dev/ru/storage.md) |
| <a id="exp-5"></a>EXP-5 | `deferred`: Immutable export исполняет только complete; append/update требуют больше, чем словарь модели | Durable watermark и явный consumer contract; неподдерживаемый режим отвергается до I/O | Экспорт; [экспорт артефактов](../dev/ru/artifact-export.md) |
| <a id="ops-3"></a>OPS-3 | `partial`: Generic sync не имеет полного capability gate; managed import уже имеет blocking per-source probes/health | Потребность расширить generic preflight; share/ACL принадлежат оператору, producer-denial qualification остаётся OPS-8 | SMB/sync; [import](../dev/ru/dataframe-import.md) |
| <a id="ops-4"></a>OPS-4 | `deferred`: Базовая event/reconcile/keyed координация есть; durable outbox/broker и high/low-water hysteresis нет | Реальная межпроцессная доставка или измеренное давление; события остаются подсказками, ledgers/reconcile — источником истины | Sync/events; [координация](../dev/ru/event-coordination.md) |
| <a id="ops-5"></a>OPS-5 | `deferred`: Retention истории fetch и targeted discovery | Ограничить рост ledger с рассмотренным idempotency retention; оптимизировать listing только по detection-duration evidence | Fetch/storage; [sync](../dev/ru/sync.md) |
| <a id="ops-6"></a>OPS-6 | `deferred`: Внутри endpoint fetch последовательный; параллельность между endpoint есть | Измеренное нарушение SLO batch одной шары; bounded sessions/admission/health, сначала OPS-11/12. Полная parallel safety не доказана | SMB/concurrency; [sync](../dev/ru/sync.md) |
| <a id="src-1"></a>SRC-1 | `deferred`: Tika umbrella parser set шире квалифицированных HTML/PDF/DOCX/XLSX | Измеренная польза размера/безопасности оправдывает потерю best-effort форматов; сравнить явные modules и качество extraction | Source/packaging; [чтение источника](../../adapters/adapter-source-tika/src/main/java/com/iocextractor/adapter/out/source/TikaSourceReader.java) |
| <a id="tool-1"></a>TOOL-1 | `deferred`: Безопасный affected-test accelerator; taxonomy/module targets уже работают | Измеренная цена локального цикла; staged/unstaged/untracked и upstream/downstream closure, полный gate для unknown/shared изменений | Сборка; [тестирование](TESTING.md) |
| <a id="tool-2"></a>TOOL-2 | `deferred`: Нет детерминированного Java formatter/fmt-check; SpotBugs/CPD/PMD существуют отдельно | Отдельный mechanical-change срез после review formatter/diff; включать check после чистого baseline | Сборка/стиль; [качество сборки](../dev/ru/build-quality.md) |
| <a id="tool-3"></a>TOOL-3 | `partial`: Seeded fixtures, isolated runtime, smoke и lifecycle/import/aggregate load tools уже есть; нет общего контракта performance report | Сравнимые oneshot measurements: input/unique/committed counts, diagnostics, время/память, binary/config identity. TOOL-10 и load acceptance отдельно | Инструментарий разработки; [инструменты](../../tools/dev/README.md) |
| <a id="tool-4"></a>TOOL-4 | `deferred`: External suites ещё требуют fixtures оператора; общего capability/provisioning/evidence control нет | До следующего семейства интеграций или обязательного external CI status; критерии ниже | Инфраструктура интеграционных тестов; [внешние тесты](TESTING.md#внешние-и-медленные-проверки) |
| <a id="tool-5"></a>TOOL-5 | `deferred`: PIT pilot намеренно domain-only; нет рассмотренной adoption matrix остальных production scopes | Отдельная test-quality работа или доказанный критичный assertion gap; критерии ниже | Качество тестов/владельцы модулей; [mutation pilot](TESTING.md#диагностические-пилоты) |

### Критерии внешнего harness для TOOL-4

Нужны единый registry suites с lifecycle, capabilities, ожидаемыми counts, timeout,
владельцем, cadence и gate policy; secret-free descriptor подготовленной цели;
readiness/capability checks; изолированный namespace/lease; проверяемая безопасная
очистка при отказе и ограниченный janitor. Credentials поступают только через
secret boundary, не Maven properties или retained reports. Verifier доказывает
равенство ожидаемого и исполненного, отвергает тихие provisioned skips и различает
product/environment/capability/cleanup/inconclusive outcomes. Evidence связывает
commit, версии suite/runner/provider, обезличенную цель, отчёты, diagnostics
и cleanup. Стабильный Make/CI facade должен перевести SMB и второе реальное
семейство интеграций; одних абстракций недостаточно. Offline verify независим.

### Критерии расширения mutation для TOOL-5

Берите текущие production scopes из build configuration, не устаревшее число
21 модуля; TCK не входит в production mutation denominators. Начинайте с чистых
application/platform scopes; integration-heavy adapters/bootstrap не допускаются
по умолчанию. Для каждого scope
фиксируйте adopt/defer/reject, критичное поведение/потребителей тестов и причину.
Допущенные packages/consumers требуют воспроизводимых команд, HTML/XML reports,
commit/tree identity, отсутствия тихих пересечений/пробелов. Измеряйте время,
память, mutation/test-strength signal, NO_COVERAGE и шум survivors; каждому
survivor нужны владелец/disposition. Cadence/gates принимаются после стабильных
pilots и рассмотренной политики. Differential analysis решается отдельно,
включая корректность кеша и лицензирование; pilot не становится безусловной
оценкой каждого PR.

## Закрытые и исключённые пункты

<a id="архив-закрытых-долгов"></a>

Архив сохраняет исходный объём; он не утверждает завершённость соседних
возможностей или ресурсных бюджетов. Датированная квалификация остаётся
датированной.

| ID | Решение | Выполненный результат и свидетельства |
|---|---|---|
| <a id="ing-1"></a>ING-1 | `closed` | Декларативная retention done/failed, delete/archive ports; устаревшая partition retention удалена. [retention](../dev/ru/ingestion.md) |
| <a id="ing-2"></a>ING-2 | `out-of-scope` | Tail/offset/rotation исключён для дискретных документов. Будущему streaming source нужен новый контракт режима, не скрытое обещание. [обработка](../dev/ru/processing.md) |
| <a id="ing-3"></a>ING-3 | `closed` | Actuator/health только в daemon на loopback; REST use-case adapter остаётся ING-8. [эксплуатация демона](../guides/ru/daemon-operations.md) |
| <a id="ing-4"></a>ING-4 | `closed` | Service ledger и canonical JDBC dataframe truth, per-artifact identity и mutable CSV projection; partition staging удалён. [хранение](../dev/ru/storage.md) |
| <a id="ing-4a"></a>ING-4a | `closed` | Долговечные STARTED→DB_COMMITTED→PROJECTION_COMPLETED→COMPLETED и run recovery; межжурнальный partial-run resume остаётся ING-11. [ingestion](../dev/ru/ingestion.md) |
| <a id="ing-5"></a>ING-5 | `closed` | Отдельный aggregation trigger удалён с partition staging; ingestion идёт прямо в canonical storage. [ingestion](../dev/ru/ingestion.md) |
| <a id="ing-6"></a>ING-6 | `closed` | Partition-wrapper boundary удалён; source identity принадлежит application/adapters. [архитектура](ARCHITECTURE.md) |
| <a id="ing-9"></a>ING-9 | `closed` | Retention archive сохраняет относительные пути через baseDir вместо схлопывания имён. [тесты retention](../../core/ioc-application/src/test/java/com/iocextractor/application/maintenance/RetentionServiceTest.java) |
| <a id="ing-10"></a>ING-10 | `closed` | Recovery до poller, source-key guard и монотонные переходы; один daemon, не distributed fencing. Checkpoints f4f011e/c3a03e2/a44d10f/7ce5f8f. [ingestion](../dev/ru/ingestion.md) |
| <a id="ing-12"></a>ING-12 | `closed` | Ограниченные hash retries и типизированная terminal-диагностика нечитаемого источника; физическая судьба pre-claim остаётся ING-13. [ingestion](../dev/ru/ingestion.md) |
| <a id="ing-14"></a>ING-14 | `closed` | Отказы quiet-period возвращаются в WatchService retry set; полный polling остаётся штатным correctness path. [интеграционный тест фильтра](../../adapters/adapter-ingest/src/test/java/com/iocextractor/adapter/in/ingest/IngestFileListFilterIT.java) |
| <a id="ing-17"></a>ING-17 | `closed` | Эквивалентный terminal CAS принимает только неизменные authority/evidence и проверяет registration даже при finalized retry. Регрессии и записанный исправленный прогон 100 DOCX подтверждают закрытие; неуспешные load windows остаются неуспешными. [тесты completion](../../core/ioc-application/src/test/java/com/iocextractor/application/ingest/admission/DocumentAdmissionCompletionTest.java) [история](../dev/ru/native-capacity.md) |
| <a id="ops-1"></a>OPS-1 | `closed` | Исходная проверка нескольких/новых файлов покрыта записанным intake 100 DOCX; deterministic running-daemon/watch тесты заново прошли 2026-11-07. Полный polling по умолчанию, watcher — ускоритель. Multi-endpoint identity/landing остаются OPS-11/12. [тесты демона](../../bootstrap/ioc-app/src/test/java/com/iocextractor/DaemonIngestE2EIT.java) [история batch](../dev/ru/native-capacity.md) |
| <a id="obs-d1"></a>OBS-D1 | `closed` | Диагностики ADR-0017, bounded outcomes, exactly-once runner delivery и prepare→checkpoint→commit; producer ratchet пуст. [наблюдаемость](../dev/ru/observability.md) |
| <a id="obs-d3"></a>OBS-D3 | `closed` | Локальные scalar fields события передаются типизированными SLF4J key/value; ambient correlation принадлежит MDC. Boot 4 encoder сохраняет контракт. [наблюдаемость](../dev/ru/observability.md) |
| <a id="obs-1"></a>OBS-1 | `closed` | Шесть severity→log-level описаны/проверены, включая FATAL/ERROR→error. [наблюдаемость](../dev/ru/observability.md) |
| <a id="obs-2"></a>OBS-2 | `closed` | Непредставимые mutable projection values дают counted value-free OPERATION/WARN в completion; immutable export сохраняет REPORT. [контракт CSV](../dev/ru/csv-output-format.md) |
| <a id="obs-3"></a>OBS-3 | `closed` | Generated log catalog, актуальный action/reference ratchet и doc-sync. [каталог](../LOGGING-CATALOG.md) |
| <a id="obs-7"></a>OBS-7 | `closed` | E2 сохраняет publication failure основной, distinct observer failure — suppressed, включая self/suppression-disabled случаи. [Spring publisher](../../adapters/adapter-events-spring/src/main/java/com/iocextractor/adapter/out/events/spring/SpringControlEventPublisher.java) |
| <a id="obs-8"></a>OBS-8 | `closed` | E2 разделяет delivery/success observation и ограничивает fallback WARN RuntimeException; Error выходит наружу. Синтетический отказ провайдера не является production Logback incident. [Spring publisher](../../adapters/adapter-events-spring/src/main/java/com/iocextractor/adapter/out/events/spring/SpringControlEventPublisher.java) |
| <a id="obs-9"></a>OBS-9 | `closed` | L3-2 перенёс внутренние имена в ioc.event.type, skipped — в ioc.operation.disposition; scalar profile исключает ECS event.type/category. Реальные encoder branches проверены. [migration IT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingMigrationIT.java) |
| <a id="obs-10"></a>OBS-10 | `closed` | L3-2 удалил явный error.type из Throwable-ветки; реальный encoder проверен по bytes/JSON/отсутствию внутренних ошибок, type-only остаётся отдельно. [migration IT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingMigrationIT.java) |
| <a id="cfg-1"></a>CFG-1 | `closed` | Типизированный auto/explicit-long ID-start заменил тихий numeric fallback. [конфигурация](../dev/ru/configuration.md) |
| <a id="cfg-2"></a>CFG-2 | `closed` | Startup cross-validation identity↔sink/schema/registry/id-policy. [конфигурация](../dev/ru/configuration.md) |
| <a id="cfg-3"></a>CFG-3 | `closed` | Legacy сообщение с номером stage удалено со старым aggregation/storage; операторская диагностика использует словарь конфигурации. [отчёты об отказах](../dev/ru/configuration-failure-reports.md) |
| <a id="cfg-4"></a>CFG-4 | `closed` | Strict unknown-key для YAML/CLI/system/env, schema-aware IOC_* matcher и value-free overrides; migration hints сохранены без tombstones. [конфигурация](../dev/ru/configuration.md) |
| <a id="cfg-5"></a>CFG-5 | `closed` | Контролируемые YAML/semantic/Boot reports не раскрывают rejected values/raw causes; произвольный framework DEBUG вне гарантии. [контракт отчётов](../dev/ru/configuration-failure-reports.md) |
| <a id="code-2"></a>CODE-2 | `closed` | Дублирование legacy CSV lookup удалено; поведение принадлежит domain classification и canonical row keys. [обработка](../dev/ru/processing.md) |
| <a id="code-3"></a>CODE-3 | `closed` | Один materialized ClassificationDecision на NETWORK IOC используется providers/filter/TRACE. [обработка](../dev/ru/processing.md) |
| <a id="code-4"></a>CODE-4 | `closed` | Catalog discovery/doc-sync заменили хрупкое утверждение фиксированного числа диагностик. [каталог диагностик](../DIAGNOSTICS-CATALOG.md) |
| <a id="arch-4"></a>ARCH-4 | `closed` | E2 распознаёт Spring Error wrapper и передаёт исходный Error; проверены реальный annotated listener и прямой Error. Сервис владеет listeners/executors. [Events Spring](../guides/ru/libraries/events-spring.md) |
| <a id="exp-6"></a>EXP-6 | `closed` | Publish ledger удерживает unfinished/missing deliveries; только SUCCEEDED/ABANDONED разрешают удаление среза. [экспорт](../dev/ru/artifact-export.md) |
| <a id="ops-7"></a>OPS-7 | `closed` | После completion CanonicalArtifactsChanged вызывает coalesced quiet-period export; interval/reconcile остаются источником истины. ADR-0014 R2. [координация](../dev/ru/event-coordination.md) |
| <a id="ops-9"></a>OPS-9 | `closed` | Exact remote terminal/quarantine purge предшествует CAS local/receipt/ledger cleanup; replay отделён, recursive delete запрещён. Live family breadth остаётся OPS-8. [import](../dev/ru/dataframe-import.md) |

Завершённый контекст также включает framework-free pipeline contracts в
platform-etl, исполнение настроенных source/sink charset и пустой source label
с SOURCE.MARKERS_UNMATCHED вместо старого UNKNOWN.
