---
title: "R030-RETIRE — Dead code and compatibility retirement"
version: "0.3.0"
goal_id: "R030-RETIRE"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-RETIRE — Удаление устаревших поверхностей

## Результат и состояние

Доказанно dead/unwired/obsolete surfaces удаляются целиком; supported compatibility,
accepted migrations и recovery/audit history сохраняются. Модульные cells
[матрицы](../status-matrix.md) остаются открытыми. Исходные support obligations и
завершённые dependency removals сохранены в
[release evidence](../../../../.github/release-evidence/0.3.0.md#build-and-test-adoption).

## Сохранённые dispositions

| ID | Surface, состояние и обязательство | Owner / exit condition |
|---|---|---|
| `RETIRE-INGEST-1` | `LegacyLedgerImporter`, `legacy_imports`: `keep-supported`; file-ledger replay до intake и checkpoints защищают от повторной/частичной миграции | Ingestion/storage; сначала определить supported source versions и migration exit window |
| `RETIRE-SQL-2` | Accepted dataframe/service SQL migrations: `keep-history`; append-only upgrade, recovery/audit и newer-schema rejection | Storage; старые migrations не удаляются и не переписываются, live schema меняется новой migration |
| `RETIRE-CFG-3` | `CONFIG.LEGACY_SYNC_TIMEOUT`: `keep-supported`; removed `read-timeout` отвергается с migration action | Configuration; removal-phase guidance сохраняется до отдельного support-window решения |
| `RETIRE-REGEX-4` | `JdkRegexPatternEngine`: `keep-supported`; documented `ioc.engine=jdk` выбирается composition root и имеет behavioral tests | Regex/bootstrap; низкое прежнее coverage не является доказательством dead code |
| `RETIRE-SMB-5` | `SmbjShareClient`/change-notify: `keep-supported`; transport factory и optional push имеют runtime consumers | SMB; external fixture skip не разрешает удаление, target qualification остаётся самостоятельной |
| `RETIRE-MAINT-6` | `DaemonMaintenanceScheduler`: `keep-supported`; conditional daemon retention capability | Bootstrap/maintenance; проверить config/runtime lifecycle, а не прежний coverage signal |
| `RETIRE-PUBLISH-7` | `PublishLedger` default health aggregation: `keep-supported`; durable delivery, retention guard и health используют port | Sync/storage; отсутствие local hit не отменяет supported port contract |
| `RETIRE-TCK-8` | `ioc-application-tck`: `keep-supported`; contracts исполняются adapter consumers, artifact вне production denominator | `R030-TEST`; отсутствие собственных suites не делает reusable TCK dead code |
| `RETIRE-DEP-9` | Direct `ioc-application → ioc-platform-errors`: `removed`, POM remediation завершена | `R030-BUILD`; не переоткрывать исходный declared-unused candidate |
| `RETIRE-DEP-10` | Direct `adapter-csv → commons-io`: `removed`, POM remediation завершена | `R030-BUILD`; не путать удаление direct edge с отсутствием transitive library |
| `RETIRE-APP-11` | `application.artifact.StableArtifactId`: `remove-now`, **pending**; live refs ограничены declaration и package README | Application/artifact; отдельный срез удаления record и README entry после повторной wiring/consumer проверки |

## Оставшаяся работа

1. Для каждого модуля сверить candidates, supported source versions, external
   consumers и operational/persistence/history роль. Сравнение с released
   `v0.2.0` не объявляет автоматически весь reactor API внешним контрактом.
2. Для `RETIRE-APP-11` подтвердить отсутствие compile, dynamic/resource,
   reflection/serialization и external consumers; удалить только record и
   ставшую ложной README entry. Artifact/public-ID behavior не менять.
3. Выполнить symbol/reference absence check, application tests и clean reactor
   gate; зафиксировать результат, docs/notes и module cell.
4. Новые candidates оценивать по `remove-now`, `keep-supported`, `keep-history`,
   `deprecate-first` или `defer-uncertain`. Для последних двух нужны owner,
   transition plan либо точное missing evidence и условие возврата.

## Критерии закрытия

У каждого candidate есть обоснованное disposition. Все согласованные
`remove-now` slices удалены вместе с exclusive wiring/resources/configuration,
ненужными dependencies и tests retired contract; соседнее supported behavior
остаётся защищённым. Проверены CLI/env/config, metadata/SPI/reflection, schemas,
packaging/deployment, consumer dependency closure и supported upgrade/rollback.
History сохранена, intentional retirements описаны в release notes.

## Граница и зависимости

Static search, compiler, dependency analysis или низкое coverage по отдельности
не доказывают отсутствие runtime use. При недостаточном evidence removal
запрещён; отключение флагом не считается physical removal. Accepted migrations
и необходимые durable checkpoints не удаляются вместе с временными worknotes.
Rollback qualification согласуется с [RELEASE-PROCESS](../../../RELEASE-PROCESS.md)
и [deployment](../../../guides/deployment.md), включая matching binary/config и
оба SQLite stores. Требуется `R030-BASE`; работа связана с QUAL/ARCH/TEST/DOC/REL.
