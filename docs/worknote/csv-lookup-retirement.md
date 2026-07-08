# Worknote: вывод из эксплуатации CSV lookup

**Статус:** рабочая issues-дока (НЕ ADR). Создана 2026-07-07 после ревью
устаревшего `adapter-lookup-csv` на фоне завершённого перехода dataframe truth
на SQLite/JDBC. **Формат:** список проблем и целевое направление cleanup; после
закрытия переносится в settled docs/KNOWN-ISSUES и/или удаляется.

## Контекст

Текущая архитектурная цель уже зафиксирована в storage-layer worknote: SQLite =
система записи, `dataframe/*_generated.csv` = проекция/экспорт из БД. После
этого runtime lookup через CSV-файлы больше не является целевым путём.

Однако в коде всё ещё жив production fallback:

- `adapters/adapter-lookup-csv` остаётся модулем reactor и зависимостью
  `bootstrap/ioc-app`;
- `AppConfig` выбирает `JdbcLookupRepository` только при
  `ioc.storage.dataframe.type=jdbc`, иначе падает в `CsvArtifactLookupRepository`
  или `CsvMaskLookupRepository`;
- `application.yml` всё ещё содержит `ioc.lookup.type: csv`, `path`, `artifacts`;
- часть тестов и docs/packaging продолжают держать старую модель lookup seed CSV.

## Сводка

| ID | Проблема | Severity | Статус |
|---|---|---|---|
| CSVLOOKUP-1 | Storage-level `lookup.contains` отбрасывает IOC до JDBC repository и может терять provenance/source | High | открыт |
| CSVLOOKUP-2 | CSV lookup fallback всё ещё включён в production wiring, хотя JDBC dataframe truth стал default | Medium | открыт |
| CSVLOOKUP-3 | `ioc.lookup.*` смешивает мёртвый CSV-контракт и живую настройку dedup | Medium | открыт |
| CSVLOOKUP-4 | Docs/packaging всё ещё описывают seed CSV lookup, который JDBC-путь не использует как truth | Medium | открыт |
| CSVLOOKUP-5 | `LookupRepository` смешивает dedup lookup и id-baseline (`maxId`) | Medium | открыт |
| CSVLOOKUP-6 | CSV lookup tests/README/KNOWN-ISSUES удерживают легаси как будто он поддерживаемый путь | Low | открыт |

## CSVLOOKUP-1 — storage lookup до JDBC write теряет provenance

**Симптом:** `DeduplicateIndicatorsStage` удаляет индикатор, если
`LookupRepository.contains(indicator)` уже видит его в storage. При JDBC-пути это
означает, что повторный IOC не доходит до `JdbcIocSink` и
`JdbcCanonicalArtifactRepository`.

**Почему это важно:** JDBC repository уже владеет keep-first семантикой через
`row_key`/`ON CONFLICT(row_key) DO NOTHING` и отдельно обновляет
`<artifact>_sources`. Если pipeline выкинул повтор до repository, новая source
или occurrence не может быть учтена. Это противоречит текущей идее "SQLite truth
+ provenance in DB".

**Целевое направление:**

1. Убрать storage-level `contains` из dedup-пути или сузить его до batch-only
   дедупликации.
2. Оставить cross-run/storage dedup canonical repository: БД принимает row,
   применяет unique key и сама обновляет provenance.
3. Добавить regression test: повторный IOC из нового источника не меняет
   keep-first public row, но добавляет запись в `<artifact>_sources`.
4. Отдельно решить, должны ли внутрипакетные дубли увеличивать occurrences; если
   да, batch dedup тоже не должен выкидывать такие строки до repository.

**Верификация по коду (2026-07-07):** подтверждено, и репозиторий уже готов к
целевому решению — `upsertSource` в `JdbcCanonicalArtifactRepository.insertRow`
выполняется **и при конфликте** `row_key` (occurrences+1, `last_seen_at`, новая
строка для нового source). Единственный блокер накопления provenance — сброс на
стадии. Симптом применим и к daemon: тот же `IocExtractionServiceFactory` +
`DeduplicateIndicatorsStage` работают в обоих режимах.

Три констрейнта для реализации:

- **Ревизия не пострадает:** `write()` бампает `artifact_revision` только при
  `inserted > 0`. Сам nudge при этом всё равно срабатывает как latency hint —
  ingest публикует `CanonicalArtifactsChanged` на каждый завершённый run
  (`IngestionService.publishArtifactsChanged`, без гейта по inserted), listener
  зовёт `trigger.nudge()` — но export/materialization отсекается дешёвым
  revision gate шедулера при актуальном progress-checkpoint. Итог тот же:
  маршрутизация повторов в repository безопасна для 0014 Р2, лишних export'ов
  не будет — только no-op чек.
- **Сжигание id:** id присваивается в mapper'е ДО conflict-детекции
  (`JdbcIocSink.row` → `ids.getAsLong()`), поэтому без pre-filter каждый повтор
  сожжёт номер последовательности → пропуски в ascending id. Либо принять гэпы
  (в БД maxId не растёт — межпрогонный baseline не портится), либо перенести
  присвоение id внутрь repository — стыкуется с CSVLOOKUP-5.
- **ING-7 не ухудшается:** `afterWrite` (CSV-проекция) уже сейчас вызывается
  безусловно, даже при `inserted == 0` — повторы в repo лишний churn не добавят.

## CSVLOOKUP-2 — production fallback на CSV lookup

**Симптом:** `adapter-lookup-csv` всё ещё участвует в сборке и wiring. В oneshot
non-JDBC режиме приложение может продолжить писать/дедуплицировать через CSV,
несмотря на то что daemon уже требует direct-to-canonical JDBC.

**Целевое направление:**

1. Удалить `adapters/adapter-lookup-csv` из reactor, dependency management и
   зависимостей `bootstrap/ioc-app`.
2. Упростить `LookupRepository` bean в `AppConfig` до JDBC-only или fail-fast при
   unsupported dataframe storage.
3. Удалить тесты, которые существуют только для поддержки non-JDBC CSV fallback;
   оставшиеся context tests перевести на явный JDBC setup.

**Дополнения (2026-07-07):**

- **Пишущая половина того же режима:** ветка `CsvIocSink` в
  `AppConfig.buildSinks` (else от `isDataframeJdbc`) мертва ровно в той же
  степени; удалить класс `CsvIocSink` + его тесты. Модуль `adapter-sink-csv`
  при этом остаётся — `CsvArtifactProjection`, slice-writers, retention store и
  completed-slice catalog живые.
- `CsvMaskLookupRepository` мёртв дважды: достижим только при не-jdbc И пустом
  `lookup.artifacts` И не-daemon, а default-конфиг всегда заполняет `artifacts`.
  Собственного теста нет, Javadoc стал ложью («the current "storage"»).
- Конкретика тестов: `ApplicationContextTest` сидит на
  `ioc.storage.dataframe.type=disabled` — пересадить на jdbc + временный SQLite
  (по образцу golden); override `ioc.lookup.path` в `application-golden.yml` и
  сам `ApplicationContextTest` (`ioc.lookup.path=…no-such…`) умирают вместе с
  ключами.

## CSVLOOKUP-3 — stale `ioc.lookup.*`

**Симптом:** `ioc.lookup.type` выглядит как активный switch, но не используется.
`ioc.lookup.path` и `ioc.lookup.artifacts` нужны только CSV fallback. При этом
`ioc.lookup.deduplicate` всё ещё влияет на pipeline и потому не является
полностью мёртвым.

**Целевое направление:**

1. Удалить `lookup.type`, `lookup.path`, `lookup.artifacts` после удаления
   CSV fallback.
2. Если batch dedup остаётся, перенести `deduplicate` в честный раздел
   pipeline/extraction, например `ioc.pipeline.deduplicate`.
3. Обновить `IocProperties`, `application.yml`, packaging template и тестовые
   overrides.

**Дополнения (2026-07-07):**

- Проверено: `packaging/templates/application.yml` ключей `ioc.lookup.*` **не
  содержит** — exposure ограничен рукописными операторскими конфигами.
- Синергия с CFG-4: по прецеденту `read-timeout` рассмотреть tombstone для
  `lookup.type`/`lookup.path` (fail-fast «ключ удалён, dedup настраивается в
  …»), чтобы внешний YAML со старой секцией не деградировал молча; убрать
  tombstone вместе с CFG-4 strict binding.

## CSVLOOKUP-4 — seed CSV в docs/packaging

**Симптом:** installer/README всё ещё описывают seed CSV для lookup. При JDBC
truth эти CSV не становятся источником дедупликации, если их явно не импортировать
в canonical DB.

**Целевое направление:**

1. Разделить понятия "операторские lookup seed CSV" и "generated projection".
2. Если seed нужен как migration/import path — оформить отдельную явную команду
   или startup-задачу импорта в DB.
3. Если seed больше не нужен — удалить packaging hooks и устаревшие docs.

**Дополнение (2026-07-07) — усиливает severity:** `JdbcLegacyArtifactImporter`
**не заведён нигде в production** — единственный вызывающий его код это его же
юнит-тест, и по git-истории (`git log -S` по bootstrap) он не был заведён
никогда. То есть runtime import path сегодня **отсутствует вовсе**: seed CSV в
default-режиме молча нефункциональны, requirement «дедуп против ручных
справочников» в JDBC-режиме не выполняется. Это блокер-решение всего
retirement: (а) requirement умер → чинить только docs (README, где
ручные списки всё ещё «the lookup reference») и удалить importer как мёртвый
класс; (б) requirement жив → wire importer (или команда `ioc import`) как seed
в canonical DB, и только потом снимать CSV lookup.

## CSVLOOKUP-5 — `LookupRepository` перегружен

**Симптом:** один порт отвечает и за `contains`, и за `maxId`. После удаления CSV
lookup эти причины существования расходятся:

- `contains` как storage dedup должен уйти в canonical repository/unique key;
- `maxId` нужен только для id-baseline при генерации артефактных rows.

**Целевое направление:**

1. Выделить id baseline в отдельный небольшой порт/сервис или в ответственность
   canonical dataframe repository.
2. Не вычислять `maxId` для artifacts без id-колонки.
3. Перепроверить `IdGenerator(start=maxId + 1)` после удаления CSV fallback.

**Дополнения (2026-07-07) — тот же смелл живёт и в JDBC-реализации:**

- `JdbcLookupRepository` хардкодит имена артефактов и колонок (`masks/mask`,
  `ip_list/ip`, `hashes/hash_md5|sha1|sha256`), а безаргументный `maxId()`
  агрегирует захардкоженную тройку — противоречие принципу «config-driven, no
  hard-coded triggers». Retirement CSV это не лечит; при редизайне порта вести
  routing/baseline от `DataframeArtifactSchema` / `artifact-identity`-конфига.
- `isBareIp` существует в трёх расходящихся копиях: domain-предикат
  `is-bare-ip`, `CsvArtifactLookupRepository` (посимвольная проверка `:/?`),
  `JdbcLookupRepository` (regex full-match). Одна умирает с retirement;
  JDBC-копию свести к domain-предикату при сплите порта.
- Сюда же стыкуется вопрос сжигания id из CSVLOOKUP-1: перенос присвоения id в
  repository закрывает обе проблемы одним движением.

## CSVLOOKUP-6 — тесты и docs, удерживающие легаси

**Симптом:** `CsvArtifactLookupRepositoryTest`, README модуля, `KNOWN-ISSUES`
и capability docs продолжают описывать CSV lookup как поддерживаемый механизм.

**Целевое направление:**

1. Удалить тесты `adapter-lookup-csv` вместе с модулем.
2. Обновить `docs/ARCHITECTURE.md`, `docs/MODULARIZATION.md`,
   `docs/SERVICES-CATALOG.md`, `docs/dev/output-mapping.md`,
   `docs/dev/ingestion.md`, `docs/KNOWN-ISSUES.md`, `adapters/README.md`.
3. Не удалять полезный migration-history контекст без отдельного решения:
   если `JdbcLegacyArtifactImporter` остаётся нужен, оформить его как явный
   migration/import seam, а не как runtime CSV lookup.

**Дополнения (2026-07-07):**

- `KNOWN-ISSUES` CFG-2 схлопывается наполовину: кросс-чек имён
  `lookup.artifacts ↔ sink.artifacts` уходит вместе с ключами; половина про
  `artifact-identity.artifacts` остаётся.
- Стэйл-комментарий в `application.yml` у `sink.csv.charset` («…and for reading
  existing artifacts in lookup/storage») — вычистить вместе с секцией.
- **Процесс:** удаляется архитектурная опция, зафиксированная в ранних
  ADR/модульной карте — финальное удаление оформить коротким **ADR 0015**
  («вывод legacy CSV lookup/storage-режима», ссылки на 0009/storage-worknote);
  этот worknote ADR не заменяет (append-only дисциплина).

## План рефактора

**Цель:** снять runtime CSV lookup/storage fallback и оставить один целевой путь:
canonical SQLite/JDBC как truth, CSV-файлы только как projection/export. При этом
нельзя потерять provenance: повторный IOC должен доходить до canonical repository,
чтобы `<artifact>_sources` обновлялся даже при `ON CONFLICT(row_key) DO NOTHING`.

### Инварианты

1. `core/ioc-domain` и `core/ioc-application` остаются framework-free: никаких
   Spring/JDBC/CSV implementation imports внутрь.
2. Storage-level dedup не возвращается в pipeline под новым именем. Cross-run
   dedup принадлежит `JdbcCanonicalArtifactRepository` и уникальному `row_key`.
3. Pipeline-дедуп, если остаётся, означает только within-batch dedup по
   `Indicator.dedupKey()`.
4. `LookupRepository` не переименовывается механически. Его ответственности
   разделяются: `contains` удаляется, `maxId` уходит в узкий id-baseline seam.
5. Перенос id allocation внутрь repository НЕ входит в первый cleanup. Текущий
   `IdGenerator` пока остаётся, а возможные gaps от конфликтов считаются
   допустимыми для unique ascending id. Gapless/id-in-repository — отдельное
   решение, потому что задевает старый контракт `ascending|descending`.
6. `adapter-sink-csv` как модуль остаётся: в нём живут CSV projection, slice
   writers, retention store и completed-slice catalog. Удаляется только старый
   прямой writer `CsvIocSink` — его единственный production-потребитель это
   non-JDBC ветка `buildSinks` (проверено 2026-07-07; slice writers — отдельные
   классы), после снятия режима он мёртв гарантированно.

### Фаза 0 — ADR и decision gate

1. Добавить короткий ADR 0015: "retire legacy CSV lookup/storage mode".
2. Зафиксировать в ADR:
   - SQLite/JDBC dataframe storage — единственный production truth;
   - `dataframe/*_generated.csv` — generated projection/export, не source of truth;
   - `lookup.contains` больше не является application policy;
   - ручные seed CSV не работают как runtime lookup в JDBC-режиме;
   - **явно:** batch-dedup остаётся (инвариант 3), поэтому внутрипакетные дубли
     НЕ инкрементируют occurrences — это осознанное решение вопроса из
     CSVLOOKUP-1 п.4, а не молчаливое следствие инварианта.
3. Принять отдельное решение по seed CSV:
   - requirement умер → чистим docs/packaging и удаляем `JdbcLegacyArtifactImporter`;
   - requirement жив → делаем явный import path (`ioc import` или startup/migration
     task), но не возвращаем runtime lookup через CSV.

### Фаза 1 — correctness: provenance before deletion

1. Переделать `DeduplicateIndicatorsStage`:
   - убрать зависимость от `LookupRepository`;
   - оставить only batch-local `HashSet<dedupKey>`, если `deduplicate=true`;
   - обновить Javadoc: стадия больше не смотрит в storage.
2. Обновить `IocExtractionService` / `IocExtractionServiceFactory` constructors:
   - убрать `LookupRepository lookup`;
   - оставить boolean dedup setting, но переименовать его смысл в batch dedup.
3. **Закрыть окно legacy-режима сразу в этой фазе:** fail-fast для
   `ioc.storage.dataframe.type != jdbc` в `AppConfig` (по образцу daemon
   `sourceSinkFactory`). Иначе между Фазами 1 и 3 ещё достижимый CSV
   oneshot-fallback теряет свой единственный межпрогонный дедуп
   (`lookup.contains`; у CSV-файлов нет аналога `ON CONFLICT`) и начинает
   молча дублировать строки при повторных прогонах. После fail-fast Фаза 3
   становится чистым вычитанием.
4. Пересадить `ApplicationContextTest` с `ioc.storage.dataframe.type=disabled`
   на временный JDBC dataframe DB (по образцу golden) — переезжает сюда из
   Фазы 3, потому что fail-fast из п.3 ломает его уже в этой фазе.
5. Обновить тесты:
   - `DeduplicateIndicatorsStageTest`: "lookup hits" больше не удаляются;
   - `StageContractTest`, `IngestionServiceTest` и другие test doubles больше не
     реализуют `LookupRepository`;
   - новый regression в JDBC adapter: повторный IOC с новой source сохраняет
     keep-first public row и добавляет/обновляет `<artifact>_sources`.
6. Проверка фазы:
   - `./mvnw -pl core/ioc-application,adapters/adapter-store-jdbc,bootstrap/ioc-app -am test`.

### Фаза 2 — split id-baseline seam

1. Ввести application port с узкой ролью, например:
   `ArtifactIdBaseline.maxId(String artifactName)`.
2. Реализовать JDBC adapter без hard-coded artifact names:
   - базироваться на configured dataframe schemas / artifact definitions;
   - для artifact без `id`-колонки возвращать `0` и не ходить в SQL;
   - не иметь безаргументного `maxId()` с зашитой тройкой
     `masks/ip_list/hashes`.
3. Переподключить `AppConfig.startOf(...)`:
   - принимать `ArtifactIdBaseline`, не `LookupRepository`;
   - перепроводка идёт через **оба** bean-пути, тянущих параметр
     `LookupRepository`: `extractIocsUseCase` (oneshot) И `sourceSinkFactory`
     (daemon), плюс сигнатуры `buildSinks` / `artifactDefinitions`;
   - вызывать baseline только если artifact реально имеет id config/column;
   - сохранить текущий explicit `start` и `auto = maxId + 1`.
4. Обновить `JdbcLegacyArtifactImporter`, если он остаётся:
   - заменить внутренний `new JdbcLookupRepository(dataSource)` на новый
     id-baseline adapter или локальный schema-aware query.
5. `LookupRepository` interface в этой фазе **НЕ удалять**: его ещё реализуют
   `CsvArtifactLookupRepository`/`CsvMaskLookupRepository`, `JdbcLookupRepository`
   и держит bean `lookupRepository` — реактор не соберётся. Удаление — Фаза 3,
   после сноса адаптера и bean'а.
6. Проверка фазы:
   - `./mvnw -pl core/ioc-application,adapters/adapter-store-jdbc,bootstrap/ioc-app -am test`.

### Фаза 3 — remove CSV lookup/storage runtime fallback

1. Удалить `adapters/adapter-lookup-csv`:
   - root `pom.xml` modules;
   - parent `dependencyManagement`;
   - `bootstrap/ioc-app/pom.xml`;
   - `CsvArtifactLookupRepository`, `CsvMaskLookupRepository`, tests, README.
2. Упростить `AppConfig`:
   - удалить imports `CsvArtifactLookupRepository` / `CsvMaskLookupRepository`;
   - удалить bean `lookupRepository(...)`;
   - удалить helper `lookupArtifactPaths(...)`;
   - `JdbcLookupRepository` удалить или заменить новым `JdbcArtifactIdBaseline`.
3. Удалить non-JDBC write branch:
   - убрать `CsvIocSink` branch из `buildSinks`;
   - удалить `CsvIocSink` и его tests (единственный production-потребитель —
     эта ветка, см. инвариант 6);
   - оставить `CsvArtifactProjection` и export/sync filesystem classes.
4. Удалить `LookupRepository` interface из `core/ioc-application` — только
   теперь не осталось ни реализаций, ни потребителей (перенесено из Фазы 2).
5. Вычистить осиротевшую observability-константу: `EventAction.LOOKUP_LOAD`
   используют только удаляемые CSV-классы (проверено 2026-07-07) — удалить из
   `platform-observability` и убрать упоминание из
   `docs/dev/LOGGING-TAXONOMY.md`.
6. Удалить tests, которые держали legacy режим:
   - (`ApplicationContextTest` уже пересажен в Фазе 1);
   - убрать `ioc.lookup.path=...` overrides из golden/daemon/export tests;
   - убедиться, что lightweight help/lazy init tests всё ещё не поднимают
     лишний storage runtime без необходимости.
7. Maven/dependency audit:
   - `mvn dependency:tree -Dincludes=com.iocextractor:ioc-adapter-lookup-csv`;
   - проверить, что `commons-csv` / `commons-io` остаются только там, где нужны
     `adapter-sink-csv` и CLI/export code.
8. Проверка фазы: полный `./mvnw verify`, не только `-pl … test` — удаление
   модуля из реактора ловят именно ArchUnit / enforcer / link-check /
   `DocumentationConventionTest`.

### Фаза 4 — config cleanup and tombstones

1. Удалить из `application.yml`:
   - `ioc.lookup.type`;
   - `ioc.lookup.path`;
   - `ioc.lookup.artifacts`.
2. Перенести `ioc.lookup.deduplicate` в честный раздел, например:
   `ioc.pipeline.deduplicate` или `ioc.extraction.deduplicate`.
3. Обновить `IocProperties`:
   - удалить живой `Lookup` record после migration;
   - если нужен fail-fast для старых внешних YAML, добавить временный tombstone
     под `ioc.lookup.*` с понятным сообщением: "legacy lookup removed; configure
     batch dedup via ...";
   - tombstone **обязан покрывать `lookup.deduplicate`** — единственный ключ,
     который переезжает, а не умирает: операторский
     `ioc.lookup.deduplicate: false` иначе молча перестанет применяться (ровно
     тот failure mode, ради которого существует CFG-4-прецедент); сообщение —
     "moved to ioc.pipeline.deduplicate".
4. Синхронизировать с CFG-4:
   - tombstone не должен становиться новой долгоживущей фичей;
   - полное удаление tombstone — вместе со strict binding pass.
5. Обновить stale comment у `sink.csv.charset`: charset больше не описывает
   reading existing artifacts in lookup/storage.

### Фаза 5 — docs/packaging cleanup

1. Обновить settled docs:
   - `docs/ARCHITECTURE.md`;
   - `docs/MODULARIZATION.md`;
   - `docs/SERVICES-CATALOG.md`;
   - `docs/dev/pipeline.md` — диаграмма стадий несёт
     `DeduplicateIndicatorsStage (LookupRepository)`, семантика меняется уже в
     Фазе 1; заодно проверить `docs/dev/extraction.md`;
   - `docs/dev/output-mapping.md`;
   - `docs/dev/ingestion.md`;
   - `docs/KNOWN-ISSUES.md`;
   - `adapters/README.md` и module READMEs;
2. Убрать формулировки, что hand-filled
   `dataframe/masks_list.csv` / `hashes_list.csv` — runtime lookup reference.
3. Packaging:
   - если seed requirement умер — удалить `--lookup-seed`/seed-copy hooks и docs;
   - если requirement жив — описать новый explicit import path вместо lookup seed.
4. `KNOWN-ISSUES`:
   - закрыть/переформулировать `CFG-2`: часть про
     `lookup.artifacts ↔ sink.artifacts` уходит, часть про
     `artifact-identity.artifacts` остаётся;
   - удалить `CODE-2` про duplicate bare-IP logic в CSV lookup после удаления
     adapter;
   - если `JdbcLookupRepository` удалён, убрать связанные hardcode notes.

### Фаза 6 — final verification

1. Targeted tests:
   - `./mvnw -pl core/ioc-application -am test`;
   - `./mvnw -pl adapters/adapter-store-jdbc -am test`;
   - `./mvnw -pl bootstrap/ioc-app -am test`.
2. Reactor gate:
   - `./mvnw verify`.
3. Boundary check expectations:
   - no Spring/JDBC/CSV leakage into `core/ioc-domain` or `core/ioc-application`;
   - no `adapter-lookup-csv` in reactor/dependency tree;
   - no production references to `CsvIocSink`, `CsvArtifactLookupRepository`,
     `CsvMaskLookupRepository`, `LookupRepository`;
   - generated CSV projection still works from canonical DB;
   - daemon ingest still emits `CanonicalArtifactsChanged`; no-op duplicate-only
     writes do not advance artifact revision, so export materialization remains
     revision-gated.
4. Nice-to-have — сквозной bootstrap-регресс «двойной extract одного
   источника»: CSV-проекция побайтово идентична, `artifact_revision` не
   сдвинулась, occurrences в `<artifact>_sources` выросли. Адаптерный тест из
   Фазы 1 пинит это на уровне repository; сквозной прогон закрепляет весь новый
   контракт CSVLOOKUP-1 целиком.

### Отложенные решения

1. **Gapless id / id-in-repository.** Не смешивать с retirement. Если понадобится
   устранить gaps от duplicate conflicts, отдельным дизайном решить, сохраняем ли
   legacy `ascending|descending` и explicit id, или переходим к DB-owned id.
2. **Seed import.** Если ручные списки всё ещё нужны операционно, сделать явный
   import use case/command. Не возвращать скрытый CSV lookup.
3. **Projection churn / ING-7.** Текущий `afterWrite` уже вызывает projection даже
   при `inserted == 0`; retirement это не ухудшает. Оптимизацию projection делать
   отдельно как delta/cached projection work.
