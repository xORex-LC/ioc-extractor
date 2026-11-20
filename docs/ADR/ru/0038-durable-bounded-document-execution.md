> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0038-durable-bounded-document-execution.md).

<a id="adr-0038-durable-bounded-document-execution-and-writer-admission"></a>
# ADR-0038: Устойчивое ограниченное исполнение документов и допуск writer

Статус: принято
Дата: 2026-10-06

<a id="context"></a>
## Контекст

Выполнение хеширования, парсинга, маршрутизации, канонических записей и
проекции в файловом poller задерживает приём за самым медленным документом.
Передача полных IOC-графов неограниченному executor усилила бы давление на
память. Параллельные канонические записи также могли бы изменить whole-row
KEEP_FIRST и приоритет зарегистрированных полей.

<a id="decision"></a>
## Решение

Каждый документ демона использует существующий durable admission protocol
независимо от политики полей его артефактов. Единственный detector резервирует
наблюдение, назначает ему dataframe order и атомарно захватывает token.
Он возвращается до копирования snapshot, хеширования или парсинга. Service
journal — восстанавливаемая очередь; очереди executor содержат ограниченные
ссылки на задания. Периодическое сканирование обнаруживает потерянные hints.

До двух workers запечатывают приватные source snapshots и готовят ограниченный
workspace из ADR-0036. Продвигаться может только самый ранний неразрешённый
document admission. Content-key guard захватывается при promotion, после
подготовки, поэтому более новый документ с тем же содержимым не может
заблокировать старый в ожидании своей очереди. Canonical writes, receipt
authority, projection recovery, archive и terminal CAS сохраняют транзакционные
границы. Read-only поиск receipt может пропустить preparation; promotion
перепроверяет receipt и возвращается к extraction, если он истёк.

Admission limits охватывают pending count и source bytes. Preparation window,
worker count и общие quotas памяти/cache/диска workspace отдельно ограничивают
исполняемую работу. При насыщении незахваченный input остаётся обнаруживаемым.
Захваченная работа сохраняет observation, order и private source при retry и
restart. Attempts и backoff устойчиво хранятся в service schema v14. Исчерпанная
ошибка с проверенным content key использует существующий rejection lifecycle.
Исчерпанная pre-hash failure остаётся устойчиво заблокированной с принадлежащим
ей token и переводит capacity health в DOWN; она никогда не выдумывает content
identity из path/mtime. Это не закрывает ING-13 и не вводит operator requeue command.

Один общий dataframe writer admission выбирает между атомарными единицами работу
CONTROL, EXPIRY, EXPORT_SLOTS, PROMOTION и MAINTENANCE. Новые запросы предпочитают
этот порядок; запросы, ожидающие не менее 100 ms, предшествуют новым и сохраняют
FIFO. Выбор не вытесняет текущую работу. Вложенная работа относится к внешней
writer unit. Резервирование ID — короткая CONTROL operation; snapshot streaming,
хеширование и preparation остаются вне writer ownership. Записи service journal
принадлежат отдельной service DB.

Health показывает ограниченные счётчики phase/count/age/source bytes,
завершённые rows/bytes, суммы и максимумы writer wait/hold по классам,
active/queued snapshot readers, WAL bytes и кешируемое давление workspace.
Существующие export/publish health views сохраняют durable publication lag.
Телеметрия не пишет в хранилище и не логирует каждый IOC.

<a id="consequences"></a>
## Последствия

Preparation может продолжаться, пока более старый документ выполняет promotion.
Упорядоченная promotion намеренно блокируется за самым ранним retry или
pre-hash blocked job; разрешение обгона молча изменило бы KEEP_FIRST.
Shutdown останавливает intake, ожидает workers в пределах ограниченного grace
period и закрывает непродвинутые handles, сохраняя durable tokens. Startup
recovery остаётся барьером до открытия любого intake.

Writer priorities не делают большую атомарную транзакцию прерываемой. Если её
максимальное время владения превышает принятые пять секунд, resource acceptance
остаётся открытой, а CAP-7C должен явно разрешить visibility/atomicity. Ни одна
существующая транзакция не разбивается молча ради успешного измерения.

Перед upgrade остановить прежний binary и сделать backup обеих DB и owned
files. Существующие document admissions мигрируют с нулевыми execution attempts
и без backoff. Старые binaries не открывают service schema v14. Rollback требует
согласованного backup; изменение `user_version` не поддерживается.

<a id="validation"></a>
## Проверка

Управляемые тесты охватывают preparation не по порядку с упорядоченной promotion,
поток/потерю hints, насыщение count и bytes, retry identity, pre-hash blocking,
shutdown/restart workers, перепроверку receipt, terminal finalization и повторное
открытие journal. JDBC tests проверяют выбор writer без голодания, reader permits
и WAL pinning при задержанном snapshot. Whole-service mixed-load и live SMB
qualification остаются CAP-6; эти mechanism tests их не заменяют.
