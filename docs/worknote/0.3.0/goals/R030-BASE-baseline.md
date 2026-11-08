---
title: "R030-BASE — Baseline"
version: "0.3.0"
goal_id: "R030-BASE"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-BASE — Воспроизводимый baseline

## Результат и состояние

`verified`: исходное состояние перед hardening измерено; сравнение с released
`v0.2.0`, source/toolchain, workloads и ограничения сохранены в
[release evidence](../../../../.github/release-evidence/0.3.0.md#original-comparison-baseline).
Это историческая точка сравнения, а не подтверждение более позднего кандидата.

## Сохранённый объём

Baseline охватывает reactor/dependency graph, clean build, test inventory,
coverage, compiler/static/dependency warnings, durations/flakes, runtime/resource
measurements, hotspots, retirement/shared-code intake, supported compatibility,
известных consumers, документацию и существующие controls. Каждое измерение
имеет revision, среду, команду, результат и время либо явный disposition.
Исходные reports доступны через immutable source snapshot из release evidence.

## Критерии закрытия и дальнейшая работа

- Исходные измерения и unavailable-dispositions остаются воспроизводимыми.
- Последующие goals сравнивают согласованные workload/measurement boundaries,
  объясняя различия среды, scope и поведения.
- [Матрица](../status-matrix.md) сохраняет связь baseline с остальными goals;
  финальные свежие gates принадлежат `R030-REL`.
- Повторный baseline допустим только по явному решению об изменении scope или
  контракта; он не заменяет историю исходного сравнения.

## Граница

Baseline capture не включает исправление findings, structural changes, removal,
publication admission или снижение thresholds. Measurement-only bootstrap уже
завершён; повторять его как незакрытую задачу не требуется.
