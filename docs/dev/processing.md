# Обработка IOC: document → canonical artifacts

Способность читает документ, refang-ит и извлекает IOC, атрибутирует источник,
классифицирует сетевые значения и готовит строки canonical artifacts. Она не
владеет обнаружением файлов, долговременным lifecycle источника, физической
схемой SQLite или доставкой export slices.

## Runtime flow

Порядок стадий является частью application contract и собирается явно:

```text
read -> refang -> extract -> attribute -> deduplicate(batch-local)
     -> classify -> prepare rows -> failure-policy checkpoint
     -> canonical commit -> mutable CSV projection
```

`platform-etl` даёт framework-free `Envelope`, `Stage`, `Pipeline` и
`PipelineRunner`. IOC-specific payloads и порядок стадий принадлежат
`core/ioc-application`; доменные правила находятся в `core/ioc-domain`;
Tika, RE2/J, Guava PSL и Commons CSV изолированы адаптерами.

Порядок стадий не конфигурируется. Декларативны данные правил и mapping, а также
`FailurePolicy`; перестановка стадий является изменением application contract.

## Инварианты

1. **Core остаётся framework-free.** Domain/application не импортируют Spring,
   Tika, RE2/J, Guava, Commons CSV, JDBC или filesystem implementation details;
   границы защищает ArchUnit.
2. **Паттерны совместимы с RE2/J.** Поддерживаемые типы — `IPV4`, `DOMAIN`,
   `URL`, `MD5`, `SHA1`, `SHA256`; patterns используют `\b` и не используют
   look-around/back-references, чтобы работали оба `PatternEngine`.
3. **Поддерживаемый document contract проверяем.** HTML, включая explicit
   legacy charset, PDF, DOCX и XLSX имеют contract tests. Остальные parser-ы
   Tika являются best effort до появления fixture и теста.
4. **Domain возвращает решения, а не telemetry.** Refang, extraction,
   attribution и classification материализуют pure outcomes; application
   использует их для payload, diagnostics и gated TRACE без повторного вызова
   доменного правила.
5. **Dedup предшествует classification.** Дубликаты текущего batch не оплачивают
   feature extraction и rule evaluation; durable dedup отдельно выполняет
   canonical storage по `row_key`.
6. **Mapping не делает IO.** `ArtifactPreparer` применяет `accepts`, filters,
   column providers и transforms и возвращает write plan. `from: id` остаётся
   deferred slot до materialization непосредственно перед commit.
7. **Failure policy применяется до durable write.** Ожидаемый data-dependent
   отказ provider/transform становится `SINK.ROW_MAPPING_FAILED`; неожиданный
   exception остаётся run failure. Rejected run не резервирует id и не пишет
   canonical rows.
8. **Persistence выбирается явным command context.** До activation pipeline
   использует compatibility repository. В `fixed` mode driving boundary
   передаёт `LifecycleWriteContext`; `WriteArtifactsStage` вычисляет row keys из
   уже подготовленных templates и вызывает lifecycle writer с observation и
   receipt facts. Domain/stages не читают config и не знают JDBC.
9. **Post-commit projection advisory.** Успешный canonical commit необратим для
   текущего pipeline run. Lossy mutable projection может добавить
   `SINK.CHARSET_UNMAPPABLE` и повысить completion до warnings, но не запускает
   повторную failure-policy rejection.
10. **Dry-run не выполняет side effects.** Pipeline проходит read/decision/
   preparation, но пропускает canonical commit и projection.

## Декларативный artifact mapping

Колонка задаёт `name`, storage `type`, `from`/`const`, optional `when-type` и
ordered transforms. `type` описывает public storage schema и участвует в export
schema fingerprint; это не value provider.

Актуальные provider/transform/predicate keys принадлежат
`ConfigRegistryCatalog` и preflight-ятся до первой обработки. Новый артефакт,
выразимый существующими registries, добавляется конфигурацией. Новая семантика
значения требует тонкого component-а в CSV adapter и явной регистрации в
composition root. Новый формат или технология вывода требует отдельного
адаптера за application port.

`ioc.source.charset` относится только к document boundary. Для text/HTML можно
форсировать charset; container formats владеют внутренней кодировкой сами.
`ioc.sink.csv.charset` относится к mutable projection и immutable export, но у
них разные failure contracts: projection допускает replacement + advisory,
immutable slice использует strict encoding, потому что bytes входят в hashes.

## Отказы

| Граница | Поведение |
|---|---|
| Source parsing | adapter переводит parser/IO failure в typed source failure |
| Empty text / dropped item | diagnostic становится частью `Envelope` outcome |
| Expected mapping rejection | element diagnostic до commit; судьбу решает `FailurePolicy` |
| Programming/storage defect | run failure; не маскируется collect-and-continue |
| Projection после commit | hard failure завершает invocation ошибкой, canonical truth сохраняется |

## Как расширять

- Новый `IndicatorType`: обновить domain model, RE2-compatible pattern corpus,
  normalization/classification и artifact routing tests.
- Новый provider/transform/predicate: добавить adapter/domain component,
  зарегистрировать key в `ConfigRegistryCatalog`, обновить preflight и mapper
  contract tests.
- Новый artifact на существующем CSV contract: добавить sink + identity config
  и проверить schema/identity drift.
- Новый sink/format: реализовать application port новым adapter-модулем; stage
  order и domain model не должны зависеть от технологии.

## Источники истины

- Pipeline assembly: `IocExtractionService`.
- Generic execution/outcome: `platform-etl`, `platform-diagnostics` и их tests.
- Domain rules: `core/ioc-domain` + `DomainBoundaryTest`.
- Registry/config contract: `ConfigRegistryCatalog`, `ConfigRegistryPreflight`,
  `application.yml`.
- Source formats: `TikaSourceReaderFormatContractTest` и charset tests.
- Prepare/checkpoint/commit: `StageContractTest`,
  `ArtifactPolicyCheckpointTest`, `TypedMappingFailurePolicyTest`.
- Generated reference: `DIAGNOSTICS-CATALOG.md`.

## Когда обновлять документ

Обновить при изменении порядка стадий, supported IOC/document contract,
failure-policy checkpoint, mapping DSL, границы canonical commit или
post-commit projection semantics. Переименование внутреннего mapper-а само по
себе обновления не требует.

## Связанные документы

- [storage.md](storage.md) — canonical identity, transaction и projection truth.
- [ingestion.md](ingestion.md) — daemon driving flow.
- [observability.md](observability.md) — diagnostics и gated decision tracing.
- [ADR-0017](../ADR/0017-diagnostics-first-class-outcome.md) — почему write path
  разделён на prepare/checkpoint/commit.
