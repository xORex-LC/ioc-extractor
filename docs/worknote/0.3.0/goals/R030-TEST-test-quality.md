---
title: "R030-TEST — Test quality"
version: "0.3.0"
goal_id: "R030-TEST"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-TEST — Эффективность тестов и покрытие

## Результат и состояние

Общие controls — `verified`: lifecycle, coverage, regex, waits, external-suite
classification, pilots, Codecov и repository consumers квалифицированы в
[release evidence](../../../../.github/release-evidence/0.3.0.md#build-and-test-adoption).
Модульные cells [матрицы](../status-matrix.md) остаются самостоятельной работой.
Наличие coverage/gates не доказывает полноту review каждого модуля.

Обязательные правила и команды принадлежат [TESTING](../../../TESTING.md) и
[build quality](../../../dev/build-quality.md); goal сохраняет объём 0.3.0.

## Сохранённые release deliverables

- Lossless Surefire/Failsafe lifecycle, контролируемые tags, targeted/fast/
  integration/full команды и integrity ожидаемых test/coverage reports.
- JaCoCo для unit/integration, локальные и aggregate reports, per-scope ratchets:
  aggregate `75/80` line/branch, domain/application `85/90`; исходный более высокий
  baseline не понижается до общего floor. TCK проверяется consumers и остаётся
  вне production denominator.
- Best-effort Codecov с project/base-relative/patch signals; отсутствие внешнего
  status имеет non-blocking disposition. Meaningful changed behavior проверяется
  независимо от advisory changed-lines target `90%`.
- Risk-based negative/boundary/error/recovery/concurrency/compatibility coverage,
  bounded waits и явные dispositions flaky/sleep findings; regression test для
  каждого исправленного дефекта.
- Domain-only PIT и seeded stability pilots с reports, triage, runtime/cost и
  adoption disposition; оба report-only, без числового release score.
- Изолированные consumers опубликованных coordinates из каждого заявленного
  repository; local install или consumer внутри reactor их не заменяет.

## Оставшаяся работа по модулю

1. Установить критичное observable behavior и существующие tests; analyzer и
   missed-branch metrics использовать для навигации, а не как готовый finding.
2. Проверить invariant-oriented cases для refang, normalization, classification,
   deduplication и identity на существующем stack; закрыть подтверждённые gaps.
3. Для structural/retirement slices сохранить characterization, absence и
   supported-neighbor evidence; для SQLite migrations, partial commits и
   run-ledger recovery иметь подходящие upgrade/fault-path проверки.
4. Проверить resource/time bounds, остановку owned workers, flake disposition,
   длительность дорогих suites и обоснованность fixtures/builders.
5. Выполнить применимые focused и полные gates; поднять ratchet после evidence,
   обновить документацию и соответствующую cell.

## Критерии закрытия

Общие controls сохраняются; все согласованные module scopes проверены по риску,
изменённые критичные ветви защищены meaningful tests, exclusions узкие и
обоснованные, flakes/performance regressions имеют владельца и disposition.
Пилоты сохраняют воспроизводимые команды, reports, seed/triage и принятое
решение; external skip не выдаётся за live pass. Финальные свежие gates и
packaged/repository/target evidence проверяются в `R030-REL` отдельно от истории.

## Принятые отсрочки и зависимости

Расширение PIT остаётся `TOOL-5`, centralized external harness — `TOOL-4` в
[KNOWN-ISSUES](../../../KNOWN-ISSUES.md). Вне scope: repository-wide mutation
threshold/PIT на каждом PR, новый property-based framework, fuzz/chaos/soak/load
platforms, обязательная SMB-лаборатория, необоснованные Testcontainers или
cross-platform matrix, RabbitMQ tests до адаптера и массовая стилевая перепись.
Автоматические retries не скрывают flakes; 100% coverage не является целью.
Требуются baseline и instrumentation `R030-BUILD`; goal поддерживает module
hardening и `R030-REL`.
