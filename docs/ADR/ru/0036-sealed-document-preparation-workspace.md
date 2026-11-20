> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0036-sealed-document-preparation-workspace.md).

<a id="adr-0036-sealed-document-preparation-workspace"></a>
# ADR-0036: Запечатанный workspace подготовки документа

Дата: 2026-10-05

Статус: принято для реализации CAP-4

<a id="context"></a>
## Контекст

Подготовка документов удерживала всех artifact winners в Java collections.
Затем writer копировал этих winners в confirmation commands и неограниченные
пакеты JDBC receipt. Один лишь корректный SQL не ограничивает этот working set.

Документы коммитятся по одному artifact за раз. Managed imports, напротив,
коммитят полную delivery через несколько artifacts. Эти границы должны
оставаться разными.

<a id="decision"></a>
## Решение

Application владеет preparation invocation через JDK-only порты workspace,
row source и закрываемого cursor. JDBC adapter помещает document candidates,
original-observation dedup keys и final-key selection в одну private SQLite DB
на invocation. Запись на диск происходит всегда; нет порога размера документа,
молча выбирающего неограниченную реализацию.

Один общий для factory admission budget покрывает все live workspace caches
и bounded row codecs. Disk quotas включают source snapshots, DB и journal
files. Максимальные размеры field, encoded row и artifact catalog ограничивают
отдельные элементы. SQLite mmap отключён, временная сортировка использует диск.
До failure-policy checkpoint ID не резервируются. Cursors сохраняют порядок
первого появления key; LAST_NONEMPTY заменяет целую row без перемещения её key.

Workspace закрепляет исходные source bytes и их SHA-256, observation identity
и durable registration rank, processing policy fingerprint, format version,
ordered headers, deferred-ID metadata и ordered-field positions. Каждый routed
candidate сохраняется приватно, включая проигравшие; selection никогда не
обходит последующую validation или diagnostics. Sealing записывает extraction
counts, candidate/winner counts и streaming checksum. Promotion требует
проверки целостности всего seal до первого artifact.

Canonical writers потребляют повторно читаемые принадлежащие им row cursors,
закрывают их на каждом пути, переиспользуют transaction mutation sessions и
сбрасывают receipt batches в пределах фиксированного ограничения. Canonical
Data, provenance, artifact commit markers и typed receipt rows остаются в той
же artifact transaction. Участники import COALESCE и cross-artifact atomicity
сохраняют существующий workspace и receipt ownership; document IDs никогда
не становятся import delivery IDs.

<a id="state-and-ownership"></a>
## Состояние и владение

| Состояние | Владелец / допустимое действие | Действие при ошибке |
| --- | --- | --- |
| UNSEALED | Extraction invocation; только append и reduce | Удалить при close; после interruption перестроить из закреплённого driving source |
| SEALED | Workspace factory; immutable rows и закреплённая identity | Сохранить для retry того же наблюдения; перед использованием проверить identity, quota и checksum |
| PROMOTING | Application writer; одна artifact transaction за раз | Сохранить seal; до reservation или replay проверить durable observation/artifact commit |
| TERMINAL | Успешный invocation, dry-run или policy rejection | Закрыть cursors и удалить private state |

Неожиданные исключения до seal удаляют незавершённое private state. Отклоняющий
failure-policy checkpoint освобождает seal без canonical promotion. Успешная
promotion освобождает его после всех projections. Projection failure сохраняет
seal, хотя canonical commit отменить нельзя. Process-local leases исключают
concurrent use и cleanup активного workspace.

<a id="recovery-matrix"></a>
## Матрица восстановления

| Граница interruption | Требуемое восстановление |
| --- | --- |
| До seal | Перестроить; canonical side effects невозможны |
| Seal изменён, не совпадают source/rank/policy/header | Fail closed до любой новой mutation; не переинтерпретировать private rows |
| После seal до checkpoint | Заново оценить исходный diagnostic checkpoint; не выполнять promotion только по rows |
| Во время artifact transaction | Откатить этот artifact; зарезервированные диапазоны остаются израсходованными |
| После commit одного artifact | То же наблюдение возобновляется; результат закоммиченного artifact берётся из durable marker, включая STAGING whole-document receipt |
| После commit всех artifacts | Полный canonical receipt допускает recovery после удаления workspace |
| После commit до projection | Повторить projection; никогда не продлевать уже закоммиченные rows для того же наблюдения |
| Другая retry run identity (ING-11) | Не выводить завершение отсутствующих artifacts из неполного receipt; требовать стабильную driving observation identity, иначе fail closed при существующем promotion pin |
| Import после canonical commit | Завершить по import receipt без CSV или document workspace |

Это решение не делает document promotion глобально атомарной и не устраняет
удержание полного текста/извлечённых occurrences. Эти upstream buffers и
планирование операций остаются отдельно измеряемой работой над capacity.

<a id="consequences"></a>
## Последствия

Подготовка обменивает последовательные записи на private disk на ограниченное
Java working state. Исчерпание диска становится явной precommit failure.
Private state — восстанавливаемая работа, а не ещё один canonical authority.
Source retention и cleanup должны быть наблюдаемыми и ограниченными; tests
должны проверять настоящий spill, corruption, малые quotas, cancellation,
restart и receipt-only recovery.

Связано: [ADR-0017](../0017-diagnostics-first-class-outcome.md),
[ADR-0024](../0024-managed-dataframe-import.md),
[ADR-0034](../0034-required-router-processing-plans.md).
