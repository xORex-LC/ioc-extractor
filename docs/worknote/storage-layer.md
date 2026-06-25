# Worknote: слой хранилища (ING-4)

**Статус:** Шаг 1 реализован verify-green до JDBC service ledger + health;
следующий фокус — Шаг 2 (business dataframe truth). **Ветка:** `feature/data-storage/sqlite`.
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
3. Стек: Spring JDBC/JdbcClient vs Spring Data JDBC; миграции — за портом `SchemaMigrator` (O11).
4. Организация схемы служебных данных.
5. Контракт доступа внешних сервисов к хранилищу.

## Решения (текущие)

- **Порты — storage-neutral** (уже так): `IngestionLedger`, `StableIdIndex`,
  `CanonicalArtifactRepository`, `LookupRepository`, … Бизнес-логика знает только
  порты, не JDBC/СУБД. → цель 1.
- **Реляционные бэкенды — один модуль `adapter-store-jdbc`** (Spring JDBC +
  `JdbcClient`). СУБД выбирается на CD драйвером+профилем; диалектные отличия
  (upsert/identity) — внутренняя стратегия `SqlDialect`; схема — за портом `SchemaMigrator` (O11).
  Не множим модуль на каждую СУБД. → цели 1, 3.
- **Не-реляционные (Redis и пр.) — отдельный adapter-модуль** на клиентскую либу,
  реализует служебные порты. → цель 1 (Redis как служебное хранилище).
- **Разделение бизнес/служебное — на уровне datasource/схемы**, не только двух
  `.sql`: два корня миграций (`SchemaMigrator` per role, O11), два логических datasource (поначалу оба
  SQLite-файла: `ioc-dataframe.db` + `ioc-service.db`). «Бизнес→Postgres,
  служебное→Redis» становится вопросом wiring, а не переписывания. → цель 2.
- **Миграции за портом `SchemaMigrator`** (РЕВИЗИЯ O11): на SQLite-only стадии —
  versioned `user_version`-runner (просто, без зависимости). Liquibase (database-agnostic
  changesets, не Flyway) оправдан при мульти-бэкенде — вводим тогда за тем же портом. → цель 3.
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

## Архитектура: компоненты, границы, зоны ответственности

Раскладываем по нашему dependency-rule (Clean Hex + Onion): **зависимости внутрь**,
`domain` ни от чего, `application` — только домен + platform-абстракции, фреймворки
(Spring/JDBC/sqlite-jdbc) **только** в adapters + bootstrap. Конвенцию «один adapter =
одна внешняя либа» здесь читаем **мягче — «один технический механизм / интеграционная
семья»**: `adapter-store-jdbc` оборачивает реляционно-JDBC-семью (Spring JDBC + `java.sql`
+ runtime-драйвер + диалект), а не одну либу — это не нарушение правила, а его точная
формулировка. Сквозной принцип storage-итерации: **policy (что) — в application,
mechanism (как) — в adapter**.

### Инвентарь компонентов (по слоям)

| Компонент | Слой | Роль | Владеет / оборачивает |
|---|---|---|---|
| `AggregationSaga` (рефактор `AggregationService`) | application | **policy**: crash-safe протокол (saga) через порты + run-ledger | — |
| `ArtifactIdentityResolver` (есть) | **application** (port/policy; НЕ domain — `row_key` из output-row + config-identity, а не доменная идентичность IOC → реализация рядом с artifact-mapping) | формула `row_key` из row | `ArtifactRowKey` |
| `SchemaPlanner` (+ `SchemaPlan` VO) | **infra** (НЕ application — adapter; кандидат в `platform-schema`) | **policy**: desired-из-descriptor, diff-классификация, guardrail + O10. **Шаг 2; в Шаге 1 НЕ нужен** (ledger'у хватает static `user_version`-runner) | — (чистый, тест без БД) |
| `LegacyLedgerImporter` | **infra/startup** (в `adapter-store-jdbc` или bootstrap-orchestration) | идемпотентный импорт legacy `.properties` → JDBC ledger **за портом** `IngestionLedger` (application не знает о файловом legacy-формате) | `legacy_imports` |
| `adapter-store-jdbc` (**новый модуль**) | adapter | **mechanism**: весь SQL/диалект/JDBC/PRAGMA/транзакции | Spring JDBC (`JdbcClient`); `sqlite-jdbc` — runtime-драйвер |
| ↳ `JdbcIngestionLedger` | adapter | реализует `IngestionLedger` (application-порт) | `ingestion_ledger` (+ `_partition`) |
| ↳ `JdbcCanonicalArtifactRepository` | adapter | реализует `CanonicalArtifactRepository` | `<artifact>` + `<artifact>_sources` |
| ↳ `JdbcLookupRepository` | adapter | реализует `LookupRepository` (индексные SQL) | читает `<artifact>` |
| ↳ `JdbcSchemaMigrator` | adapter | интроспекция + DDL + `user_version`-runner (**инфра, не application-порт**) | `vN.sql`, `artifact_identity`, `user_version` |
| ↳ `JdbcStorageHealthProbe` | adapter | health → VO: connect, `user_version`, `foreign_keys`, `journal_mode`, `quick_check` | читает PRAGMA/служебку |
| ↳ `JdbcRunLedger`, `JdbcUnitOfWork` | adapter | run-ledger; локальная атомарность | `aggregation_run`/`export_run` |
| ↳ `SqlDialect` (стратегия) | adapter | диалектные отличия (upsert/identity/introspect) | внутренняя |
| `SchemaInitializer` | bootstrap | запуск plan→log→apply на старте (аналог EF `Migrate()`) | гонит `JdbcSchemaMigrator` |
| `StorageHealthIndicator` | bootstrap | оборачивает `JdbcStorageHealthProbe`-VO в Actuator `HealthIndicator` (O12) | — |
| `AppConfig` / `IocProperties` (расширение) | bootstrap | wiring 2× `DataSource` (Hikari + `connectionInitSql`), условный выбор `file\|jdbc`, сборка schema-descriptor из `sink`-config | `ioc.storage.*` |
| CSV-writer (роль меняется) | `adapter-sink-csv` | **проекция из БД** (был системой записи) | `*_generated.csv` |
| storage-`DiagnosticCode`-каталог | platform-diagnostics | типизированные коды (`SCHEMA_*`, `IDENTITY_DRIFT_*`, `MIGRATION_*`, `LEGACY_IMPORT_*`) | — |

### Application-порты (out) — только коллабораторы бизнес-use-case'ов

- **`DataframeUnitOfWork`** — локальная атомарная граница в dataframe-сторе (Шаг 2;
  id+rows+sources одной транзакцией), нужна saga-use-case'у. Глобального `UnitOfWork` нет (saga, не 2PC).
- **`RunLedger`** — `aggregation_run`/`export_run` (чекпоинты саги), коллаборатор saga-use-case'а.
- Существующие остаются: `IngestionLedger`, `CanonicalArtifactRepository`,
  `LookupRepository`. **`StableIdIndex` растворяется** (id даёт identity-колонка, O3/O4).

**Инвариант портов:** в сигнатурах — **ноль JDBC-типов** (`ResultSet`/`Connection`/SQL);
только доменные VO. Иначе протекает mechanism в policy.

### НЕ application-порты — инфраструктурные SPI (правка после ревью, O13)

`SchemaMigrator`/`SchemaPlanner`/`SchemaPlan`, `ArtifactIdentityStore` (`identity_hash`/`epoch`),
`StorageHealthProbe` — их вызывает **bootstrap/schema-initialization контур, а НЕ runtime
application-сервисы**; это startup/lifecycle/infra concern. Тянуть их в `application` =
превратить слой в свалку инфраструктурных SPI. Размещение:
- **Шаг 1** — внутри `adapter-store-jdbc`, оркестрация на старте — `SchemaInitializer`
  (bootstrap); health — `JdbcStorageHealthProbe` (adapter) + `StorageHealthIndicator` (bootstrap).
- **Позже** — если понадобится общий backend-нейтральный контракт, выносим в нейтральный
  **`platform-schema`** (cross-cutting за портом, без доменной связи — как прочие `platform/*`),
  **не** в `application`.

### Зоны ответственности — и почему так

| Зона | Кто | Почему |
|---|---|---|
| saga-оркестрация, формула `row_key`, репозитории-коллабораторы | **application** (use-case policy + порты) | это коллабораторы бизнес-use-case'ов |
| desired-схема, diff-классификация, миграция, identity-guard, health | **infra** (adapter Шаг 1 / `platform-schema` Шаг 2) | startup/lifecycle, **не** бизнес-use-case → не тянем в application (O13) |
| SQL, диалект, интроспекция, транзакции, PRAGMA, `vN.sql`, health-probe | **adapter-store-jdbc** (mechanism) | одна-либа-на-адаптер; своп СУБД = один модуль (O2) |
| wiring, выбор `file\|jdbc`, datasource/pool, биндинг config, Actuator-обёртки | **bootstrap** (композиционный корень) | единственное место, знающее обе стороны; core остаётся framework-free |
| не-реляционное (Redis, future) | **отдельный adapter-модуль** | один-адаптер-одна-либа; реляционное не раздувается |

### Границы (enforced by build, не ревью)

- **ArchUnit:** `java.sql` / `javax.sql` / `org.springframework.jdbc` /
  `org.springframework.transaction` (вкл. `@Transactional` — уже запрещён в core) /
  `com.zaxxer.hikari` / `org.sqlite` / (future) liquibase — только в `adapter-store-jdbc`
  + bootstrap; domain/application их не импортируют. Порты application не несут JDBC-типов
  в сигнатурах. Новый модуль — в layer-правилах.
- **Enforcer:** версии `spring-jdbc`/`sqlite-jdbc`/`HikariCP` — в parent
  `dependencyManagement`; бан дубль-версий.
- **Storage TCK:** контракт-тест каждого порта (`IngestionLedger`,
  `CanonicalArtifactRepository`, …; для инфра-контрактов — `SchemaMigrator`) —
  переиспользуемый abstract-тест в **отдельном `test-support`/TCK-пакете (или маленьком
  модуле), подключаемом адаптерами в test scope** (не Maven test-jar — меньше магии в
  reactor). Адаптеры гоняют его на реальном SQLite (и на file где применимо) — паритет вместо «зеркала».

### Изменения в существующих модулях

- **`adapter-ingest`:** `FileIngestionLedger` остаётся как `ledger.type: file`-альтернатива
  (выбор в bootstrap), не удаляется.
- **`adapter-sink-csv`:** роль writer'а меняется с системы записи на **проекцию из БД** (Шаг 2).
- **`core/ioc-application`:** `AggregationService` рефакторится в saga; `StableIdIndex`/
  `CsvStableIdIndex` растворяются; `LookupRepository.contains(Indicator)` перестраивается
  на `ArtifactIdentityResolver` (одна формула идентичности).

## Организация служебной схемы (цель 4)

Моделируем по **stateful-концерну/агрегату, а не по сервису**. Сервисы —
поведение; таблицы — состояние. Большинство внутренних сервисов stateless
(оркестрация) → таблицы нет. Состояние имеют немногие концерны:

- `ingestion_ledger` (+ `ingestion_partition`) — концерн ингеста;
- (шаг 2/3) `aggregation_run`, `export_run` (run-ledger: observability+recovery),
  `legacy_imports` (маркер идемпотентного импорта), `artifact_identity`
  (`identity_hash`+`epoch`, O10), `retention_run_log` — по мере появления;
- версия формата/служебной схемы — `PRAGMA user_version` (versioned-runner, O11);
  app-level мета — отдельная небольшая таблица при необходимости.

Один порт-репозиторий на концерн, владеет своими таблицами в служебной схеме.

**Два вида «служебных» данных — едут в разные места (уточнено в диалоге).**
«Разделить служебное и бизнес» ≠ «сервисы не пишут рядом с бизнес-строкой».
Различаем по тому, *чему* данные принадлежат, а не «операционные ли они»:

| Вид | Что это | Куда | Связь |
|---|---|---|---|
| (a) состояние **самого приложения** | `ingestion_ledger`, run-логи, config, schema-registry | **служебный** контекст/схема | нет бизнес-строки для привязки |
| (b) операционная мета **о бизнес-строке** | `created_at`/`updated_at`/`last_seen`, провенанс | **вместе с бизнес-данными** (dataframe-стор), по FK | 1:1 / 1:N к бизнес-строке |

Правило: **данные едут за своим контекстом / за сущностью, которой принадлежат.**
Мета о маске → контекст маски (lineage-колонки бизнес-таблицы + `_sources` 1:N в
бизнес-сторе, не в служебной БД). Состояние пайплайна → служебный контекст. Сервис свободно пишет
`updated_at` рядом с бизнес-строкой — это не нарушение разделения.

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
  на classpath + JDBC-URL/профиль; диалект — стратегия `SqlDialect`; схема — за портом
  `SchemaMigrator` (O11: user_version-runner сейчас, Liquibase при мульти-бэкенде).
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
- **O6 — РЕВИЗИЯ (см. §«Управление схемой»).** Датафрейм-схему ведёт аддитивный
  declarative-reconciler через **интроспекцию** (`PRAGMA table_info` ↔ config), **без
  хранимого `columns_hash`/registry** (интроспекция = ground truth). Деструктив
  (drop/rename/тип) → HALT + ручная versioned-миграция (guardrail). Статичная/служебная
  схема — versioned-runner на `user_version` за портом `SchemaMigrator` (Liquibase отложен,
  O11). НЕ генерим runtime-changeset'ы.
- **O7 — РЕВИЗИЯ: 1:1 `<artifact>_meta` свёрнута.** Отдельная 1:1-таблица не
  оправдана: «чистая проекция» даётся config-driven экспортом (явный список колонок),
  а не физическим сплитом; FK/DRY-плюсы решали проблему, созданную самим сплитом.
  Вместо неё — раскладка по оси **immutable vs mutable**: иммутабельная lineage
  (`created_at`, `first_source_key`) — **колонки бизнес-таблицы** с зарезервированным
  внутренним префиксом (исключены из config-списка экспорта; `SchemaReconciler`
  владеет только config-колонками, префикс не считает дрейфом); мутабельная observation
  (`last_seen`, `occurrences`) — в `_sources` (1:N); сводный `last_seen` — view. Итог:
  **2 таблицы/артефакт**, без 1:1-JOIN, `ON CONFLICT DO NOTHING` чист (бизнес-строка
  иммутабельна). `_sources` (1:N) остаётся — это честная нормализация.
- **O9 — выбран B** (два физических стора: `ioc-dataframe.db` + `ioc-service.db`) +
  **сага forward-recovery** (идемпотентная, без компенсаций). Раздельные локи/бэкап/
  заменяемость; кросс-сторовая согласованность — сагой, не глобальной транзакцией. A
  (один стор/единая транзакция/без саги) отклонён.
- **O10 — выбран guard на identity-drift** (см. §«Управление схемой»). Per-artifact
  `identity_hash` (`key-columns`+нормализация) + `epoch`: дрейф авто-детектится, продолжение
  требует явного bump `epoch` + **офлайн** backfill `row_key` в окне (O(строк)). Не
  молчаливое перекеивание. Часть единого guardrail-классификатора.
- **O11 — Liquibase отложен за порт `SchemaMigrator`.** На SQLite-only стадии — простой
  versioned-runner на `user_version` (атомарно, без зависимости). Liquibase оправдан только
  при реальном мульти-бэкенде (DB-agnostic changesets) — вводим тогда, за тем же портом, без
  переписывания вызовов. YAGNI до Postgres. Пересматривает O2/O6 в части «миграции — Liquibase».
- **O8 — РЕШЕНО: `id` = unique + stable + ascending, gapless НЕ требуется** (дыры
  допустимы). Обоснование: B-дереву важна **монотонность** (ascending → append-friendly),
  а не плотность — дыры в значениях индексу ничего не стоят; «дыра в id» ≠ «фрагментация
  страниц» (последнюю даёт `DELETE`, лечит `VACUUM`, не плотность). Gapless конфликтует
  со stability (требует перенумерации) и параллелизмом (требует сериализованной выдачи),
  и недостижим дёшево в шардинге. `AUTOINCREMENT` даёт монотонность+стабильность и
  естественно порождает дыры — это нормально. **Пересмотреть только** при явном
  downstream-требовании плотности (напр. если MaxPatrol трактует `id` как плотный счётчик).
- **O12 — health: probe в адаптере, обёртка в bootstrap.** `JdbcStorageHealthProbe`
  (adapter) отдаёт VO (connect/`user_version`/`foreign_keys`/`journal_mode`/`quick_check`);
  bootstrap оборачивает в Actuator `HealthIndicator`. Никакого ручного JDBC в bootstrap —
  mechanism не течёт в композиционный корень.
- **O13 — миграция/планировщик/identity-guard/health = инфраструктура, НЕ application-порт.**
  Их не вызывает бизнес-use-case → в `application` не тянем (иначе слой = свалка SPI). Шаг 1:
  внутри `adapter-store-jdbc` + `SchemaInitializer` (bootstrap). Позже, при нужде в общем
  контракте — нейтральный `platform-schema`, не application. Application-порты — только
  коллабораторы use-case'ов (`IngestionLedger`/`CanonicalArtifactRepository`/`LookupRepository`/
  `RunLedger`/`DataframeUnitOfWork`). Уточнения ревью: `SchemaPlanner` — **Шаг 2** (в Шаге 1
  не нужен, ledger'у хватает static `user_version`-runner); legacy-импорт — `LegacyLedgerImporter`
  в adapter/bootstrap **за портом** `IngestionLedger` (application не знает о файловом
  legacy-формате); `ArtifactIdentityResolver` — application, **не** domain (`row_key` ≠ доменная
  идентичность IOC); правило «один adapter» читаем как «один технический механизм / семья».
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
  внутри store-адаптера. **Уточнение (проверено по коду):** демон сейчас
  **однопоточный** — `ioc.ingestion.concurrency: 1` в конфиге есть, но **никуда не
  подключён** (нет `TaskExecutor`/`ExecutorChannel`/пула в адаптере); поллер
  обрабатывает файлы последовательно на одном поллер-потоке. Это
  зарезервированный knob на будущее. Следствие: довод «раздельные write-локи
  SQLite помогают параллельным писателям» — **выгода будущего, не текущая**;
  пока писатель один, локи не дерутся. Для «зачем два файла сейчас» остаются
  только раздельный retention/бэкап и заменяемость, lock-аргумент отложен.
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

**Структура — два концерна (РЕВИЗИЯ O7: 1:1 `_meta` свёрнута):**

1. **Бизнес-таблица на артефакт = контракт приёмника** (SIEM-список): `id` +
   бизнес-индикаторные колонки + `score, time_*, threat_type, source, description`.
   Схема config-derived. `id` (бизнес/SIEM) и `row_key` (цель `ON CONFLICT`) — здесь.
   **+ иммутабельные lineage-колонки** (`created_at`, `first_source_key`) с
   зарезервированным внутренним префиксом: пишутся раз при вставке (keep-first им не
   противоречит), исключены из config-списка экспорта.
   - Тонкий генератор DDL: по умолчанию `TEXT`, `id INTEGER` identity, `row_key UNIQUE`,
     индекс на идентичность; реальные типы — опциональный `type:`-override.
   - `SchemaReconciler` владеет **только config-колонками**; reserved-prefix не считает дрейфом.
2. **Provenance / observation — 1:N** (`<artifact>_sources`): `row_id, source_key,
   first_seen_at, last_seen_at, occurrences`. Один IOC ∈ нескольких источников — 1:1
   терял бы provenance. Сюда же — **мутабельная observation** (`last_seen`,
   `occurrences`), которой не место в keep-first бизнес-строке. Сводный `last_seen` —
   view (`MAX(last_seen) FROM _sources`). Схема наша/стабильная → рукописный versioned-runner (`vN.sql`, O11).

Следствия:
- **CSV-проекция чище по построению**: SIEM-список = `SELECT <бизнес-колонки> FROM
  <artifact>`; мета в экспорт не попадает вообще.
- **Provenance (1:N `<artifact>_sources`) впитывает роль партиций** — снимает потерю
  происхождения при коллапсе β (O1); бизнес-значения keep-first, источники аккумулируются.
- Это вертикальное партиционирование **по концерну** (бизнес-контракт ⟂ операционка),
  не нормальные формы. *Настоящая* нормализация (dimension-таблицы для повторяющихся
  `source`/`threat_type` по FK) — опциональный рефайн на потом, не шаг 1.

**Почему 1:1 `_meta` свёрнута (РЕВИЗИЯ O7, уточнено в диалоге):** «чистая проекция»
**не требует** физического 1:1-сплита — экспорт config-driven (явный список колонок),
мета не протекает просто потому, что её нет в config-списке. FK/DRY-плюсы 1:1 решали
проблему, созданную самим сплитом; по нормальной форме одно-значный 1:1-атрибут и так
может быть колонкой бизнес-таблицы. Правильная ось — не «бизнес vs мета», а **immutable
vs mutable**: иммутабельная lineage → колонки бизнес-таблицы; мутабельная observation
→ `_sources`. Остаётся **2 таблицы/артефакт**, без 1:1-JOIN.
**`_sources` (1:N) — настоящая нормализация** (IOC ∈ нескольких документов), остаётся.

## Схемы данных (конкретный DDL)

Целевой DDL (диалект — SQLite). Конвенции: timestamps — `TEXT` ISO-8601 (SQLite не
имеет native datetime; ISO-8601 сортируется лексикографически); enum'ы — `TEXT`
(имя константы); бизнес-колонки по умолчанию `TEXT`, опц. `type:`-override.

### Служебная БД (`ioc-service.db`) — versioned, рукописные `vN.sql` (O11)

```sql
-- Шаг 1 — durable ledger ингеста (отражает IngestionRecord/IngestionStatus)
CREATE TABLE ingestion_ledger (
    source_key      TEXT PRIMARY KEY,          -- SourceKey.value (lowercase content-hash)
    status          TEXT NOT NULL,             -- CLAIMED|PARTITION_WRITTEN|LEDGER_RECORDED|
                                               --   SOURCE_ARCHIVED|AGGREGATED|FAILED
    original_path   TEXT NOT NULL,
    processing_path TEXT NOT NULL,
    archived_path   TEXT,                      -- nullable (done/failed dest)
    detected_at     TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    reason          TEXT                       -- nullable failure reason
);
CREATE INDEX ix_ledger_status ON ingestion_ledger(status);   -- retention/recovery-запросы

-- партиции, произведённые для источника (1:N)
CREATE TABLE ingestion_partition (
    source_key     TEXT NOT NULL REFERENCES ingestion_ledger(source_key) ON DELETE CASCADE,
    partition_path TEXT NOT NULL,
    PRIMARY KEY (source_key, partition_path)
);

-- Шаг 1 — маркер идемпотентного импорта legacy var/ledger/*.properties
CREATE TABLE legacy_imports (
    name         TEXT PRIMARY KEY,
    source_path  TEXT NOT NULL,
    checksum     TEXT NOT NULL,
    status       TEXT NOT NULL,                -- IN_PROGRESS | COMPLETED
    completed_at TEXT
);

-- Шаг 2/3 — run-ledger (чекпоинты саги: recovery + observability)
CREATE TABLE aggregation_run (
    run_id        TEXT PRIMARY KEY, started_at TEXT NOT NULL, completed_at TEXT,
    status        TEXT NOT NULL, affected_rows INTEGER
);
CREATE TABLE export_run (
    run_id        TEXT PRIMARY KEY, artifact TEXT NOT NULL, started_at TEXT NOT NULL,
    completed_at  TEXT, status TEXT NOT NULL, csv_checksum TEXT
);

-- Шаг 2 — guard identity-drift (O10); per-artifact «user_version для формулы»
CREATE TABLE artifact_identity (
    artifact      TEXT PRIMARY KEY,
    identity_hash TEXT NOT NULL,               -- hash(key-columns + нормализация)
    epoch         INTEGER NOT NULL DEFAULT 1,
    applied_at    TEXT NOT NULL
);
-- версия формата служебной схемы — PRAGMA user_version (не таблица)
```

### БД датафреймов (`ioc-dataframe.db`) — Шаг 2

Бизнес-таблица **config-derived** (DDL генерит declarative-reconciler из
`sink.artifacts[].columns`). **Reserved-prefix `_`** = внутренняя lineage (config-колонки
с `_` не начинаются): принадлежит versioned-мигратору, **исключена из CSV-экспорта и из
config-drift проверки**.

```sql
-- ШАБЛОН бизнес-таблицы (генерируется):
CREATE TABLE <artifact> (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,  -- O8: unique+stable+ascending, дыры OK
    <config-колонки …>                                -- TEXT по умолч., опц. type-override
    row_key       TEXT NOT NULL UNIQUE,               -- O3/O4: цель ON CONFLICT
                                                       --   = SHA-256 канон. кортежа identity
    _created_at   TEXT NOT NULL,                       -- reserved lineage (иммутабельна)
    _first_source_key TEXT                             -- reserved lineage
);

-- ПРИМЕР: masks (config identity = [mask]); экспорт = id..description (без row_key/_*):
CREATE TABLE masks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mask TEXT, url_match TEXT, host_match TEXT, score TEXT,
    time_last_seen TEXT, time_first_seen TEXT, threat_type TEXT, source TEXT, description TEXT,
    row_key TEXT NOT NULL UNIQUE,
    _created_at TEXT NOT NULL, _first_source_key TEXT
);

-- provenance/observation (1:N, рукописный — наша стабильная схема):
CREATE TABLE masks_sources (
    row_id        INTEGER NOT NULL REFERENCES masks(id) ON DELETE CASCADE,
    source_key    TEXT NOT NULL,                       -- source-атрибуция
    first_seen_at TEXT NOT NULL,
    last_seen_at  TEXT NOT NULL,
    occurrences   INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (row_id, source_key)
);

-- сводный last_seen — view, не колонка:
CREATE VIEW masks_last_seen AS
  SELECT row_id, MAX(last_seen_at) AS last_seen_at FROM masks_sources GROUP BY row_id;
```

Остальные артефакты (`ip_list`, `address_blacklist`, `hashes`) — по тому же шаблону;
`address_blacklist` без `id`/`_*` по своему config (это адрес-блоклист, не SIEM-список с id).
CSV-проекция артефакта = `SELECT <config-колонки> FROM <artifact> ORDER BY id`.

## Эволюция схемы (смена бизнес-полей) — две оси, разное поведение

Две независимые оси конфига; «обновить список бизнес-полей» затрагивает их по-разному:
- **Форма** — `ioc.sink.artifacts[].columns` (DDL таблицы + колонки/порядок CSV-проекции);
- **Идентичность** — `ioc.aggregation.artifacts[].key-columns` (входит в `row_key`:
  дедуп, keep-first, stable id). Сейчас identity — **подмножество** колонок
  (`masks→[mask]`, `ip_list→[ip]`, `hashes→[hash_md5,sha1,sha256]` first-non-empty);
  `score/time_*/description` в идентичность **не входят**.

**Матрица (как спроектировано, Шаг 2; `SchemaReconciler` по O6 — только аддитивный):**

| Изменение | Поведение | Миграция |
|---|---|---|
| Добавить **не-identity** колонку | `ALTER ADD COLUMN` (дифф интроспекцией config↔БД); у старых строк `NULL`, у новых заполняется; идентичность и id стабильны | нет (авто) |
| Переупорядочить колонки | чистый конфиг: порядок CSV по config-списку, порядок колонок в БД не важен | нет |
| **Удалить / переименовать** колонку | guardrail: колонка в БД, нет в config → **HALT** на старте (не молчаливое сиротство); деструктив требует кода | **ручная** versioned-миграция |
| **Сменить состав `key-columns`** или нормализацию identity-колонки | `row_key` того же IOC становится другим ⇒ `ON CONFLICT` не матчит ⇒ keep-first ломается, дубли, новый id на известный IOC; сегодня вдобавок рассинхрон `.ioc-id-index.csv` | **смена эпохи идентичности** — см. O10 |

Сегодня (до storage): CSV с полной регенерацией каждый прогон (ING-7), identity через
`key-columns` + `.ioc-id-index.csv`; для презентационных колонок — то же (меняется
заголовок на следующей регенерации, у старых строк новая колонка пустая).

## Управление схемой — сводка (versioned ↔ declarative + guardrail) (ратифицировано)

Консолидация дискуссии. Всё управление схемой раскладывается по двум осям.

**Ось A — кто инициирует:** код → **versioned** (императивные миграции); конфиг →
**declarative** (state-based реконсиляция). **Ось B — безопасность (= модель
безопасности):** наращивание (`CREATE TABLE`, `ADD COLUMN`) → авто; сужение/переформирование
(drop/тип/rename/идентичность) → **всегда эскалирует** в код/явную миграцию. «Без кода»
работает только в безопасную сторону.

**Карта: какая схема — каким механизмом:**

| Схема | Инициирует | Механизм | Версионирование |
|---|---|---|---|
| формат хранилища + служебная (ledger, `_sources`, registry) | код | versioned runner (`vN.sql` в атомарной транзакции) | `PRAGMA user_version` (int/файл) |
| бизнес-колонки (table-per-artifact из `sink.columns`) | конфиг | declarative reconciler, **additive-only** | **интроспекция** `PRAGMA table_info` ↔ config (без хранимого хеша) |
| идентичность артефакта (`key-columns`+нормализация) | конфиг | declarative detect + явная авторизация | `identity_hash` + `epoch` (per-artifact) |

**Guardrail — единый diff-классификатор** (допустимое авто-множество = `{CREATE TABLE, ADD COLUMN}`):

| Diff (config ↔ факт БД) | Действие |
|---|---|
| колонки/таблицы нет в БД | `ADD COLUMN`/`CREATE TABLE` — авто |
| колонка в БД, нет в config (не reserved-prefix) | **HALT** — drop-намерение → ручная versioned-миграция |
| разный тип/констрейнт, rename | **HALT** |
| смена `key-columns`/нормализации | **HALT** → `epoch`+backfill `row_key` (O10) |

- **Fail-fast на старте** (схема config-derived → compile-time у нас нет); сдвиг влево —
  **`--validate-schema`/dry-run в CI** (ловим деструктив до деплоя).
- Отказ — **типизированная диагностика** (`platform-diagnostics`, напр.
  `SCHEMA_DESTRUCTIVE_CHANGE_REQUIRES_MANUAL_MIGRATION`), не голое исключение.
- **reserved-prefix**: внутренние lineage-колонки принадлежат versioned-мигратору,
  исключены из config-drift проверки (иначе ложные срабатывания каждый старт).
- `epoch` — per-artifact аналог `user_version` для семантики формулы (глобальный
  `user_version` им быть не может: один на файл, не знает про формулу `row_key`).

**Атомарность/оговорки (SQLite):** SQLite = **transactional DDL** → `BEGIN; <ddl>;
PRAGMA user_version=N; COMMIT` атомарно (упал → rollback, версия не сдвинулась). Но:
(1) «предсказуемо по времени» только для чистого DDL — backfill = O(строк), **офлайн в
окне**; (2) ALTER ограничен (тип/констрейнт → 12-шаговый rebuild таблицы, тоже в транзакции).

**Раскладка по dependency rule** (не один монолитный сервис, не «зашить в адаптер»):

```
application  SchemaPlanner   — политика: desired-из-config, классификация diff, guardrail+O10.
                              storage-neutral, чистый, тест без БД. → SchemaPlan.
             port: SchemaMigrator { introspect; apply(plan) }
adapter-jdbc JdbcSchemaMigrator — механика: интроспекция + DDL через SqlDialect +
                                  user_version-runner.
bootstrap    SchemaInitializer  — на старте: plan → ЛОГ → apply (аналог EF Migrate()).
```

Поза: **plan → log → apply** (стиль Atlas/Terraform). `SchemaMigrator` — порт, за которым
реализация заменяема (O11: сейчас user_version-runner, Liquibase отложен).
**Терминология:** versioned/migration-based ↔ declarative/state-based; reconciliation loop;
metadata-driven schema; additive-only auto-migration с guardrail.

## Тестирование миграций (ПЕРВЫЙ приоритет — хрупкая зона) (ратифицировано)

Миграции/реконсиляция — самая хрупкая часть; **главный инвариант — отсутствие потери
данных**. Без исчерпывающего покрытия код миграций не мёржится. Тесты — часть `verify`
(реальный гейт). Гоняем на **реальном временном SQLite-файле** (не мок — поведение
SQL-специфично), быстро (embedded). Каркас — расширенный **Storage TCK** (один контракт-
тест на file+JDBC, позже +Postgres).

**Базовый приём — snapshot до/после:** перед миграцией снимаем (row-count + checksum
содержимого на таблицу), после — сверяем; любой diff должен быть в явном allowlist
ожидаемого. Сид — репрезентативные + краевые данные (UTF-8/cp1251-наследие, NULL,
максимальный id, дубли по `row_key`, большой объём для backfill).

**Сценарии (минимальный обязательный набор):**

1. **Versioned-runner (служебка/формат):** fresh (`user_version=0`)→v1..vN; частичный
   (`=k`)→только v(k+1)..vN; уже актуальный (`=N`)→no-op; **атомарность** — инъекция
   ошибки в середине vN → rollback, `user_version` и схема не сдвинулись (transactional
   DDL); идемпотентный повтор; downgrade (`db>target`) → отказ старта, без мутаций.
2. **Declarative-reconciler (бизнес-колонки):** add column → `ALTER ADD COLUMN`, старые
   строки NULL, **данные целы** (count+values); reorder → без DDL/без изменений; новый
   артефакт → `CREATE TABLE`; колонка в БД не в config → **HALT + типизированная
   диагностика, НИ одной мутации**; смена типа/констрейнта → HALT; rename → HALT;
   reserved-prefix-колонка не в config → **НЕ флагается** (нет false-positive);
   `--validate-schema` dry-run обнаруживает деструктив **без** мутации.
3. **Identity-drift (O10):** смена `key-columns` → дрейф обнаружен → HALT; bump `epoch`
   + backfill → `row_key` пересчитан по всем, keep-first-коллизии схлопнуты
   детерминированно, id выживших сохранены, **без потерь и дублей**; **атомарность
   backfill** — краш в середине → resume/rollback, без частичного перекеивания;
   коллизия (две строки → один новый `row_key`) → keep-first побеждает, исход детерминирован.
4. **Legacy-import (CSV→JDBC):** сохранение текущих id (без перенумерации);
   identity-sequence за максимумом; **идемпотентность** — повтор после краша посередине
   (маркер `legacy_imports`) → дозавершает, без дублей; пусто/нет файла → чистый fresh.
5. **Crash-window matrix → тесты:** каждое окно матрицы (упал после insert row / до mark
   ledger / после CSV-temp / до rename) — отдельный тест: повтор идемпотентен, recovery сходится.
6. **Property-based/fuzz:** случайные последовательности аддитивных config-изменений +
   данные → инварианты (нет потерь, id стабильны, проекция == БД) — ловит незаписанные комбинации.
7. **Round-trip/golden:** прогон через N миграций → CSV-проекция == golden; данные переживают серию.

**Acceptance-гейт:** ни одна миграционная фича не мёржится без своих сценарных тестов +
crash-window тестов; snapshot-инвариант «no data loss» — обязательная ассерта каждого.

## Интеграция с Diagnostics & Observability (ратифицировано)

**Порты:** `DiagnosticSink` (`emit(Diagnostic)`) — **порт** (есть `Collecting`/`Noop`/
`LoggingDiagnosticSink`). Логирование — **НЕ порт**: утилиты `LogEvents`/`LogEvent` +
`MdcScope` + `LogField`/`EventAction`/`EventOutcome` поверх SLF4J + `PipelineObserver`.
Отдельный logging-порт **не вводим**.

**Принцип:** policy-код (application: `AggregationSaga`) → эмитит **диагностики** через
`DiagnosticSink`, **не** логирует напрямую (`LoggingDiagnosticSink` мостит в ECS-логи).
Edge-код (adapter-store-jdbc, `SchemaInitializer`, health) → диагностики **и** оперативные
логи через `LogEvents` (край, фреймворк разрешён). SLF4J/`LogEvents` в core — нет.

**Чек-лист (помимо самих кодов и списка событий):**

1. **Новая `DiagnosticCategory.STORAGE`** (enum закрыт — добавить). Каталоги
   `StorageDiagnosticCodes`/`SchemaDiagnosticCodes` в `platform-diagnostics/codes`
   (данные, не порт). Подгруппы: `STORAGE.MIGRATION_*`/`LEDGER_*`/`IDENTITY_*`/`IMPORT_*`.
2. **Severity → поведение** (`FailurePolicy`/`DiagnosticException`, важнее самих кодов):
   startup-fatal (identity-drift HALT, migration-fail, downgrade, destructive) → `FATAL` +
   abort boot (`failFast`); per-row backfill/import → `collectAndContinue` (`ERROR` копим);
   ops (mmap off на WSL, медленный checkpoint) → `WARN`.
3. **MDC/корреляция** — добавить `LogField`: `IOC_DB_ROLE`, `IOC_SCHEMA_VERSION`
   (`user_version`), `IOC_MIGRATION_VERSION`, `IOC_IDENTITY_EPOCH`, `IOC_AFFECTED_ROWS`.
   **Один `run_id`** (`IOC_RUN_ID`) в MDC ↔ `aggregation_run.run_id`.
4. **`EventAction`/`EventOutcome`** — добавить actions `SCHEMA_MIGRATE`, `SCHEMA_VALIDATE`
   (dry-run), `LEDGER_IMPORT`, `DB_HEALTH`, `MAINTENANCE`, `BACKFILL`; для идемпотентного
   replay — outcome `retry`/`skipped` (повтор саги не плодит «ERROR»).
5. **run-ledger ⟂ логи — не дублировать роли:** `aggregation_run`/`export_run` durable
   (recovery), ECS-логи ephemeral; связь — общий `run_id`.
6. **Health vs diagnostics — разделить:** Actuator pull (DOWN при `quick_check` fail) vs
   push-события. Сбой эмитит **и** диагностику (в момент), **и** делает Health DOWN.
7. **Шум/кардинальность/чувствительность:** на `INFO` **не** логировать значения IOC —
   только счётчики/`artifact`/`run_id`; backfill — прогресс **пачками**, не построчно;
   построчный trace gated `ioc.observability.per-item-trace-enabled`.
8. **Тестируемость:** `CollectingDiagnosticSink` в сценарных тестах миграций — **ассертить
   эмитнутый код**, не только поведение (guardrail→`SCHEMA_DESTRUCTIVE_*`; drift→`IDENTITY_DRIFT`;
   partial import → идемпотентно, без дублирующих FATAL).
9. **Wiring/границы:** `DiagnosticSink` + `DiagnosticFactory(clock)` инжектятся в
   storage-компоненты; правки `LogField`/`EventAction`/категории — в `platform-*` (не core).
   ArchUnit: storage-адаптер зависит от `platform-diagnostics`/`platform-observability` (ок).
10. **Шаблоны сообщений** кодов — с параметрами (`{artifact}`, `{from}->{to}`, `{reason}`);
    кормят `DiagnosticCatalog`/рендер в генерируемые доки — продумать параметры сразу.

## SQLite: тюнинг и конфигурируемость (ратифицировано)

**Принцип:** конфигурируемо то, что **варьируется по развёртыванию** (железо/нагрузка);
**инварианты корректности зашиты в адаптер**. «Всё в конфиг» у нас — про
source/sink/policy (домен); PRAGMA — инфраструктурный перф адаптера, тащить сюда
буквально нельзя (config sprawl, foot-guns). Уровень `sqlite3_config()` (covering-index-
scan, аллокаторы, threading mode) из JDBC недостижим — данность; наш рычаг там —
**покрывающие индексы**, а не флаги.

**Три тира владения:**

| Тир | Что | Куда | Владелец |
|---|---|---|---|
| 1. инварианты корректности | `journal_mode=WAL`, `foreign_keys=ON`, `encoding=UTF-8`, пол `synchronous=NORMAL`, пол `busy_timeout` | **код адаптера** (на попытку опустить ниже пола — кламп + WARN) | разработчик |
| 2. перф/ops (зависит от хоста) | `cache_size`, `mmap_size`, `temp_store`, `wal_autocheckpoint`, `journal_size_limit`, maintenance, пул | **конфиг** (пресет + overrides) | оператор |
| 3. топология/коннект | `url`/path, `type: file\|jdbc`, профиль | **конфиг** (уже в scope) | оператор |

**Конфиг — backend-scoped** (не прибивать storage-neutral верх): SQLite-специфика в
`ioc.storage.<role>.sqlite.*`; Postgres этот подузел игнорирует.

```yaml
ioc:
  storage:
    dataframe:
      type: jdbc                          # storage-neutral селектор
      url: jdbc:sqlite:./dataframe/ioc-dataframe.db
      sqlite:
        tuning: balanced                  # ПРЕСЕТ: low-memory | balanced | high-throughput
        overrides: { cache-size: -32000 } # тонкая правка поверх пресета (редко)
        maintenance:
          checkpoint: after-aggregation   # off | autocheckpoint | after-aggregation
          optimize-on-close: true
          incremental-vacuum: true        # после retention-DELETE
          vacuum: { schedule: weekly }    # эксклюзивный лок → в окно
      pool: { write-max: 1, read-max: 4 }
    service:
      type: jdbc
      url: jdbc:sqlite:./var/ioc-service.db
      sqlite: { tuning: low-memory }
```

**Тир 1 — инварианты (все пресеты, из кода).** Persistent (один раз, до первого
`CREATE`): `journal_mode=WAL`, `auto_vacuum=INCREMENTAL`, `encoding='UTF-8'`.
Per-connection (в `connectionInitSql`): `foreign_keys=ON` (SQLite сбрасывает в OFF на
КАЖДОМ соединении!), `synchronous=NORMAL` (пол — обоснован идемпотентной saga: потеря
последней не-зачекпойнченной txn на power-loss = повтор шага, без corruption),
`busy_timeout≥5000`.

**Тир 2 — пресеты `tuning` (per-connection перф):**

| PRAGMA | low-memory | balanced | high-throughput |
|---|---|---|---|
| `cache_size` | `-2000` (≈2 MiB) | `-16000` (≈16 MiB) | `-65536` (≈64 MiB) |
| `mmap_size` | `0` (off) | `134217728` (128 MiB) | `536870912` (512 MiB) |
| `temp_store` | `DEFAULT` (file) | `MEMORY` | `MEMORY` |
| `wal_autocheckpoint` | `1000` | `1000` | `2000` (реже во время burst; добиваем `wal_checkpoint(TRUNCATE)` после агрегации) |
| `journal_size_limit` | `8388608` (8 MiB) | `67108864` (64 MiB) | `268435456` (256 MiB) |

Дефолты ролей: **service → low-memory** (мелкий ledger), **dataframe → balanced**
(оператор поднимает до high-throughput на большом хосте). `overrides:` — точечная правка
поверх пресета. Опасные PRAGMA (`synchronous=OFF`, `foreign_keys=OFF`,
`journal_mode=DELETE`) пресетами/overrides **не выдаются**; если уж нужно — отдельный
`unsafe-overrides:` с явным WARN «unsupported, at your own risk».

**Maintenance** (операционное, рядом с `ioc.maintenance.retention`): `checkpoint`
(after-aggregation для dataframe), `optimize-on-close` (мягкий ANALYZE для планировщика),
`incremental-vacuum` (после retention-DELETE, раз `auto_vacuum=INCREMENTAL`), `VACUUM` по
расписанию в окно. Health: `PRAGMA quick_check` в DB-health-контур (ING-3). Бэкап:
`VACUUM INTO 'snapshot.db'` (консистентно + дефрагментация; разный каданс на два файла).

**Пул — родной Hikari**, не своя машинерия: два datasource (service/dataframe),
`write-max=1` (физика SQLite — один писатель), read-pool побольше. Перф-PRAGMA тира 2 +
инварианты тира 1 кладёт `connectionInitSql`.

**Валидация/наблюдаемость:** биндить `@ConfigurationProperties @Validated` с границами
(диапазон cache_size и пр.); на старте **логировать эффективный набор PRAGMA**.

**WSL2 (наш рантайм):** файлы БД — только на Linux-ФС (`/opt/...` ext4, как сейчас),
**никогда `/mnt/c`** (9p/DrvFs ломает file-locking и fsync — это корректность, не только
скорость); `mmap_size` замерять (на нестандартных ФС эффект непредсказуем), для
low-memory `mmap=0`.

## Логическое vs физическое разделение (происхождение саги)

**Прояснение в диалоге, переакцентирует §«Цели»/§«Решения».** «Служебная БД» =
**ограниченный контекст (bounded context)**, разделение *по смыслу*, а **не** выбор
бэкенда. Бэкенд может быть один и тот же (оба SQLite, оба Postgres) — суть в
изоляции контекстов: свои таблицы/порты, никаких FK/JOIN служебное↔бизнес. Раннее
«бизнес→Postgres / служебка→Redis» — лишь *пример возможности*, а не предпосылка.

Ключ, расставляющий сагу по местам:

| | Что это | Что требует |
|---|---|---|
| **логическое** разделение (цель) | раздельные контексты, без FK/JOIN между ними | дисциплина границ; **сагу НЕ требует** |
| **физическое** разделение | разные коммит-единицы (2 файла SQLite, 2 БД, 2 бэкенда) | нет общей транзакции → **нужна сага** (или 2PC) |

**Сага возникает из физического раскола, не из логического.** Логической цели можно
достичь внутри одного физического стора, сохранив единую транзакцию:
- Postgres: одна БД, две схемы → контексты разделены, `BEGIN…COMMIT` накрывает обе.
- SQLite: один файл, две группы таблиц → одна транзакция на оба контекста.

**SQLite-нюанс (важно для нашего выбора WAL).** В SQLite нет `CREATE SCHEMA`:
1 файл = 1 БД = 1 пространство имён. Несколько «схем» эмулируются через
`ATTACH DATABASE` (префиксы `service.`/`business.`). Атомарность кросс-файловой
транзакции: в **rollback-journal** — да (master-journal); в **WAL** — **нет**
(«атомарна для каждой базы по отдельности, но не для набора как целого»). Мы
выбрали **WAL**, значит «один бэкенд SQLite, но два файла» **уже не даёт сквозной
транзакции** ⇒ сага оправдана даже без разных бэкендов.

**Развилка A/B — РЕШЕНО: B** (O9). Два физических стора (`ioc-dataframe.db` +
`ioc-service.db`) + сага forward-recovery (идемпотентная, без компенсаций). Раздельные
локи/бэкап/заменяемость; кросс-сторовая согласованность — сагой. A (один стор/единая
транзакция/без саги) отклонён.

## Ревизия после ревью (saga, идентичность, политики)

Центральный вывод: **разделяемые хранилища и единая транзакция несовместимы.**
Сохраняем заменяемость store-ов ⇒ workflow = **устойчивая saga**, не один transaction boundary.

- **Транзакции/согласованность.** Нет глобальной транзакции через service-store +
  dataframe-store (тем более Redis). Локальная атомарность *внутри* store
  (`DataframeUnitOfWork`: id+rows+sources одной транзакцией; lineage — колонки rows).
  Глобального
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
- **Схема (ревизия O6/O11, см. §«Управление схемой»):** служебка — versioned
  `user_version`-runner за портом `SchemaMigrator`; датафрейм-схема — declarative-reconciler
  через интроспекцию (без `columns_hash`/registry) + per-artifact `identity_hash`/`epoch`.
  Guardrail: add-column/add-index авто; rename/drop/тип/идентичность → HALT + явная миграция.
- **Внешний доступ — контракт, не запрет:** CSV-проекция = публичный интеграционный
  контракт; БД = private detail. Внешним — API/read-model либо read-only versioned
  views; внутренние таблицы наружу не торчат.
- **SQLite runtime policy:** три тира (инварианты в коде / перф-пресеты в конфиге /
  топология), backend-scoped `ioc.storage.<role>.sqlite.*`, write-pool=1, maintenance.
  Конкретные значения пресетов и форма конфига — см. §«SQLite: тюнинг и конфигурируемость».
- **DB health (O12):** `JdbcStorageHealthProbe` (adapter) → VO: connect + `user_version` +
  `foreign_keys`/`journal_mode` + `quick_check`; bootstrap оборачивает VO в Actuator
  `HealthIndicator`. Не «директория доступна» и **не** ручной JDBC в bootstrap.
- **Storage TCK:** общий контракт-тест порта, гоняется на file + JDBC.
- **Legacy import:** маркер `legacy_imports` + upsert до `completed` (не «если пусто»).
- **Конфиг storage-neutral:** селектор `type: file | jdbc`; СУБД — в `url`/driver/profile.

## Открытые нити

**Все нити (O1–O13) ратифицированы** — см. §«Решено в диалоге». Открытых вопросов нет;
дизайн готов к реализации Шага 1. Единственный отложенный пересмотр (не блокер):
плотность `id` (O8) — вернуться только при downstream-требовании плотности.

## Шаг 1 — scope (служебное состояние: ledger → JDBC)

**Не трогаем:** бизнес-данные, CSV-артефакты, oneshot, агрегацию/партиции,
stable-id. Только перенос durable-ledger на JDBC-стор. Verify-green, shippable.

- **Модуль `adapters/adapter-store-jdbc`** в reactor; зависит внутрь на
  `core/ioc-application` (порты) + `platform-errors`. Spring JDBC (`JdbcClient`) —
  фреймворк; `sqlite-jdbc` — runtime-драйвер; миграции — versioned `user_version`-runner
  без Liquibase на SQLite-only стадии (O11). Версии — в parent POM.
- **`JdbcIngestionLedger implements IngestionLedger`** на `JdbcClient`: все
  переходы статуса + `find*`. Таблицы `ingestion_ledger` (+ дочерняя
  `ingestion_partition`). Многооператорные методы — в локальной транзакции адаптера
  (полноценный `UnitOfWork`-порт вводим в шаге 2 под многопортовую агрегацию).
- **Служебный datasource + миграции:** `ioc.storage.service.url:
  jdbc:sqlite:./var/ioc-service.db`; SQLite PRAGMA (WAL, `busy_timeout`,
  `foreign_keys`); служебная схема — versioned `user_version`-runner за портом
  `SchemaMigrator` (рукописные `vN.sql`, атомарно; Liquibase отложен — O11);
  `SchemaInitializer`-бин в bootstrap (plan→log→apply на старте).
- **Idempotent import legacy** — `LegacyLedgerImporter` (adapter/bootstrap, **за портом**
  `IngestionLedger`; application не знает о файловом формате) переносит `var/ledger/*.properties`
  → `ingestion_ledger` через маркер `legacy_imports(name, source_path, checksum, status,
  completed_at)` + upsert строк до `completed` (не «если таблица пуста» — падение посередине
  → частичный импорт).
- **Селектор** `ioc.ingestion.ledger.type: file | jdbc` (НЕ `sqlite` — СУБД в
  `ioc.storage.service.url`/driver) → условный бин в `AppConfig`. oneshot не трогаем
  (datasource поднимается только при jdbc/daemon).
- **ArchUnit:** новый модуль в boundary-правилах (JDBC/SQL/миграции заперты в
  adapter+bootstrap; domain/application их не видят).
- **SQLite runtime policy:** инварианты тира 1 (WAL, `foreign_keys=ON` пер-коннект!,
  `synchronous=NORMAL`, `busy_timeout`) из кода + пресет `low-memory` для service;
  write-pool=1 (Hikari). Полная модель — §«SQLite: тюнинг и конфигурируемость».
- **DB health (O12)** (вместо «директория доступна»): `JdbcStorageHealthProbe` в adapter
  (connect + `user_version` + `foreign_keys`/`journal_mode` + `quick_check`) → bootstrap
  оборачивает VO в Actuator `HealthIndicator` (не ручной JDBC в bootstrap) — health-контур (ING-3).
- **Storage TCK:** общий контракт-тест `IngestionLedger`, гоняется на **обоих**
  адаптерах (file + JDBC) — гарантия идентичного поведения (вместо «зеркала»).
- **Crash-window matrix** для импорта + ledger-переходов — перед кодом.
- **Доки:** `ingestion.md` (`ledger.type: jdbc` — реальный), `techdebt.md` ING-4
  (seam → частично).

## Шаг 2 — scope (бизнес-данные → JDBC truth, CSV = проекция)

**Самый рискованный шаг:** переворот системы записи; трогает ОБА пути записи
(daemon-агрегация И oneshot) — нельзя раздваивать truth. Golden e2e переезжает на
проверку проекции.

- **Второй datasource:** `ioc.storage.dataframe.url:
  jdbc:sqlite:./dataframe/ioc-dataframe.db`; второй `SchemaMigrator` (versioned-runner
  для формата + declarative-reconciler для бизнес-колонок).
- **Table-per-artifact, DDL из конфига** (declarative-reconciler через интроспекцию,
  additive-only): бизнес-колонки + `id INTEGER` identity + `row_key UNIQUE`
  + индекс на идентичность; опц. `type:`-override.
- **Per-artifact `<artifact>_sources` (1:N)** (1:1 `_meta` свёрнута — O7-ревизия):
  иммутабельная lineage — колонки бизнес-таблицы (reserved-prefix, вне экспорта);
  мутабельная observation (`last_seen`/`occurrences`) — в `_sources`; сводный
  `last_seen` — view. См. «Схема датафреймов».
- **Идентичность одна — `row_key`** = SHA-256 canonical-кортежа нормализованных
  output-значений. БД — **единственный авторитет дедупа** (`ON CONFLICT(row_key)`);
  pipeline-level `LookupRepository.contains(Indicator)` снимаем/перестраиваем на тот
  же `ArtifactIdentityResolver` (не две формулы).
- **Guard эволюции схемы (O10) — единый diff-классификатор:** бизнес-колонки —
  интроспекцией (`PRAGMA table_info` ↔ config), additive→`ALTER ADD COLUMN`; деструктив
  (drop/rename/тип) → HALT + ручная versioned-миграция. Идентичность — per-artifact
  `identity_hash`+`epoch`: дрейф→HALT, требует bump `epoch` + офлайн backfill `row_key`.
  Отказ — типизированная диагностика; reserved-prefix исключён из проверки. Детали —
  §«Управление схемой».
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
  транзакция в dataframe-БД (id+rows+sources через `DataframeUnitOfWork`; lineage —
  колонки rows) →
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
  ингест пишет прямо в canonical-таблицы; provenance из `<artifact>_sources`.
  **Жёсткое условие:** механизм партиций вычищается полностью, без legacy/мёртвого кода.

## Реализационная нарезка (verify-green slices)

Три шага выше остаются **macro-milestones**, но реализация идёт мелкими срезами:
каждый срез должен быть shippable, проходить `./mvnw verify` и не оставлять
полупереключённый truth. Step 1 намеренно «скучный»: только служебный ledger,
без dataframe/CSV/saga-рефакторинга.

1. **JDBC-модуль, build-границы и платформенный observability-фундамент.** Добавить
   `adapters/adapter-store-jdbc` в reactor, dependencyManagement для Spring JDBC/Hikari/
   sqlite-jdbc, module README, ArchUnit/Enforcer запреты на `java.sql`/`javax.sql`/
   `org.springframework.jdbc`/`org.sqlite`/`com.zaxxer.hikari`/`org.springframework.transaction`
   вне adapter+bootstrap. **+ платформа diag/obs:** `DiagnosticCategory.STORAGE`, новые
   `LogField` (`IOC_DB_ROLE`/`IOC_SCHEMA_VERSION`/`IOC_MIGRATION_VERSION`/`IOC_IDENTITY_EPOCH`/
   `IOC_AFFECTED_ROWS`), `EventAction` (`SCHEMA_MIGRATE`/`SCHEMA_VALIDATE`/`LEDGER_IMPORT`/
   `DB_HEALTH`/`MAINTENANCE`/`BACKFILL`) — см. §«Интеграция с Diagnostics & Observability».
2. **Service storage config skeleton.** Добавить `ioc.storage.service.*` в
   `IocProperties`/`application.yml` с дефолтом `jdbc:sqlite:./var/ioc-service.db`,
   backend-scoped `sqlite.tuning: low-memory`, pool limits; без подключения к daemon path.
3. **SQLite service infra.** Реализовать datasource factory + PRAGMA policy
   (`WAL`, `foreign_keys=ON`, `synchronous=NORMAL`, `busy_timeout`, low-memory preset)
   и логирование effective settings; тесты на clamp/минимальные инварианты.
4. **Service schema runner.** Реализовать lightweight `user_version` runner и v1-схему
   `ingestion_ledger`, `ingestion_partition`, `legacy_imports`; тесты fresh/current/
   partial/downgrade/rollback.
5. **IngestionLedger TCK.** Вынести общий контракт `IngestionLedger`: status transitions,
   partitions replace-семантика, `findIncomplete`, `findReadyForAggregation`, missing/failed
   behavior; сначала прогнать на `FileIngestionLedger`.
6. **JdbcIngestionLedger.** Реализовать порт на `JdbcClient` с локальными транзакциями
   для многооператорных методов; прогнать TCK на real temp SQLite + targeted SQL tests
   на FK/cascade/order.
7. **Ledger selector wiring.** Подключить `ioc.ingestion.ledger.type: file | jdbc`,
   условные бины, daemon context tests для обоих вариантов; oneshot остаётся без datasource.
8. **LegacyLedgerImporter.** Импорт `var/ledger/*.properties` в JDBC ledger через
   `legacy_imports` per source file + checksum; crash/idempotency tests для повторного запуска
   после частичного импорта.
9. **JDBC storage health.** Добавить `JdbcStorageHealthProbe` в adapter и Actuator wrapper
   в bootstrap: connect, `user_version`, PRAGMA, `quick_check`; сохранить file-health для
   `ledger.type=file`.
10. **Step 1 docs/status.** Обновить `docs/ingestion.md` (`file | jdbc`, не `sqlite`),
    `docs/techdebt.md` (ING-4 `seam` → `частично`), config examples и worknote state.

**Статус Шага 1:** срезы 1–10 реализованы и покрыты `./mvnw verify`: JDBC storage
adapter, service SQLite datasource policy, `user_version`-schema, `IngestionLedger`
TCK, `JdbcIngestionLedger`, selector wiring, legacy import, JDBC health и docs/status.
File-ledger остаётся default; JDBC включается только в `daemon+jdbc`; oneshot datasource
не поднимает.
11. **Dataframe schema foundation.** Добавить dataframe datasource + versioned format
    schema + additive declarative reconciler для business columns; guardrail tests
    add/reorder/drop/type/reserved-prefix/dry-run.
12. **Artifact identity foundation.** Реализовать canonical `row_key` (JSON-array,
    explicit null, first-non-empty), `ArtifactIdentityStore` (`identity_hash`+`epoch`) и
    identity-drift HALT/backfill tests.
13. **JDBC artifact repositories.** Реализовать `JdbcCanonicalArtifactRepository` и
    `JdbcLookupRepository` с contract/TCK, import legacy `*_generated.csv` + `.ioc-id-index.csv`
    с сохранением id и sequence за максимумом.
14. **DB truth switch + projection.** Перевести oneshot и daemon на DB truth одновременно
    для каждого артефакта, добавить CSV projection из БД, обновить golden e2e на проверку
    проекции; затем добавить run-ledger/saga crash-window tests.
15. **Payoff slices.** После Step 1+2 отдельно реализовать retention↔aggregation
    decoupling, per-group count-retention и только потом опциональный β-коллапс партиций
    с полным удалением dead partition-staging кода.

**Diagnostic-коды по срезам** (каталоги — в `platform-diagnostics/codes`, эмит — в edge/policy;
тесты ассертят код через `CollectingDiagnosticSink`): срез 4 — `STORAGE.MIGRATION_*`
(applied/rollback/downgrade); 8 — `STORAGE.IMPORT_*` (partial/idempotent); 11 —
`STORAGE.SCHEMA_DESTRUCTIVE_*`/`SCHEMA_ADDED`; 12 — `STORAGE.IDENTITY_DRIFT`/`EPOCH_BUMP`;
14 — saga/run-ledger коды + crash-window. Severity→поведение и MDC/events — §«Интеграция
с Diagnostics & Observability».

**Статус Шага 2 (срезы 11–14 реализованы, `verify` green).** Заметки реализации,
зафиксированные после код-ревью:

- **Срез 14 — truth switch + projection (выполнен).** `ioc.storage.dataframe.type=jdbc`
  стал дефолтом; oneshot пишет через `JdbcIocSink`, daemon — через агрегацию +
  `ProjectingCanonicalArtifactRepository`; CSV (`*_generated.csv`) — проекция из БД
  (`CsvArtifactProjection`, atomic temp→move). Ревью-D закрыт: `write()` пишет
  `_first_source_key` (first-insert) и upsert в `<artifact>_sources`
  (`occurrences`/`last_seen`), причём источник отброшенного keep-first-дубля всё равно
  учитывается. `_source_key` течёт из ingestion-record (daemon) или label/“oneshot”.
  Golden e2e переведён на проверку проекции (байт-точно), daemon-композиция покрыта
  `DataframeProjectionIntegrationTest`. **oneshot стал накопительным** (БД персистентна,
  `ON CONFLICT DO NOTHING`) — намеренно, задокументировано в [ingestion.md](../ingestion.md).
- **run-ledger/saga (crash-window) — сознательно отложен из среза 14 → ING-4a.** Шаг
  «commit БД → запись CSV-проекции» неатомарен, но самовосстанавливается при следующем
  прогоне (идемпотентность + полная перепроекция), поэтому это окно наблюдаемости, а не
  баг целостности под single-writer/idempotent моделью. Полноценный durable run-ledger
  (схема `run_ledger`, фазы, crash-window тесты) — отдельный срез, не хвост truth-switch.
  См. [techdebt ING-4a](../techdebt.md).

- **Единая identity-формула — намеренная смена семантики composite-ключа (ревью A).**
  Срез 12 убрал adapter-локальный `ConfigurableArtifactIdentityResolver` и перевёл
  daemon-агрегацию на `CanonicalArtifactIdentityResolver` (application) — одна формула
  `row_key` для дедупа CSV И для JDBC-storage («не раздваивать truth»). Для текущего
  конфига классы эквивалентности не изменились (нормализация идентична; в проде только
  одноколоночный composite + `first-non-empty`). НО семантика **многоколоночного**
  composite изменена осознанно: старый резолвер отбрасывал строку, если хоть одна
  ключевая колонка пуста; новый кодирует пустую как явный `null` и всё равно строит
  ключ (строка остаётся). Сегодня таких артефактов в конфиге нет → регрессии нет;
  поведение закреплено тестом `composite_identity_keeps_explicit_nulls`. Если появится
  многоколоночный composite — помнить, что строки с частичными null больше не выпадают.

- **`<artifact>_sources` / `<artifact>_last_seen` намеренно пустые до среза 14 (ревью D).**
  В срезах 11–13 они созданы как schema foundation, но писать туда пока некому: текущий
  `CanonicalArtifact` не несёт observation/provenance, а `JdbcCanonicalArtifactRepository.write()`
  сознательно остался совместим с существующим aggregation-контрактом. Плановое место
  заполнения — **срез 14** (DB truth switch + saga/run-ledger): расширить write-path так,
  чтобы вместе с upsert основной строки писать observation в `<artifact>_sources`
  (`source_key`, `first_seen_at`, `last_seen_at`, `occurrences`), а `_first_source_key`
  в основной таблице заполнять при first insert. Сейчас корректно это сделать нельзя без
  изменения модели данных (источник теряется до уровня `CanonicalArtifact`). Статус:
  инфраструктура готова заранее, пустая намеренно — не dead weight, не YAGNI-ошибка.

- **Чистка ревью (срезы 11–13):** `JdbcLegacyArtifactImporter` читает строки через
  source-порт `CanonicalArtifactRepository` (CSV-диалект остаётся в adapter-sink-csv;
  JDBC-адаптер не парсит CSV сам), сайдкар-floor `.ioc-id-index.csv` приходит параметром
  от вызывающего слоя. Sequence-floor намеренно глобальный (единый счётчик `nextId`
  в `CsvStableIdIndex` → общее id-пространство). `JdbcLookupRepository.contains` —
  регистро-независимый `lower(col)=?`; функциональный индекс на `lower(col)` — follow-up
  на момент wire-up в срезе 14.
