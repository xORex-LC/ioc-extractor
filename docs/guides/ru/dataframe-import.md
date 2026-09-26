# Управляемый импорт dataframe

Этот гайд описывает эксплуатацию contract-driven CSV import из выделенного
local-каталога или SMB-шары. Import по умолчанию отключён и пишет в ту же
canonical SQLite truth, что и обычная extraction.

## Перед включением import

1. Создайте backup `var/db`, live-конфигурации и environment-файла.
2. Сохраните `ioc.lifecycle.validity.mode: fixed`. Import не запускается с
   отключённым lifecycle, потому что каждое принятое observation должно получить
   один атомарный validity outcome.
3. Выделите отдельные source и credentials для каждого trust level. Authority
   profile источника является жёстким ceiling для artifacts, routing, formula
   handling и merge policy.
4. Определите versioned contract для каждой допустимой формы CSV. Recognition
   использует headers и declared aliases, а не имя файла или порядок колонок.
5. До включения intake проверьте representative valid, malformed, duplicate и
   ambiguous файлы.

Используйте полный справочник [configuration.md](configuration.md) и начните с
закомментированного примера production
[`application.yml`](../../../packaging/templates/application.yml). Изменение
contract version или поведения требует validated restart; runtime hot reload не
поддерживается.

## Выбор processing и update policies

- `mode: as-is` считает mapped CSV cells окончательными candidate values.
  Выполняются только explicit contract transforms.
- `mode: processed` пропускает IOC carrier cells через обычные refang,
  extraction, classification и artifact mapping policies. Pipeline-derived
  identity/match/routing values заменяют imported copies; operator metadata,
  например score, source или description, остаётся под своей merge policy.
- `routing: target-only` — безопасный default. Используйте
  `related-artifacts`, только если contract объявляет каждую related branch, а
  authority profile это разрешает.
- `fill-missing` — безопасный merge default. Используйте `authoritative` только
  для source, которому разрешено заменять non-null values и явно очищать их
  пустой ячейкой либо configured null literal.
- `renew-unchanged` независимо определяет, продлевает ли TTL byte-equivalent
  принятая row. Отсутствующие rows никогда не удаляют, не изменяют и не
  продлевают local records.

Каждая CSV row атомарна для всех настроенных branches. Compound fields, например
URL вместе с IP в `address_blacklist` или несколько hashes одного файла,
остаются одной row. Другое identity-bearing value является новой record.

## Проверка без import

Запустите preview с той же installed-конфигурацией и source allowlist:

```bash
/srv/ioc-extractor/bin/ioc import validate \
  --source trusted-local \
  --file /path/to/candidate.csv
```

Preview читает и планирует файл, но не claim-ит его, не резервирует export slot и
не пишет canonical/service state. Успешный preview является advisory: реальный
intake повторно проверяет live catalog, source evidence и active database state.

## Подача local delivery

Сформируйте и fsync-ните файл вне настроенного source-каталога, но на том же
filesystem, затем атомарно переместите завершённый файл в этот каталог. Не
используйте имя `.part` внутри наблюдаемого каталога: filename и suffix не
выбирают contract, поэтому каждый стабильный regular file является delivery
candidate. Не помещайте producer files в `processing`, `snapshots`, `staging`,
`terminal` или `quarantine`.

Сервис ждёт стабильную metadata, атомарно claim-ит файл и создаёт private
immutable snapshot до parsing. Каждый стабилизированный CSV является отдельной
delivery, включая byte-identical повторную подачу.

## Подача SMB delivery

Используйте выделенный каталог настроенной шары. Managed import является
мутирующим consumer: в отличие от обычного `sync.fetch`, он claim-ит,
перемещает и в итоге удаляет собственные managed objects. Не выдавайте эти права
read-only sync-fetch source только потому, что обе способности используют один
SMB endpoint/session pool.

Заранее создайте точный namespace под каждым настроенным managed-import source:

```text
<source>/
└── .ioc-managed-import/
    ├── processing/
    ├── terminal/
    ├── quarantine/
    └── probe/
```

Приложение никогда не создаёт эти каталоги. Перед listing кандидатов service
identity создаёт пустой reserved object в `probe`, переименовывает его через
`processing` в `terminal` и удаляет именно этот regular file. Отсутствующий
каталог или детерминированное несоответствие permission/object отключает только
этот source; следующий reconcile повторяет probe. Это positive operation check,
а не аудит ACL: приложение не может доказать, что producer действительно лишён
доступа.

Используйте две разные identity и обеспечьте на сервере как минимум такую
матрицу:

| Location / operation | Producer identity | Service identity |
|---|---|---|
| `<source>`: публикация завершённого regular file | разрешить create/write и atomic handoff; оставить только действительно нужные producer права listing/read | разрешить list/read и server-side rename в `processing` |
| `.ioc-managed-import` и все дочерние каталоги | запретить traversal, listing, read, write и delete | разрешить traversal и точные операции probe/claim/disposition/retention |
| `probe` | нет доступа | создать пустой regular file, переименовать наружу и удалить точный file при recovery |
| `processing` | нет доступа | создать через rename, читать с запрещённым write sharing, переименовать наружу; без recursive delete |
| `terminal`, `quarantine` | нет доступа | создать через rename, инспектировать и удалить точный managed regular object |

Producer и consumer должны использовать один server-side filesystem, чтобы
claim выполнялся rename без copy/delete.
Предпочтительно загружать файл в отдельный producer-owned sibling staging
каталог и выполнять один server-side rename в настроенный source. Если producer
вынужден писать сразу в source-каталог, его максимальная пауза записи должна
быть меньше stability quiet period; если это невозможно гарантировать,
увеличьте quiet period.

Включите SMB encryption, если доверие к сети не обеспечено другим
документированным control. `CHANGE_NOTIFY` уменьшает latency; complete listing
остаётся включённым и восстанавливает потерю notifications, disconnect и restart.

### Пример для Samba

Создайте namespace как administrator, затем выразите матрицу через принятые в
вашей среде POSIX ACL groups. Пример использует отдельные accounts и
предполагает, что share соблюдает filesystem ACL:

```bash
install -d -m 0770 -o ioc-service -g ioc-service /srv/ioc-import/inbox/.ioc-managed-import/{processing,terminal,quarantine,probe}
setfacl -m u:ioc-service:rwx,u:ioc-producer:-wx,m::rwx /srv/ioc-import/inbox
setfacl -m d:u:ioc-service:rw-,d:m::rw- /srv/ioc-import/inbox
setfacl -R -m u:ioc-service:rwx,u:ioc-producer:---,m::rwx /srv/ioc-import/inbox/.ioc-managed-import
```

Адаптируйте owner/group/default ACL к identity mapping вашего Samba server.
Проверьте от имени producer, что traversal/list/read/write/delete private
namespace завершаются отказом, а от имени service account — что application
capability gate становится ready. Успешный service probe сам по себе не
доказывает запрет producer.

### Пример для Windows Server

Создайте четыре каталога в NTFS backing folder шары. Выдайте service account
право `Modify` на `<source>` и private subtree. Producer должен иметь только
права source folder, необходимые для публикации завершённого файла; удалите
унаследованный доступ producer/group к `.ioc-managed-import`, сохранив
Administrators/SYSTEM и service identity. Используйте Advanced Security или
`icacls`; точные principals и inheritance flags зависят от deployment.

Проверьте обе identity через `runas` или отдельные sessions: producer не должен
проходить в private subtree или получать его listing, service account должен
проходить capability gate и claim-ить созданный producer candidate. Приложение
не инспектирует и не сертифицирует NTFS/Samba ACL policy.

Opt-in тест репозитория `SmbManagedImportHardeningContractIT` выполняет это
two-identity доказательство на заранее подготовленном fixture. Он не создаёт и
не удаляет namespace рекурсивно; value-free system-property invocation описан
в README SMB adapter.

## Наблюдение за выполнением

```bash
/srv/ioc-extractor/bin/ioc import status
/srv/ioc-extractor/bin/ioc health
journalctl -u ioc-extractor --since -15m
```

Status показывает aggregate state counts и durable head sequence, state, age,
retry count/delay и безопасный diagnostic code. Он намеренно не выводит IOC
values, source paths, filenames и digests. Retry head удерживает FIFO order;
последующие deliveries его не обгоняют.

Packaged low-latency preset использует import listing/stability/retry `2s/2s/2s`,
export coalescing/backstop/max-cap `1s/10s/30s`, ordinary ingest
polling/stability `5s/2s` и SMB notification debounce `1s`. Эти значения
обеспечивают event-driven responsiveness и сохраняют bounded correctness scans.
Увеличьте stability window для producer, который не публикует atomic rename.

## Outcomes и recovery

- Successful deliveries перемещаются в protected terminal area вместе с safe
  JSON report. Rejected deliveries перемещаются в quarantine.
- Malformed file, ambiguous contract или hard parser limit отклоняет всю
  delivery. При `accept-valid` отдельная invalid row не отбрасывает остальные
  valid rows; `reject-delivery` является строгой альтернативой.
- Canonical promotion — одна cross-artifact transaction. Durable dataframe
  receipt предотвращает повторную mutation после crash.
- Startup recovery выполняется до ordinary ingestion и import intake. Не
  перемещайте, не редактируйте и не удаляйте private runtime files во время
  recovery.
- Manual queue skipping и forced completion намеренно отсутствуют.

Чтобы повторно подать retained terminal evidence как новую occurrence:

```bash
/srv/ioc-extractor/bin/ioc import replay --delivery <delivery-id>
```

Replay не переоткрывает старую terminal record. Он получает новый delivery ID и
sequence и сохраняет causal link к original.

## Чек-лист инцидента

1. Прекратите добавлять файлы; не меняйте private managed-import directories.
2. Сохраните `ioc import status`, `ioc health` и свежие service logs.
3. Проверьте свободное место для `var/import`, обеих databases и их WAL sidecars.
4. Проверьте source permissions, SMB reachability и не удерживает или не меняет
   ли producer файл.
5. Исправляйте конфигурацию через `ioc-config check` и `ioc-config apply`; не
   редактируйте live YAML на месте.
6. Используйте replay для retained terminal delivery только после понимания
   первопричины.

Внутренние механизмы и инварианты описаны в
[capability document](../../dev/dataframe-import.md) и
[ADR-0024](../../ADR/0024-managed-dataframe-import.md).
