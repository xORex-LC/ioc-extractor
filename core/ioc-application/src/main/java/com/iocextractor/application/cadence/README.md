# application.cadence

## Назначение

Framework-free политики, отвечающие только на вопрос «пора ли попытаться выполнить
checkpointed workload». Они не читают хранилища, не запускают export и не знают о Spring.

**Правило слоя:** вызывающая сторона передаёт durable-факты `lastActivity` и
`lastCheckpoint`; policy может хранить только локальное состояние текущего процесса.

## Структура

| Файл | Ответственность |
|---|---|
| `CadenceSource` | Контракт проверки due-state и подтверждения успешной попытки |
| `IntervalCadenceSource` | Processing-time interval; после restart начинает новый интервал |
| `QuietPeriodCadenceSource` | Debounce по durable activity с обязательным starvation `maxCap` |
| `CadenceSources` | Реестр конфигурационных стратегий `interval`, `quiet-period` |

## Инварианты

- Повторное наблюдение одного `lastActivity` не сбрасывает quiet-period.
- Новая activity переносит quiet deadline, но не `pendingSince`/max-cap deadline.
- Failure не вызывает `completed()`, поэтому due-work повторяется на следующем poll.
- `Clock` внедряется явно; wall-clock и scheduler API внутрь package не проникают.
