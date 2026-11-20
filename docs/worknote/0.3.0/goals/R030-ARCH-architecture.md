---
title: "R030-ARCH — Architecture"
version: "0.3.0"
goal_id: "R030-ARCH"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-ARCH — Границы модулей и организация пакетов

## Результат и состояние

Каждый модуль должен иметь проверенную ответственность, карту пакетов и
обоснованные зависимости; принятые проверяемые границы защищены автоматически.
Модульные cells в [матрице](../status-matrix.md) остаются открытыми: обновление
общих документов и публикация библиотек сами по себе их не закрывают.
Шесть библиотечных cells зачтены 2026-11-17 по unchanged implementation,
предыдущему scoped review и проверке текущих boundaries/tests;
[evidence](../../../../.github/release-evidence/ru/0.3.0.md#module-hardening-reconciliation-2026-11-17).

Текущие правила принадлежат [ARCHITECTURE](../../../ARCHITECTURE.md),
[MODULARIZATION](../../../MODULARIZATION.md), [BOUNDARIES](../../../BOUNDARIES.md)
и [CONVENTIONS](../../../CONVENTIONS.md).

## Оставшаяся работа 0.3.0

1. Сверить фактическую карту пакетов, ownership, public/internal surface,
   dependency direction и cycles в оставшихся module scopes.
2. Зачесть существующие boundary checks и scoped evidence; новые отклонения
   получают owner и обоснованный disposition.
3. Подтвердить архитектурные ограничения текущего supported deployment и
   согласовать затронутую документацию/matrix с фактически проверенным scope.
4. Выполнить применимые проверки для реальных изменений кандидата; не заявлять
   выполненные package moves или исправления на основании их плана.

## Отложенная реорганизация

По явному решению пользователя 2026-11-18 весь предложенный structural/technical
refactoring перенесён за пределы 0.3.0. `QUAL-BOOT-1` изменён с `fix-now` на
`deferred`; bootstrap assembly extraction и capability package moves больше
не являются обязательными изменениями этого релиза. Это accepted scope change,
а не устранение связности либо закрытие всех module cells.

Постоянная проблема, source entry points, owners и критерии возврата сохранены
в [ARCH-6](../../../KNOWN-ISSUES.md#arch-6); связанные catalog/shutdown/logging/
CSV/constructor/orchestration slices — в [CODE-6](../../../KNOWN-ISSUES.md#code-6).
В частности, будущие moves должны учитывать direct calls из router/import
configuration к AppConfig helpers, package-private collaboration и включение
record FQCN/component identity в durable processing-policy fingerprint.

Будущая итерация выбирается отдельно, без обещания конкретной версии. Перед
relocation нужны compatibility characterization, минимальная visibility,
проверка Spring/resource/reflection FQCN и соответствующие lifecycle/recovery
и analyzer gates. Model identity и algorithms не меняются попутно с moves.

## Критерии закрытия 0.3.0 и граница

Согласованный оставшийся scope review рассмотрен; responsibility/dependencies/
visibility и отклонения имеют owner/disposition, применимые boundaries защищены
checks. Отсрочки ARCH-6/CODE-6 учтены явно. Для выполненных изменений есть
meaningful verification, affected docs и module evidence; отложенные изменения
не выдаются за выполненные. Финальная source/binary/target квалификация
остаётся обязательством R030-REL.

Исходный reactor считается подходящим; repository-wide rename, JPMS, одинаковое
дерево слоёв для всех модулей и service extraction не входят в цель. Новый модуль
требует самостоятельной ownership/dependency/publication/lifecycle границы.
Требуется `R030-BASE`; работа связана с `R030-QUAL`, `R030-RETIRE`, `R030-TEST`,
`R030-LIB` и `R030-DOC`.
