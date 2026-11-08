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

Текущие правила принадлежат [ARCHITECTURE](../../../ARCHITECTURE.md),
[MODULARIZATION](../../../MODULARIZATION.md), [BOUNDARIES](../../../BOUNDARIES.md)
и [CONVENTIONS](../../../CONVENTIONS.md).

## Оставшаяся работа

1. Для каждого согласованного модуля сверить текущую и целевую карту пакетов,
   ownership, public/internal surface, cycles и dependency direction.
2. Разобрать `QUAL-BOOT-1`: корневой `com.iocextractor.bootstrap` объединяет
   configuration, storage, export, sync, health и lifecycle. Выбрать capability
   grouping после анализа совместной package-private работы и композиции.
3. Согласовать необходимые изменения с `R030-QUAL` и удалить доказанно мёртвые
   поверхности через `R030-RETIRE` до лишнего relocation.
4. Выполнять moves проверяемыми срезами; отдельно рассматривать API rename,
   algorithm change и изменение Maven boundary.

## Критерии закрытия среза

- Назначение и reading map модуля понятны; нет необоснованных flat/god packages.
- Dependency direction, cycles и visibility рассмотрены; отклонения имеют
  владельца и принятое решение, enforceable boundaries защищены checks.
- Production/test packages согласованы; Spring metadata, reflection, Logback,
  serialization и resource FQCN проверены после moves.
- Package-private collaboration сохранена либо изменение visibility обосновано;
  крупные composition classes проверены на cohesion.
- Characterization/architecture tests и применимые gates подтверждают сохранение
  supported behavior; README/capability/root maps обновлены в том же срезе.
- Целевая карта и verification evidence отражены в модульной cell.

## Граница и зависимости

Исходный reactor считается подходящим; repository-wide rename, JPMS, одинаковое
дерево слоёв для всех модулей и service extraction не входят в цель. Новый модуль
требует самостоятельной ownership/dependency/publication/lifecycle границы.
Требуется `R030-BASE`; работа связана с `R030-QUAL`, `R030-RETIRE`, `R030-TEST`,
`R030-LIB` и `R030-DOC`.
