# Worknote: слой хранилища (ING-4)

**Статус:** проектирование (диалог). **Ветка:** `feature/data-storage/sqlite`.
**Якорь:** ING-4 в [../techdebt.md](../techdebt.md); сворачивает ING-7 и два
retention-seam'а. **Формат:** живой документ — фиксируем решения и открытые нити
по ходу диалога.

## Верхнеуровневая цель

SQLite = система записи; `*_generated.csv` = **проекция/экспорт** из БД.
Холистический путь ING-4. (зафиксировано ранее в диалоге.)

## Цели

1. Строгое разделение бизнес-логики и инфраструктуры; инфраструктура **не завязана
   на конкретную СУБД** — возможность сменить/добавить хранилище (Postgres/MariaDB
   под датафреймы, Redis — служебное + мета). Конкретная СУБД за границей; общее
   выносится; вариативность на CD (хост ставит одну СУБД по нагрузке/ресурсам).
2. Разделение схем/`.sql`: бизнес-данные (датафреймы + их мета) ↔ служебное (+ мета) — раздельно.
3. Стек: Spring JDBC/JdbcClient vs Spring Data JDBC; миграции — Liquibase.
4. Организация схемы служебных данных.
5. Контракт доступа внешних сервисов к хранилищу.

## Решения (текущие)

- **Порты — storage-neutral** (уже так): `IngestionLedger`, `StableIdIndex`,
  `CanonicalArtifactRepository`, `LookupRepository`, … Бизнес-логика знает только
  порты, не JDBC/СУБД. → цель 1.
- **Реляционные бэкенды — один модуль `adapter-store-jdbc`** (Spring JDBC +
  `JdbcClient`). СУБД выбирается на CD драйвером+профилем; диалектные отличия
  (upsert/identity) — внутренняя стратегия `SqlDialect`; схема — Liquibase.
  Не множим модуль на каждую СУБД. → цели 1, 3.
- **Не-реляционные (Redis и пр.) — отдельный adapter-модуль** на клиентскую либу,
  реализует служебные порты. → цель 1 (Redis как служебное хранилище).
- **Разделение бизнес/служебное — на уровне datasource/схемы**, не только двух
  `.sql`: два Liquibase-changelog корня, два логических datasource (поначалу оба
  SQLite-файла: `ioc-dataframe.db` + `ioc-service.db`). «Бизнес→Postgres,
  служебное→Redis» становится вопросом wiring, а не переписывания. → цель 2.
- **Liquibase** (не Flyway): database-agnostic changesets ложатся на мульти-СУБД
  (Flyway — голый SQL под каждую БД). → цель 3.
- **JdbcClient (Spring JDBC), не Spring Data JDBC**: бизнес-таблицы динамические
  (колонки из конфига, строки `Map<String,String>`) — статичная entity-модель
  Spring Data JDBC с этим конфликтует; upsert `ON CONFLICT` + аллокация id и так
  требуют своего SQL. Единый стиль доступа под бизнес и служебку. → цель 3.
- **Table-per-artifact, DDL из конфига** (`ConfigurableRowMapper.columns`):
  типизированные таблицы без ручного DDL; `row_key TEXT UNIQUE` держит keep-first
  на уровне БД; эволюция колонок — `ALTER TABLE ADD COLUMN`.
- **Транзакции = saga, НЕ глобальная транзакция** (см. «Ревизия после ревью»):
  глобального `UnitOfWork` через два store нет; локальная атомарность внутри store
  (`DataframeUnitOfWork`) + saga из идемпотентных шагов с чекпоинтами. `@Transactional`
  в core/application запрещён (framework-free). → цель 5.
- **Доступ — только через порты**; никакой сервис не трогает JDBC. Чтения:
  индексные point-queries (демон) / снапшот-на-прогон (oneshot batch-дедуп). → цель 5.

## Организация служебной схемы (цель 4)

Моделируем по **stateful-концерну/агрегату, а не по сервису**. Сервисы —
поведение; таблицы — состояние. Большинство внутренних сервисов stateless
(оркестрация) → таблицы нет. Состояние имеют немногие концерны:

- `ingestion_ledger` (+ `ingestion_partition`) — концерн ингеста;
- (шаг 2/3) `aggregation_run`, `export_run` (run-ledger: observability+recovery),
  `legacy_imports` (маркер идемпотентного импорта), `artifact_schema_registry`
  (дрейф config-derived схемы), `retention_run_log` — по мере появления;
- общая мета: Liquibase ведёт свой `DATABASECHANGELOG`; app-level мета — отдельная
  небольшая таблица при необходимости.

Один порт-репозиторий на концерн, владеет своими таблицами в служебной схеме.

## Стадийность (рекомендация)

- **Шаг 1 — служебное состояние** (ledger) → JDBC-стор; idempotent import
  legacy-файлов; партиции/CSV не трогаем; разблокирует retention-seam'ы (запрос
  к ledger вместо обхода ФС).
- **Шаг 2 — бизнес-данные** (canonical table-per-artifact + lookup + oneshot-sink)
  → JDBC truth; CSV — проекция; сворачивает ING-7. Truth нельзя раздваивать:
  oneshot и daemon переводятся на БД одновременно для каждого артефакта.
- **Шаг 3 — payoff**: retention↔aggregation decoupling + per-group count (теперь
  SQL); опц. коллапс партиций (β).

Каждый шаг — отдельный verify-green коммит.

## Решено в диалоге (ратифицировано)

- **O2** — один `adapter-store-jdbc` на все реляционные СУБД. Выбор СУБД = JDBC-драйвер
  на classpath + JDBC-URL/профиль; диалект — стратегия `SqlDialect`, схема — Liquibase.
  Драйвер трактуем как runtime-деталь за `java.sql`, не «обёрнутую» интеграционную либу.
- **O5** — единый `JdbcClient` (без Spring Data JDBC).
- **O3 + O4 — нумерация id становится per-artifact.** У каждой таблицы-артефакта
  своя identity-колонка `id` (`AUTOINCREMENT` SQLite / `IDENTITY` Postgres) +
  `UNIQUE(row_key)`. `INSERT … ON CONFLICT(row_key) DO NOTHING` ⇒ keep-first
  stable id «из коробки»: новой строке БД даёт свежий id, существующая сохраняет свой.
  **Отдельный sidecar `StableIdIndex` растворяется; общий `id_sequence` не нужен.**
  Миграция: импортируем `*_generated.csv` с их текущими id как есть, sequence
  каждой таблицы стартуем за максимумом (старые id не перенумеровываются).
- **O1 — секвенс α/β.** Выбран **α-сначала**: на этой итерации storage переезжает
  под существующий двухшаг (merge → `INSERT ON CONFLICT`), партиции/проход пока
  остаются. Коллапс (β) — следующей итерацией. При коллапсе партиционный механизм
  вычищается **полностью** — без legacy и мёртвого кода (часть acceptance шага-коллапса,
  не «отключили и забыли»).
- **O6 — РЕВИЗИЯ после ревью (checksum drift).** Liquibase — **только** статичная/
  служебная схема (рукописные changeset'ы). Датафрейм-схему ведёт аддитивный
  `SchemaReconciler` (`CREATE TABLE IF NOT EXISTS`/`ALTER ADD COLUMN`), дрейф трекает
  `artifact_schema_registry(artifact, columns_hash, schema_version, applied_at)`.
  НЕ генерим runtime-Liquibase changeset'ы. rename/drop — ручная миграция.
- **O7 — per-artifact `<artifact>_meta` с 1:1 FK** на бизнес-строку (генерим из
  одного шаблона: DRY по DDL + реальная ссылочная целостность).
- **Dimension-нормализация `source`/`threat_type` — отложена** (не шаг 1). Иначе
  бизнес-таблица перестаёт быть чистой проекцией и формирование артефакта обрастает
  join-механикой.

## Найдено по ходу

- **Аномалия глобального счётчика id.** `CsvStableIdIndex` (демон-агрегация) ведёт
  один `nextId` на все артефакты — противоречит задокументированному инварианту
  «независимые id-пространства» (CLAUDE.md) и oneshot-пути (`maxId(artifactName)`).
  Storage-итерация это **чинит** переходом на per-artifact identity (см. O3+O4).

## Конкурентность записи (свойство бэкенда, не приложения)

Принцип: приложение пишем **multithread-ready**, но *как* писать конкурентно
решает адаптер/СУБД за портом. Разделяемого мутабельного счётчика id больше нет —
его даёт identity-колонка БД, поэтому корректность не зависит от числа писателей.

- **Что параллелить:** тяжёлое — **извлечение** (refang/RE2/classify/attribute,
  CPU-bound); именно оно — узкое место на больших файлах. Запись — короткая.
- **SQLite:** много читателей, **один писатель за раз** (lock на уровне БД-файла);
  WAL + `busy_timeout`. Параллельные писатели смысла не имеют (сериализуются на
  локе). Форма: пул экстракторов → **один write-коннект/очередь** коммитит.
- **Server-СУБД (Postgres/…):** параллельные писатели возможны (row-lock +
  identity id) — адаптер может снять сериализацию.
- **Гранулярность транзакции:** дефолт — **транзакция на файл** (идемпотентно по
  content-hash); bulk-батч из inbox — опциональный рычаг throughput, ценой более
  грубого recovery и задержки до первого результата.
- `ioc.ingestion.concurrency` = размер пула **извлечения**; политика записи —
  внутри store-адаптера. (Сейчас concurrency=1, как и было; параллелизм — поздний knob.)
- Следствие: «единый писатель» (docs §10, ради счётчика id) на SQLite теперь —
  лишь физика SQLite, а не требование корректности.

## Схема датафреймов (источник + структура)

**Источник — одна декларация, без второго источника схемы.** Форму артефакта уже
задают `ioc.sink.artifacts[].columns` (имена + порядок + наполнение) и
`ioc.aggregation.artifacts[]` (идентичность → `row_key`). DDL **бизнес-таблицы
выводится из тех же `columns`**, что наполняют CSV-проекцию ⇒ дрейфа БД↔CSV нет.

**Важно: датафреймы — это табличные списки MaxPatrol SIEM**;
набор полей списка зарезервирован контрактом SIEM. Поэтому
`score, time_last_seen, time_first_seen, threat_type, source, description` — это
**бизнес-поля датасета**, а НЕ мета. Их повтор в masks/ip_list/hashes — следствие
того, что все три целятся в один тип списка SIEM; в другом приёмнике их может не быть.
(Ранняя формулировка «мета-конверт» — ошибочна, снята.)

**Структура разносится на два концерна (вертикальное партиционирование):**

1. **Бизнес-таблица на артефакт = контракт приёмника** (SIEM-список): `id` +
   бизнес-индикаторные колонки + `score, time_*, threat_type, source, description`.
   Схема config-derived. `id` (бизнес/SIEM) и `row_key` (цель `ON CONFLICT`) — здесь.
   - Тонкий генератор DDL: по умолчанию `TEXT`, `id INTEGER` identity, `row_key UNIQUE`,
     индекс на идентичность; реальные типы — опциональный `type:`-override.
2. **Операционная мета — ОТДЕЛЬНО, по связи** (1:1 `<artifact>_meta`): *наши*
   lineage-поля `created_at, updated_at, last_seen_at, first_source_key`.
3. **Provenance — 1:N** (`<artifact>_sources`): `row_id, source_key, first_seen_at,
   last_seen_at, occurrences`. Один IOC ∈ нескольких источников — 1:1 терял бы
   provenance (правка после ревью). Схема наша/стабильная → рукописный Liquibase.

Следствия:
- **CSV-проекция чище по построению**: SIEM-список = `SELECT <бизнес-колонки> FROM
  <artifact>`; мета в экспорт не попадает вообще.
- **Provenance (1:N `<artifact>_sources`) впитывает роль партиций** — снимает потерю
  происхождения при коллапсе β (O1); бизнес-значения keep-first, источники аккумулируются.
- Это вертикальное партиционирование **по концерну** (бизнес-контракт ⟂ операционка),
  не нормальные формы. *Настоящая* нормализация (dimension-таблицы для повторяющихся
  `source`/`threat_type` по FK) — опциональный рефайн на потом, не шаг 1.

## Ревизия после ревью (saga, идентичность, политики)

Центральный вывод: **разделяемые хранилища и единая транзакция несовместимы.**
Сохраняем заменяемость store-ов ⇒ workflow = **устойчивая saga**, не один transaction boundary.

- **Транзакции/согласованность.** Нет глобальной транзакции через service-store +
  dataframe-store (тем более Redis). Локальная атомарность *внутри* store
  (`DataframeUnitOfWork`: id+rows+meta+sources одной транзакцией). Глобального
  `UnitOfWork` не вводим — ложная абстракция. Кросс-store = saga из идемпотентных
  шагов + чекпоинты; бэкбон — статус-автомат ledger + run-ledger.
- **Crash-safe protocol:** «upsert бизнес-данных (идемпотентно `ON CONFLICT`) → затем
  mark ledger», повтор безопасен. **Crash-window matrix** перед кодом каждого шага
  (упал после insert row / до meta / до mark / после CSV-temp / до rename → компенсация).
- **Run-ledger:** `aggregation_run`, `export_run` (run_id, started/completed, status,
  affected_rows, csv_checksum) — recovery + observability.
- **Идентичность — одна.** `row_key` = SHA-256 canonical-кортежа **нормализованных**
  output-значений (JSON-array, явные null, определённый first-non-empty); без ручной
  склейки. БД — **единственный авторитет дедупа**; pipeline `contains(Indicator)`
  снимаем/перестраиваем на тот же `ArtifactIdentityResolver`.
- **Контракт id:** stable + unique(per-artifact) + ascending, **НЕ gapless** (см. O8).
- **Схема (ревизия O6):** Liquibase — только статика/служебка; датафрейм-схема —
  аддитивный `SchemaReconciler` + `artifact_schema_registry` (columns_hash). Evolution
  policy: add-column/add-index; rename/drop — ручная миграция.
- **Внешний доступ — контракт, не запрет:** CSV-проекция = публичный интеграционный
  контракт; БД = private detail. Внешним — API/read-model либо read-only versioned
  views; внутренние таблицы наружу не торчат.
- **SQLite runtime policy:** per-connection PRAGMA (WAL, `busy_timeout`,
  `foreign_keys=ON`), малый/один write-pool, maintenance (WAL checkpoint + VACUUM).
- **DB health:** connect + migrations applied + schema_version + PRAGMA (не «директория доступна»).
- **Storage TCK:** общий контракт-тест порта, гоняется на file + JDBC.
- **Legacy import:** маркер `legacy_imports` + upsert до `completed` (не «если пусто»).
- **Конфиг storage-neutral:** селектор `type: file | jdbc`; СУБД — в `url`/driver/profile.

## Открытые нити

- **O8 — gapless id?** Контракт id = stable/unique/ascending, **не gapless**
  (identity/sequence пропускают на rollback/conflict). Достаточно ли это для
  MaxPatrol-списка, или где-то нужен gapless (тогда отдельный counter-allocation —
  дороже, сериализует)? Рекомендую: достаточно unique+stable. → ждёт ответа.

## Шаг 1 — scope (служебное состояние: ledger → JDBC)

**Не трогаем:** бизнес-данные, CSV-артефакты, oneshot, агрегацию/партиции,
stable-id. Только перенос durable-ledger на JDBC-стор. Verify-green, shippable.

- **Модуль `adapters/adapter-store-jdbc`** в reactor; зависит внутрь на
  `core/ioc-application` (порты) + `platform-errors`. Spring JDBC (`JdbcClient`) —
  фреймворк; `sqlite-jdbc` — runtime-драйвер; `liquibase-core` — миграции. Версии — в parent POM.
- **`JdbcIngestionLedger implements IngestionLedger`** на `JdbcClient`: все
  переходы статуса + `find*`. Таблицы `ingestion_ledger` (+ дочерняя
  `ingestion_partition`). Многооператорные методы — в локальной транзакции адаптера
  (полноценный `UnitOfWork`-порт вводим в шаге 2 под многопортовую агрегацию).
- **Служебный datasource + Liquibase:** `ioc.storage.service.url:
  jdbc:sqlite:./var/ioc-service.db`; SQLite PRAGMA (WAL, `busy_timeout`,
  `foreign_keys`); рукописный changelog (служебная схема — hand-written по O6);
  `SpringLiquibase`-бин в bootstrap.
- **Idempotent import legacy** `var/ledger/*.properties` → `ingestion_ledger` через
  маркер `legacy_imports(name, source_path, checksum, status, completed_at)` + upsert
  строк до `completed` (не «если таблица пуста» — падение посередине → частичный импорт).
- **Селектор** `ioc.ingestion.ledger.type: file | jdbc` (НЕ `sqlite` — СУБД в
  `ioc.storage.service.url`/driver) → условный бин в `AppConfig`. oneshot не трогаем
  (datasource поднимается только при jdbc/daemon).
- **ArchUnit:** новый модуль в boundary-правилах (JDBC/SQL/Liquibase заперты в
  adapter+bootstrap; domain/application их не видят).
- **SQLite runtime policy:** per-connection PRAGMA (WAL, `busy_timeout`,
  `foreign_keys=ON` — пер-коннект!), малый/один write-pool.
- **DB health** (вместо «директория доступна»): connect OK + migrations applied +
  schema_version + PRAGMA выставлены — в health-контур (ING-3).
- **Storage TCK:** общий контракт-тест `IngestionLedger`, гоняется на **обоих**
  адаптерах (file + JDBC) — гарантия идентичного поведения (вместо «зеркала»).
- **Crash-window matrix** для импорта + ledger-переходов — перед кодом.
- **Доки:** `ingestion.md` (`ledger.type: jdbc` — реальный), `techdebt.md` ING-4
  (seam → частично).

## Шаг 2 — scope (бизнес-данные → JDBC truth, CSV = проекция)

**Самый рискованный шаг:** переворот системы записи; трогает ОБА пути записи
(daemon-агрегация И oneshot) — нельзя раздваивать truth. Golden e2e переезжает на
проверку проекции.

- **Второй datasource + Liquibase-корень:** `ioc.storage.dataframe.url:
  jdbc:sqlite:./dataframe/ioc-dataframe.db`; второй `SpringLiquibase`-бин.
- **Table-per-artifact, DDL из конфига** (O6-гибрид: выводим из `sink.columns`,
  применяем через Liquibase): бизнес-колонки + `id INTEGER` identity + `row_key UNIQUE`
  + индекс на идентичность; опц. `type:`-override.
- **Per-artifact `<artifact>_meta` (1:1) + `<artifact>_sources` (1:N):** мета и
  provenance отдельно (см. «Ревизия» → provenance). Из общего шаблона.
- **Идентичность одна — `row_key`** = SHA-256 canonical-кортежа нормализованных
  output-значений. БД — **единственный авторитет дедупа** (`ON CONFLICT(row_key)`);
  pipeline-level `LookupRepository.contains(Indicator)` снимаем/перестраиваем на тот
  же `ArtifactIdentityResolver` (не две формулы).
- **`CanonicalArtifactRepository` на JDBC:** merge → `INSERT ON CONFLICT(row_key)
  DO NOTHING` (keep-first + id от identity; контракт id: stable/unique/ascending, не
  gapless). `StableIdIndex`/`CsvStableIdIndex` растворяются (рефакторим `AggregationService`).
- **`LookupRepository` на JDBC:** `maxId`/проверки — индексные SQL вместо загрузки
  всего CSV в память.
- **oneshot-sink → JDBC:** oneshot и daemon переводятся на БД одновременно для
  каждого артефакта (точка «не раздваивать truth»).
- **CSV-проекция:** `*_generated.csv` — производный экспорт из БД (writer-проекция в
  `adapter-sink-csv`, роль меняется с системы записи на писателя проекции).
  **Сворачивает ING-7** (полная регенерация корректна/атомарна, т.к. CSV — производное).
- **Crash-safe aggregation protocol (saga, НЕ глобальная транзакция):** локальная
  транзакция в dataframe-БД (id+rows+meta+sources через `DataframeUnitOfWork`) →
  затем `mark AGGREGATED` в service-БД; повтор идемпотентен. `aggregation_run`/
  `export_run` как чекпоинты. Crash-window matrix — перед кодом.
- **Idempotent import legacy:** `*_generated.csv` (с сохранением текущих id) +
  sidecar `.ioc-id-index.csv` → таблицы; identity-sequence за максимумом.
- **Golden e2e** → проверка проекции (ожидания те же, путь-производитель другой).

## Шаг 3 — scope (payoff, разблокирован 1+2)

- **Retention↔aggregation decoupling:** реапить только `AGGREGATED`-партиции (запрос
  к ledger) вместо голого возраста/количества.
- **Per-group count-retention:** «N новейших на артефакт/группу» — теперь SQL.
- **Опц. коллапс партиций (β):** убрать partition-staging + отдельный проход;
  ингест пишет прямо в canonical-таблицы; provenance из `<artifact>_meta.source_key`.
  **Жёсткое условие:** механизм партиций вычищается полностью, без legacy/мёртвого кода.
