# Рабочие заметки (worknotes)

Живые документы проектного **диалога**: фиксируют решения, аргументы и открытые
нити по ходу обсуждения — work-in-progress, обновляются в процессе. В отличие от
[../dev/](../dev/) (settled decision records) и публикуемых `docs/*`, worknote
может содержать ещё не закрытые вопросы и менять формулировки от итерации к итерации.

Когда решения устаканились — они переезжают в `docs/dev/` и/или публикуемые
доки (`architecture.md`, `ingestion.md`, …), а соответствующий пункт техдолга
([../techdebt.md](../techdebt.md)) обновляет статус.

- [storage-layer.md](storage-layer.md) — слой хранилища (ING-4): SQLite/JDBC,
  разделение бизнес/служебных схем, CSV как проекция.
- [sync-hardening-issues.md](sync-hardening-issues.md) — временный issues-список
  по sync/event-coordination после реализации 0013 (S0–S8): лог-altitude,
  SMB-таймауты (SO_TIMEOUT-reaper), классификация транзиентов, health-`findAll`,
  мёртвый `publish.trigger`, CHANGE_NOTIFY-investigate.
  Удаляется после закрытия всех пунктов.
- [sync-change-notify.md](sync-change-notify.md) — design-note спайка H6
  (SYNC-10): SMB2 CHANGE_NOTIFY как optional push для fetch — doorbell-модель
  (сигнал → `detect`, без targeted stat), порт `RemoteChangeSignalSource`,
  lifecycle/re-arm/overflow, решение по удалениям и retention-watch-item,
  go/no-go вопросы стенда.
