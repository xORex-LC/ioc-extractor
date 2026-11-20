> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0037-profile-scoped-export-execution.md).

<a id="adr-0037-profile-scoped-export-execution"></a>
# ADR-0037: Исполнение экспорта в области профиля

Статус: принято
Дата: 2026-10-05

<a id="context"></a>
## Контекст

Complete slices остаются атомарными внутри каждого export profile. Общая
formation lock, singleton активного run и монитор CSV writer раньше
сериализовали не связанные profiles. Поэтому медленный GDI profile мог
задерживать готовые reputation outputs.

<a id="decision"></a>
## Решение

Сериализовать formation и recovery по profile через cross-process NIO leases
и partial unique active-profile index в service schema v13. Formation
восстанавливает только свой leased profile. Startup recovery посещает каждый
incomplete profile, включая удалённые из конфигурации, до допуска scheduling.

Демон использует два bounded workers и объединяет hints максимум в одну
незавершённую attempt на каждый настроенный profile. Snapshot transactions
разделяют настроенный reader budget. Clock и slot write transactions остаются
вне CSV streaming. Mutable canonical transactions, slot namespaces,
per-profile plans, quiet/max-cap cadence, retention pins и endpoint-keyed
SMB publication сохраняют свои контракты. CSV writer держит invocation-local
materialization state и опирается на profile operation lease вместо
монитора на весь экземпляр.

<a id="consequences"></a>
## Последствия

Доступный второй worker может завершить готовый profile, пока другой заблокирован
в streaming. Никакой scheduler не может обойти удерживаемое SQLite writer
ownership. Долгие WAL snapshots по-прежнему удерживают продвижение checkpoint
и требуют измерения.

Перед upgrade остановить старый процесс и сделать согласованные storage
backups. Смешанные старые/новые formation binaries не поддерживаются:
старый binary использует global file lock и не может открыть service schema v13.
Rollback требует восстановления backup, а не переписывания `user_version`.
Migrator продолжает отклонять downgrade.

<a id="validation"></a>
## Проверка

Тесты с управляемой блокировкой должны доказать завершение независимого profile
до освобождения заблокированного, ограниченность outstanding work, исключение
по одному profile, profile-scoped recovery, сохранность при migration и
ограниченное завершение workers.
