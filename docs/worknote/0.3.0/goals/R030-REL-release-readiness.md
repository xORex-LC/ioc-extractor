---
title: "R030-REL — Release readiness"
version: "0.3.0"
goal_id: "R030-REL"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-REL — Готовность релиза

## Результат и состояние

`planned`: 0.3.0 должен связать обязательные outcomes, supported upgrade/rollback,
свежие source gates и квалифицированные binary/target identities. Авторитетная
политика — [RELEASE-PROCESS](../../../RELEASE-PROCESS.md); candidate-specific
результаты принадлежат [release notes](../../../../.github/release-notes/v0.3.0.md)
и [release evidence](../../../../.github/release-evidence/0.3.0.md).

## Оставшаяся работа

1. Завершить обязательные goals и module cells [матрицы](../status-matrix.md)
   либо принять явный scope change. Закрытие глобальных BASE/BUILD/TEST/LIB
   не заменяет QUAL/RETIRE/ARCH/DOC и оставшийся security review.
2. Выбрать clean release source, version, toolchain, applicable targets и owners;
   заполнить non-regression/compatibility matrix: CLI/config, diagnostics/logging/
   health, canonical/public IDs, CSV/manifest, lifecycle/import, sync, storage и
   deployment. Intentional changes имеют reason, before/after, tests и migration.
3. Выполнить финальные свежие `make verify`, `make pmd-analysis`, `make docs`,
   `make lint-shell`, `git diff --check` и остальные применимые release checks.
   Подтвердить lossless tests, local coverage floors/ratchets, analyzer/report
   integrity и golden/E2E. Codecov остаётся advisory; unavailable external
   reporting получает disposition.
4. Сверить security/protection/dependency evidence, `SRC-3` и применимые library
   repository/consumer obligations; не выдавать старые runs за новый gate.
5. Сравнить согласованные performance/resources с baseline: build/test time,
   extraction/export, startup, heap/threads/FDs и DB/output size. Отсутствие
   согласованного measurement/disposition блокирует закрытие; regression
   оценивается по контракту, а не автоматически принимается.
6. Квалифицировать один идентифицированный application bundle: packaged
   version/full commit, digest, help/version/runtime smoke, install/deploy,
   supported migration и rollback с согласованным backup unit. Продвигать те же
   bytes; новый build требует собственной binary qualification.
7. Сверить фактические [KNOWN-ISSUES](../../../KNOWN-ISSUES.md), curated notes,
   operator actions и approved residual/deferred risks. Завершить внешние target
   проверки либо получить допустимый явный disposition с owner/reason/impact.
8. Принять final go/no-go; отдельно авторизовать publication защищённым workflow.
   Сохранить immutable tag/assets/checksums, deployment/recovery identities и
   ownership следующего development/post-release этапа.

## Критерии закрытия

Обязательные goals/retirements и scopes закрыты; non-regression matrix не имеет
необъяснённых изменений; финальные gates и применимая binary/repository/target
qualification удовлетворяют owning contracts. Notes/docs описывают действия,
ограничения и rollback. Source/tag/version, packaged identity и published bytes
согласованы; required signatures/checksums и recovery evidence сохранены;
publication явно разрешена. Не осталось незарегистрированного release blocker.

## Граница

Прошлый pass, merged PR, RC publication, local consumer или healthy startup не
заменяют полный release acceptance. Недоступная external suite не считается pass;
разрешённый disposition определяется её owning contract. Failed blocking gate
не превращается в accepted deviation без явного policy/scope решения.
PIT/stability остаются принятыми diagnostic pilots; SAST/CodeQL и расширение PIT
не становятся новыми обязательными controls этого кандидата. Goal зависит от
всех остальных и является конечным go/no-go, а не повтором общего процесса.
