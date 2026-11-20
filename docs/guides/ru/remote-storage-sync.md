# Синхронизация с удалённым хранилищем (SMB)

[English version](../remote-storage-sync.md)

Руководство описывает настройку и эксплуатацию обычного SMB fetch/publish:
передачу файлов производителем, восстановление и доступ на сервере. Внутренние
механизмы принадлежат [sync](../../dev/ru/sync.md) и
[событийной координации](../../dev/ru/event-coordination.md). У managed CSV import
своё [руководство](dataframe-import.md) и приватное пространство владения;
общий endpoint не даёт обычному fetch права изменять файлы как import.

## Как устроена синхронизация

```text
remote source -> fetch -> local inbox -> ingestion -> canonical SQLite
canonical SQLite -> immutable export slice -> publish -> remote target
```

Fetch скачивает целые исходные файлы и не меняет удалённые оригиналы. Ingest
отдельно их разбирает. Publish передаёт уже завершённые локальные срезы, не
извлекает IOC и не формирует export. Daemon планирует оба потока. Oneshot CLI
выполняет выбранный fetch/publish cycle с включённой JDBC-backed sync configuration.

### Идентичность, повторы и текущие ограничения

Fetch распознаёт `(path, size, modifiedAt)`, не содержимое. Повторная загрузка
тех же или других байтов считается новой только при смене tuple. Polling не
восстановит файл, удалённый до listing: согласуйте срок хранения завершённых
файлов производителем и допустимую длительность outages.

Для нескольких sources/writers важны два текущих дефекта:

- `OPS-11`: у fetch key нет endpoint/source namespace. Одинаковые tuples на двух
  шарах могут полностью подавить второе скачивание.
- `OPS-12`: выбор имени и atomic landing разделены. Одновременные writers одного
  inbox filename могут заменить уже полученный источник, хотя оба fetch на Linux
  будут записаны как успешные.

См. [известные проблемы](../../KNOWN-ISSUES.md). Используйте непересекающиеся remote
paths и уникальные final filenames производителей: это снижает конкретные коллизии,
но не заменяет исправление и не даёт общего cross-process fencing. Согласуйте
ручной fetch с daemon и другими writers. Очистка ledger rows не является обычной
процедурой повторной подачи.

Даже без этих дефектов fetch — at-least-once: crash после landing до ledger update
может скачать файл ещё раз. Canonical deduplication не делает повторное наблюдение
операцией без последствий при любой lifecycle policy.

### Передача файла производителем и push

Удалённый производитель обязан записать файл под исключённым временным именем,
закрыть его и переименовать в свободное final name в той же filesystem/share.
У fetch нет remote quiet-period и проверки метаданных до/после скачивания. Запись
сразу под конечным именем может передать частичный контент; увеличение debounce
этого не предотвращает. Размер/mtime позже могут измениться, но полное повторное
скачивание не отменит частичные данные, уже прошедшие ingest.

`CHANGE_NOTIFY` опционален. Сигнал означает «получить список этого источника»,
не содержит authoritative content или delivery identity. Debounce объединяет
push triggers; scheduled/startup checks не ждут завершения записи производителем.
Polling остаётся включённым. Выбирайте `fetch.interval` по допустимой задержке
после потерянного сигнала, recovery и стоимости listing; универсального безопасного
интервала нет.

Watch владеет отдельной долгоживущей SMB session/directory handle. Обычный transport
использует cached endpoint session, при соответствующей конфигурации общую с
другими операциями. Каждый watch source добавляет спрос на ресурсы даже при одном
endpoint. Watch обновляет lease примерно каждые 30 минут независимо от обычных
request/idle timeouts и ускоряет detection после восстановления. Reconnect идёт,
пока жив daemon. Запрошенная, но неподдерживаемая push capability проваливает
startup; последующий сетевой сбой обрабатывают reconnect и polling, а не считают
доказательством квалификации сервера.

### Публикация и получатели

Publish обнаруживает completed slices и ведёт пару на slice/target. Локальный
catalog проверяет marker, manifest, membership, file hashes/sizes. SMB adapter
загружает non-marker files в соседний приватный `.tmp-…`, последним там пишет
`_SUCCESS`, проверяет размеры и переименовывает в final slice. Получатель должен
игнорировать временные каталоги.

Получатель принимает только final directory с `_SUCCESS`, соответствующим хешу
manifest, затем проверяет ожидаемые файлы, размеры и хеши. Одного marker недостаточно
для сквозной проверки байтов. Marker recovery может завершить прежнюю доставку без
скачивания каждого remote CSV. Несовпавший существующий marker проваливает проверку;
незавершённый final directory без marker может быть заменён. Изолируйте publish
output от посторонних данных и внешних writers.

Delivery ledger содержит `PENDING`, `IN_PROGRESS`, `FAILED`, `SUCCEEDED`,
`ABANDONED`; последние два терминальны. Прерванная in-progress попытка становится
retryable после recovery threshold, а не обязательно сразу при restart. Публичного
abandon/requeue CLI нет. Потерянный ответ может означать, что срез уже удалённо
доставлен, хотя локальный ledger ещё не завершён.

Local retention ждёт terminal state для всех текущих targets профиля. Отсутствующая
или failed pair может закреплять slice сверх age/count limits; `ABANDONED` снимает
pin без доказательства доставки. Удаление target меняет обязательство. Delivery-aware
retention veto действует только при включённом daemon sync/publish и JDBC service
storage. Выключение `ioc.sync.enabled` или `ioc.sync.publish.enabled` убирает veto;
сохраните unresolved slices до изменения. Отключение publish не является паузой
с сохранением этих pins. Обычный publish не удаляет старые committed remote slices;
согласуйте remote retention с получателями отдельно.

## Конфигурация

Sync, fetch и publish по умолчанию выключены, списки endpoint/source/target пусты.
Включайте только настроенные потоки.

Полный пример `ioc.sync` объединяется со встроенными defaults. Он использует
существующий export profile `reputation-lists`; для формирования срезов нужны
его настройка и `ioc.export.enabled`. Для расписания нужен daemon
mode; включённым sync use cases нужен JDBC service store. ConfigData lists целиком заменяют нижележащие списки:
передавайте полные entries endpoint/source/target, не частичный list patch.

```yaml
ioc:
  sync:
    enabled: true
    retry:
      max-attempts: 3
      backoff: 1s
      multiplier: 2.0
      max-backoff: 30s
      jitter: true
    endpoints:
      - name: intel-share
        transport: smb
        smb:
          host: files.example.org
          port: 445
          share: intel
          domain: CORP
          username: ${SMB_USER}
          password: ${SMB_PASSWORD}
          encryption: required
          connect-timeout: 10s
          request-timeout: 30s
          idle-timeout: 5m
    fetch:
      enabled: true
      interval: 1m
      sources:
        - name: incoming-intel
          endpoint: intel-share
          remote-path: /incoming
          include: [ "*.htm", "*.html", "*.docx" ]
          exclude: [ "*.tmp", "*.part", ".*" ]
          change-notify:
            enabled: false
            debounce: 3s
    publish:
      enabled: true
      interval: 5m
      targets:
        - name: reputation-delivery
          endpoint: intel-share
          remote-path: /out/reputation
          export-profile: reputation-lists
```

Выберите реальные host/share/domain и пути; для подходящей локальной учётной записи
оставьте `domain` пустым. `remote-path` — путь внутри share, не UNC. Listing
нерекурсивный. Include/exclude сопоставляют имена; пустой include принимает все
regular files, поэтому задавайте форматы явно. Логические имена/ссылки должны быть
корректны и уникальны; strict preflight отвергает неизвестные keys/transports/profiles
до I/O. Полный контракт — в [справочнике конфигурации](configuration.md).

### Повторы и расписание

| Настройка | Default | Значение |
|---|---|---|
| `retry.max-attempts` | `3` | Всего вызовов, включая первый, для ошибок с разрешённым немедленным повтором |
| `retry.backoff` / `multiplier` / `max-backoff` | `1s` / `2.0` / `30s` | Растущая, ограниченная сверху задержка немедленных повторов |
| `retry.jitter` | `true` | Случайное распределение задержек уменьшает одновременные bursts |
| `fetch.interval` | `1m` | Периодический listing/recovery backstop независимо от push |
| `change-notify.enabled` / `debounce` | `false` / `3s` | Опциональное ускорение сигналами и задержка объединения |
| `publish.interval` | `5m` | Discovery/retry backstop завершённых локальных slices |

Только `TRANSIENT` допускает немедленный micro-retry. `UNREACHABLE` и
`RESOURCE_EXHAUSTED` откладывают работу до следующего цикла. Authentication,
permission, missing objects и security-policy failures не исправляются немедленными
повторами. Следующие циклы могут встретить прежние ошибки: исправляйте причину,
а не только число попыток. Watch reconnect использует эту группу backoff, но не
ограничивает срок жизни числом `max-attempts`. Событие не гарантирует мгновенный
publish: admission, backlog и активная транзакция могут его задержать.

### Политика подключения endpoint

| Настройка | Default | Область |
|---|---|---|
| `port` | `445` | TCP для обычных и выделенных watch connections |
| `encryption` | `required` | Только SMB3 плюс проверка effective session encryption до share I/O |
| `connect-timeout` | `10s` | TCP connection, не весь cycle и не DNS deadline |
| `request-timeout` | `30s` | Один обычный SMB request, не idle watch wait |
| `idle-timeout` | `5m` | Время жизни неиспользуемого cached regular client независимо от watch lease |

`preferred` и `disabled` явно допускают более слабую границу; это не исправление
сервера, который должен обеспечивать обязательное encryption. Неподдерживаемый
`read-timeout` даёт migration hint. Legacy `encrypt` — временный rollback alias;
не задавайте его вместе с `encryption`. Новая конфигурация использует только
последний ключ. Выбирайте timeouts по измеренной сетевой/серверной задержке и
требованиям recovery; idle lifetime ниже известного firewall timeout не доказывает
немедленного обнаружения любой оборванной session.

## Эксплуатация, сбои и health

### Права и credentials

| Место | Учётная запись обычного sync | Другие identities |
|---|---|---|
| Source directory | Read/list/traverse, notification access для push; без write/delete | Producer пишет и переименовывает завершённые файлы |
| Publish target | Create/write/read/stat/list, rename и delete в собственном namespace | Consumers читают/list, не меняют |

Администратор создаёт share, настраивает parent traversal и filesystem ACL.
Обычный sync не создаёт share и не делает полный negative ACL audit при startup.
Notification support/permissions требуют проверки target. Не совмещайте обычный
source directory с managed-import ownership paths.

Передавайте credentials через environment placeholders. В systemd deployment
используется EnvironmentFile `etc/ioc-extractor.env`, mode `0640`; это не shell
script. Ротируйте credentials по account policy, обновляйте файл и перезапускайте
сервис; горячей ротации нет. Credentials остаются в памяти для reconnect, хотя
временные character arrays очищаются. Не передавайте пароли в CLI arguments,
committed YAML и диагностике.

### Интерпретация сбоев

| Ситуация | Поведение / действие оператора |
|---|---|
| Недоступна сеть или исчерпаны server resources | Следующие cycles/reconnect могут восстановиться; исследуйте длительный backlog и ёмкость сервера |
| Authentication, permission или encryption policy failure | Исправьте account/ACL/policy; повторы их не чинят |
| Source меняется во время download | Стабильный remote snapshot не обещан; обеспечьте producer handoff вместо надежды на deduplication |
| Crash во время fetch | Незавершённый staging скрыт; уже полученный файл может скачаться снова при потере ledger closure |
| Crash/потерянный ответ publish | Сохраните local slice/ledger; совпавший remote marker позволяет завершить recovery |
| Marker mismatch или missing/corrupt local slice | Сохраните evidence и исследуйте binding/content; не удаляйте marker ради перезаписи |
| Daemon долго выключен | Reconcile обрабатывает доступные sources и retained slices; удалённые inputs или вручную удалённые pins не восстановить |

`sync` health показывает наблюдения source/target, detection timing, watch state,
endpoint lanes и `publishPending`/`publishInProgress`/`publishFailed`. Нетронутый
опциональный source/target может иметь `UNKNOWN`: это не успешный probe.
У reconnect watch есть grace window до degradation. Временный source outcome может
иметь `DEGRADED`, а durable `FAILED` publish pair даёт sync `DOWN` до разрешения.
Aggregate status и наблюдение отдельной операции — разные представления.

`smbTransport` показывает application-owned connections/sessions/tree connections,
`pooledSessions`, `activeWatchSessions`, `plannedSteadySessions`, failures и
resource exhaustion. Planned demand — не configured server limit и не доступный
headroom. Суммарное usage, quotas и других клиентов проверяйте серверным monitoring.
Не измеряйте предел открытием соединений до отказа.

Логи/restart/backup описаны в [daemon operations](daemon-operations.md).
Application `event.action` различает `sync_fetch_start`/`sync_fetch_complete` и
`sync_publish_start`/`sync_publish_complete`; читайте также diagnostic/severity и
durable outcomes. Пустые cycles обычно DEBUG. Сохраняйте owned recovery state и
pinned slices, не удаляя их ради уменьшения очереди.

### Ручной CLI

Примеры используют prefix установщика `/opt/ioc-extractor`; подставьте свой
(`deploy-local.sh` по умолчанию использует `/srv/ioc-extractor`). Launcher требует
sudo и загружает configured service environment. Согласуйте **изменяющие** команды
с daemon: отдельный процесс не разделяет его endpoint exclusion.

```bash
sudo /opt/ioc-extractor/bin/ioc sync --help
sudo /opt/ioc-extractor/bin/ioc sync fetch --source incoming-intel --endpoint intel-share --dry-run
sudo /opt/ioc-extractor/bin/ioc sync publish --profile reputation-lists --target reputation-delivery --endpoint intel-share --dry-run
sudo /opt/ioc-extractor/bin/ioc sync all --source incoming-intel --profile reputation-lists --target reputation-delivery --endpoint intel-share --dry-run
sudo /opt/ioc-extractor/bin/ioc health --component sync --json
sudo /opt/ioc-extractor/bin/ioc health --component smbTransport --json
```

Убирайте `--dry-run` только для намеренного изменения. Fetch dry-run читает remote
metadata без download; publish dry-run читает local slices/ledger, не проверяя
подключением права записи на target. Полный CLI может инициализировать/мигрировать
service DB даже в dry-run. Для репетиции без изменения рабочего storage нужна
изолированная конфигурация, а не только этот флаг.

`sync all` сначала проверяет обе selection, затем fetch и publish **существующих**
slices. Он не запускает и не ждёт ingest/export нового документа. Ненулевой failure
count даёт sync exit `1`, нулевой — `0`; setup, listing и selection exceptions
тоже могут завершить команду ошибкой. Пустая работа с `0` не доказывает delivery
или ACL. Для health только `UP` даёт `0`, разобранный non-UP, включая
`DEGRADED`/`UNKNOWN`, — `1`, request/parse failure — `2`.

## Подготовка SMB-сервера

Это шаблоны **нового выделенного дерева** для администратора. Заранее создайте
названные accounts/groups producer/consumers. Для административных запросов
используйте отдельную monitoring identity; приложению не нужны server admin rights.
Существующие деревья требуют ACL migration с сохранением нужного доступа,
а не слепого выполнения примеров.

### Linux / Samba

На Debian/Ubuntu-подобном host с установленной Samba создайте service account и
выделенное дерево. Accounts/groups `producer` и `consumers` должны существовать.
Producer должен создавать group-readable files, в том числе при записи вне Samba.

```bash
sudo groupadd iocsync
sudo useradd -M -s /usr/sbin/nologin -G iocsync svc-ioc
sudo smbpasswd -a svc-ioc
sudo install -d -o root -g root -m 0755 /srv/intel /srv/intel/out
sudo install -d -o producer -g iocsync -m 2750 /srv/intel/incoming
sudo install -d -o svc-ioc -g consumers -m 2750 /srv/intel/out/reputation
```

Группа output получает чтение/traverse, не group write. Ниже минимальный dedicated
server config; согласуйте с существующей server policy. Comments вынесены в
отдельные строки, чтобы не стать частью parameter values.

```ini
[global]
    server min protocol = SMB3_00
    server smb encrypt = required
    change notify = yes
    kernel change notify = yes

[intel]
    path = /srv/intel
    browseable = no
    read only = yes
    write list = producer, svc-ioc
    valid users = producer, svc-ioc, @consumers
    create mask = 0640
    force create mode = 0640
    directory mask = 2750
    force directory mode = 2750
```

Share write permission не обходит filesystem rights. Parents должны разрешать
traversal; setgid и creation modes сохраняют чтение нужными consumer/source groups.
Отдельно проверьте ACL/umask локальных producers. Notifications для локальной записи
зависят от server/filesystem backend; проверьте их, а не предполагайте push для
любого producer path. Polling по-прежнему обнаруживает retained files.
Семантика параметров — в [справочнике Samba](https://www.samba.org/samba/docs/current/man-html/smb.conf.5.html);
сверяйтесь и с документацией установленной версии.

Проверьте конфигурацию до reload. Запросы ниже показывают config и текущую share
activity; JSON доступен не в каждой установленной версии.

```bash
sudo testparm -s
sudo testparm -s --section-name='intel' --parameter-name='max connections'
sudo smbstatus --shares --json
```

Samba `max connections = 0` означает отсутствие настроенного share limit;
current share connections — не sessions per connection. Ограничьте firewall
TCP 445 адресами application hosts или выбранным портом. Для SELinux настройте
labels выделенного дерева по поддерживаемой Samba policy дистрибутива и проверьте
доступ; отключение enforcement не является prerequisite.

### Windows Server

Создайте service/producer accounts и consumers group по local/AD policy.
Запретите interactive logon service identity и настройте rotation credentials.
В свежем примере нужны administrator PowerShell и отсутствующий `D:\intel`.
Service/producer/consumers получают в root только traversal/read; SYSTEM и
Administrators сохраняют наследуемый full access ниже.

```powershell
New-Item -ItemType Directory -Path D:\intel
icacls D:\intel /grant:r "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" "svc-ioc:RX" "producer:RX" "consumers:RX"
icacls D:\intel /inheritance:r
New-Item -ItemType Directory -Path D:\intel\incoming, D:\intel\out\reputation
icacls D:\intel\incoming /grant:r "svc-ioc:(OI)(CI)RX" "producer:(OI)(CI)M"
icacls D:\intel\out /grant:r "svc-ioc:(OI)(CI)RX" "consumers:(OI)(CI)RX"
icacls D:\intel\out\reputation /grant:r "svc-ioc:(OI)(CI)M" "consumers:(OI)(CI)RX"
New-SmbShare -Name intel -Path D:\intel -ChangeAccess svc-ioc,producer -ReadAccess consumers -EncryptData $true
```

Проверяйте exit code каждой native command и effective ACL до открытия share;
остановитесь при неудачном шаге. `/grant:r` заменяет explicit grants названных
identities, не все inherited permissions. Пример снимает inherited broad grants
только у **свежего** root и не очищает произвольные существующие descendants.
[Справочник icacls](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/icacls)
и [New-SmbShare](https://learn.microsoft.com/en-us/powershell/module/smbshare/new-smbshare)
отдельно описывают filesystem и share permissions. Group memberships и inherited
rights всё равно требуют effective-access test. Выключите SMB1 и ограничьте
firewall SMB-In адресами application hosts по server policy.

```powershell
Get-SmbShare -Name intel | Select-Object Name, EncryptData, ConcurrentUserLimit, CurrentUsers
Get-SmbServerConfiguration | Select-Object MaxSessionPerConnection, EnableSMB1Protocol
(Get-SmbSession | Measure-Object).Count
```

Share users, sessions и `MaxSessionPerConnection` — разные величины. Сопоставьте
спрос приложения с реальными server limits через administrative monitoring identity.
Для NAS нужны те же функциональные, encryption, ACL и notification checks;
не предполагайте автоматически Windows behavior.

## Квалификация и чек-листы

На выделенных test source/target проверьте реальной application identity:
read/list source успешны, source create/overwrite/rename/delete запрещены,
target create/write/read/rename/delete успешны. Отдельной producer identity
проверьте запрет изменения publish output, consumer identity — запрет создания
и замены его файлов. Producer permissions проверяются отдельно от service.

Read probe с host, имеющего актуальный Samba client, может явно запросить encryption
с интерактивным вводом пароля, без него в command line:

```bash
smbclient //files.example.org/intel -U svc-ioc --client-protection=encrypt -c 'cd incoming; ls'
```

См. [справочник smbclient](https://www.samba.org/samba/docs/current/man-html/smbclient.1.html).
Probe не доказывает application negotiation, negative permissions, publication
или crash recovery. Нужны isolated end-to-end slice и независимая проверка хешей
получателем. Не квалифицируйте изменением производственного source directory.

До включения запишите точные binary/config/server identity, подтвердите complete
producer handoff, непересекающиеся names/paths, environment credentials, required
encryption и source/target ACLs. Проверьте диск, DB/ledger backup и ёмкость pinned
slices. Dry-run подтверждает только selection/read; ноль pending slices — не publish test.

После включения проверьте попадание synthetic source в canonical storage и
completed slice в final remote path с совпавшими manifest/files. Сверьте durable
ledger outcome, source/target health и capacity signals. Для push проверьте ACTIVE,
реальные signals, reconnect и сохранённый polling. Явно запишите неподдержанные
capabilities. Opt-in external suites и evidence подчиняются
[TESTING](../../TESTING.md); offline skip не является live SMB qualification.

| Симптом | Первые проверки |
|---|---|
| Тихий пропуск второго source | `OPS-11`: path/size/mtime разных endpoints и durable fetch identity |
| Два fetched outcomes, один inbox file | `OPS-12`: одинаковое final name, concurrent writers; сохраните logs/ledger до retry |
| Постоянный DOWN | Component details, failed pairs, credentials/ACL/encryption, local slice integrity |
| Resource exhaustion / smbTransport DEGRADED | Planned/owned demand и server quotas/current usage; избегайте reconnect storm |
| Push включён, latency соответствует polling | Watch state/signals, capability и producer write path |
| Частичные данные source | Producer писал сразу в final name; debounce не completion barrier |
| Slices закреплены сверх retention limits | Отсутствующие/nonterminal delivery obligations; не удаляйте вручную |
