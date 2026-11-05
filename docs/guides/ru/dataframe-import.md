# Управляемый импорт dataframe

[English version](../dataframe-import.md)

Руководство описывает эксплуатацию импорта CSV по контрактам из выделенного
локального каталога или SMB share. Import по умолчанию отключён и пишет в ту же
каноническую SQLite, что и обычная extraction.

## Перед включением импорта

1. Приостановите producers и остановите сервис до создания согласованной
   recovery point: обе БД с соответствующими SQLite side files, owned recovery
   files, configuration, environment file и совместимый binary. Следуйте
   [процедуре backup](deployment.md); копия работающей основной БД без WAL
   не является согласованным backup.
2. Сохраняйте `ioc.lifecycle.validity.mode: fixed`. Импорт не запускается с
   отключённым lifecycle: каждое принятое наблюдение должно получить один
   атомарный validity outcome.
3. Выделите отдельные source и credentials каждому уровню доверия. Authority
   profile источника ограничивает artifacts, routing, formula handling и merge.
4. Определите версионируемый контракт каждой допустимой формы CSV. Recognition
   использует headers и объявленные aliases, а не имя файла или порядок колонок.
5. До включения intake проверьте типичные valid, malformed, duplicate и ambiguous
   файлы.
6. Для каждого SMB source заранее создайте private namespace и проверьте ACL
   отдельными producer/service identities до runtime. Квалифицируйте целевое
   семейство серверов: положительный service probe не доказывает запрет producer
   и не заменяет отложенную внешнюю проверку `OPS-8`.

Используйте полный справочник [configuration.md](configuration.md) и начните
с закомментированного примера production
[`application.yml`](../../../packaging/templates/application.yml). Смена contract
version или поведения требует проверенного restart; runtime hot reload не
поддерживается. Sources и contracts — списки: сохраняйте их полный нужный состав
при overlay. Частичная замена может удалить другие sources или поставляемый
aggregate contract; автоматически она не добавляет один элемент.

## Выбор политик обработки и обновления

- `mode: as-is` считает mapped CSV cells окончательными candidate values.
  Выполняются только явные contract transforms.
- `mode: processed` проводит IOC carrier cells через обычные refang, extraction,
  classification и artifact mapping policies. Pipeline-derived identity/match/
  routing fields заменяют imported copies; metadata оператора, например score,
  source или description, подчиняется merge policy. Каждый processed contract
  обязан объявить `processed-route` с именованным планом, явными input cells и
  разрешёнными output fields, даже при отключённом intake. Вычисленные match/
  routing fields вне output allowlist не импортируются автоматически.
  [Руководство по маршрутам](../ioc-processing-routes.md#bind-a-processed-import-contract)
  показывает явные bindings.
- `routing: target-only` — безопасный default. Используйте `related-artifacts`,
  только если контракт объявляет все related branches и authority profile
  разрешает их.
- `fill-missing` — безопасный merge default. `authoritative` подходит только
  источнику с правом заменять non-null values и явно очищать их пустой ячейкой
  либо настроенным null literal.
- `renew-unchanged` независимо управляет продлением TTL неизменной принятой
  строкой. Отсутствующие строки не удаляют, не меняют и не продлевают локальные записи.

### Presence и merge

| Вход по объявленному контракту | Смысл для существующей записи |
|---|---|
| Optional mapped header отсутствует | `ABSENT`: не менять поле |
| Пустая ячейка или точный настроенный null literal | `NULL`: очистить только при разрешённой политике очистки |
| Остальной текст | `VALUE`: применить явные transforms, validation и merge policy |

Короткая запись неверна, а не ABSENT. Кавычки вокруг `NULL` или пустой ячейки
не экранируют import null instruction. Если нужен буквальный текст `NULL`,
выберите контракт с неколлидирующим null literal; не полагайтесь на export
quoting. Пробелы являются значением, пока их не убрали явные transforms.
Effective merge policy выбирается по column, artifact, затем contract default
и ограничивается authority источника.
[Таблица merge](../../dev/dataframe-import.md#cell-presence-merge-and-identity)
также описывает `keep-existing`, `replace-non-null` и `reject-conflict`.

`renew-unchanged: false` подавляет иначе неизменное TTL confirmation, а не
подтверждение принятой публичной мутации. Оно не превращает отсутствующие строки
в удаления. Непробельный candidate настроенного ordered mutable field может
запросить подтверждение и при совпадающем публичном тексте; это не полная заморозка
TTL. Повтор recovery того же commit не применяет доставку как новое наблюдение.

Каждая CSV row атомарна для настроенных branches. Составные поля, например URL
с IP в `address_blacklist` или несколько хешей одного файла, остаются одной
строкой. Итоговый record key и объявленные match keys определяют совпадение с
активными canonical rows. Несколько active matches отклоняют logical row.
Изменение стабильного primary key найденной записи — конфликт, а не новая вставка.

Объявляйте `columns[].validation`, когда источник обязан поставлять корректные
IOC values. AS_IS и пустой список transforms сами не включают эту проверку:

| Validator | Допустимая non-null ячейка |
|---|---|
| `bare-ip` | Один bare IPv4 |
| `clean-domain` | Один clean domain или subdomain |
| `url-address` | Один поддерживаемый network address с URL/address detail |
| `network-address` | Один поддерживаемый IPv4, domain или URL, включая подробные формы |
| `hash` | Один MD5, SHA1 или SHA256 |
| `md5`, `sha1`, `sha256` | Один хеш заданного алгоритма |
| `canonical-integer` | Signed 64-bit decimal integer без ведущих нулей, плюса, пробелов, дроби или экспоненты |

IOC validators проверяют refanged whole-cell candidate и допускают пробельное
окружение; они не переписывают принятую ячейку и не очищают её до host. Для
нормализации хранимой формы объявляйте явные transforms. Validators не обходят
formula policy. Например, `canonical-integer` принимает `10` и отвергает `010`
или `10.0`; NULL остаётся NULL. Когда INTEGER участвует в record/match key,
используйте одно допустимое числовое представление.

Дубли внутри файла используют record key основного артефакта. KEEP_FIRST
сохраняет первую принятую целую строку и её requested slot; слоты остальных
принятых строк не уплотняются. Между доставками выбранные match keys также
определяют совпадение с прежней записью. Одна смена duplicate selection не
создаёт новой канонической идентичности. `coalesce` объединяет совместимые
вклады ячеек и отвергает конфликтующие группы; `last-nonempty` выбирает одну
целую строку через selection column, без слияния отдельных ячеек.

Только явная requested-slot binding интерпретирует входной ID как запрос слота.
Он никогда не становится доверенным canonical row/lifecycle ID. Запросы
используют `requested-slot.profile`; если ID должны появиться в данном output,
это должен быть профиль публикации артефакта. `preserve-existing` сохраняет слот
найденного survivor, даже если импортированный ID отличается. Новая запись с
запросом занятого слота получает минимальный доступный; receipt и terminal
report фиксируют переназначение. Это разные случаи: `existing-record-policy`
управляет только несовпадением survivor. В уже инициализированном реестре
найденная lifecycle без слота отвергается при обеих политиках; не подставляйте
её canonical ID. Допустимый экспорт может сначала согласовать такое назначение.

## Проверка без импорта

Import commands требуют включённого import, полного каталога и fixed lifecycle.
Установленный wrapper работает в oneshot и не открывает daemon intake. Для
проверок до включения live source используйте отдельную конфигурацию и копию
storage, а не включайте неквалифицированный intake у работающего daemon.
С установленными конфигурацией и source allowlist выполните:

```bash
sudo /srv/ioc-extractor/bin/ioc import validate \
  --source trusted-local \
  --file /path/to/candidate.csv
```

Validator читает, распознаёт и отображает файл. Он не получает владение source,
не резервирует slots и не продвигает canonical rows. Полная команда запускает
Spring, может инициализировать/мигрировать storage и создавать workspace
directories; это не обещание отсутствия filesystem writes. Используйте
стабильный локальный regular file без symlink вне managed inboxes. SMB delivery
можно проверить по локальной копии candidate с соответствующим source ID.

Preview возвращает `0`, когда все отображённые строки корректны, и `3` при
неверном preview; configuration, missing-file и startup failures — отдельные
отказы. Valid preview не делает active-DB matching, merge/slot planning или
canonical commit. Intake заново проверяет catalog, owned bytes и текущее
каноническое состояние. Preview отображает строки независимо: он не сводит
within-delivery duplicates и не обнаруживает COALESCE group conflicts. Его
accepted-row count не равен итоговому staging/canonical-commit count.

## Подача локальной доставки

Сформируйте файл и выполните fsync вне source directory, но на том же filesystem,
затем атомарно переместите готовый файл в каталог. Не используйте `.part` внутри
наблюдаемого каталога: имена и суффиксы не выбирают контракт, поэтому каждый
стабильный regular file является кандидатом доставки. Не помещайте producer
files в `processing`, `snapshots`, `staging`, `terminal` или `quarantine`.

Сервис ждёт стабильную metadata, атомарно получает владение и создаёт private
immutable snapshot до parsing. Каждый стабилизированный CSV — новая доставка,
включая повторную подачу тех же bytes.

## Подача SMB-доставки

Используйте выделенный каталог настроенной шары. Managed import — мутирующий
consumer: в отличие от обычного `sync.fetch`, он получает владение, перемещает
и в итоге удаляет свои managed objects. Не выдавайте такие права read-only
sync-fetch source лишь из-за общего SMB endpoint/session pool.

Заранее создайте точный namespace под каждым managed-import location:

```text
<source>/
└── .ioc-managed-import/
    ├── processing/
    ├── terminal/
    ├── quarantine/
    └── probe/
```

Приложение не создаёт эти каталоги. До candidate listing оно под service
identity создаёт пустой reserved object в `probe`, переименовывает через
`processing` в `terminal` и удаляет именно этот regular file. Отсутствующий
каталог или детерминированно несовместимые permissions/object закрывают только
этот source; следующий reconcile повторит probe. Это положительная проверка
операций, а не ACL audit: приложение не доказывает запрет доступа producer.

Используйте две identity и задайте на сервере минимальную матрицу:

| Место / операция | Producer identity | Service identity |
|---|---|---|
| `<source>`: публикация готового regular file | разрешить create/write и atomic handoff; выдавать лишь нужные producer listing/read | разрешить list/read и server-side rename в `processing` |
| `.ioc-managed-import` и все дети | запретить traversal/list/read/write/delete | разрешить traversal и точные probe/claim/disposition/retention операции |
| `probe` | нет доступа | создать пустой regular file, переименовать наружу и точно удалить при recovery |
| `processing` | нет доступа | создать через rename, читать с запретом write sharing, переименовать наружу; без recursive delete |
| `terminal`, `quarantine` | нет доступа | создать через rename, проверить и удалить точный managed regular object |

Producer и consumer должны находиться на одном server-side filesystem, чтобы
claim использовал rename без copy/delete. Предпочтительно загружать файл в
producer-owned соседний staging directory, затем делать один server-side rename
в source. При вынужденной записи прямо в source максимальная пауза producer
должна быть меньше stability quiet period; увеличьте период, если это не гарантируется.

Сохраняйте безопасный default `encryption: required`: он требует SMB3 и
реального session encryption. `preferred` и `disabled` явно принимают более
слабую границу и требуют соответствующего deployment decision; это не aliases
включённого boolean. `CHANGE_NOTIFY` уменьшает задержку; полное listing остаётся
включённым и восстанавливает потерю уведомлений, disconnect и restart.

### Пример для Samba

Создайте namespace от администратора и выразите матрицу через принятые POSIX ACL
groups. Пример использует отдельные accounts и предполагает, что share
соблюдает filesystem ACL:

```bash
install -d -m 0770 -o ioc-service -g ioc-service /srv/ioc-import/inbox/.ioc-managed-import/{processing,terminal,quarantine,probe}
setfacl -m u:ioc-service:rwx,u:ioc-producer:-wx,m::rwx /srv/ioc-import/inbox
setfacl -m d:u:ioc-service:rw-,d:m::rw- /srv/ioc-import/inbox
setfacl -R -m u:ioc-service:rwx,u:ioc-producer:---,m::rwx /srv/ioc-import/inbox/.ioc-managed-import
```

Адаптируйте owner/group/default ACL к Samba identity mapping сервера. Проверьте
от producer запрет private traversal/list/read/write/delete, а от service account
— переход application capability gate в ready. Успешный service probe не
доказывает запрет producer.

### Пример для Windows Server

Создайте четыре каталога в NTFS backing folder. Дайте service account `Modify`
на `<source>` и private subtree. Producer нужны только права source folder для
публикации готового файла; уберите унаследованный producer/group access у
`.ioc-managed-import`, сохранив Administrators/SYSTEM и service identity.
Используйте Advanced Security или `icacls`; principals/inheritance flags
зависят от deployment.

Проверьте две identity через `runas` или отдельные sessions: producer не может
войти или получить listing private subtree, service проходит capability gate
и получает владение producer-created candidate. Приложение не инспектирует и
не сертифицирует NTFS/Samba ACL policy.

Opt-in `SmbManagedImportHardeningContractIT` проверяет это на подготовленном
fixture с двумя identity. Он не создаёт и не удаляет namespace рекурсивно.
См. [external-test policy](../../TESTING.md) и
[hardening contract](../../../adapters/adapter-transport-smb/src/test/java/com/iocextractor/adapter/out/transport/smb/SmbManagedImportHardeningContractIT.java).
Property-gated skip не является квалификацией сервера. Передавайте credentials
через environment variables fixture, а не Maven properties, остающиеся в reports.

## Наблюдение за выполнением

Известное ограничение [OPS-10](../../KNOWN-ISSUES.md): текущий SQLite status
reader может упасть, если у nonterminal head нет retry deadline. Import health
indicator использует тот же reader и также затронут. Сохраните ошибку и retained
reports/logs; не делайте вывод о неудачном canonical commit и не меняйте
deadline/ledger row ради сокрытия дефекта. Команды остаются целевым интерфейсом
наблюдения, но ошибка при этом условии не является успешным status result.

```bash
sudo /srv/ioc-extractor/bin/ioc import status
sudo /srv/ioc-extractor/bin/ioc health --component dataframeImport
sudo journalctl -u ioc-extractor --since -15m
```

Установленный wrapper требует `sudo` и работает как service identity с её
EnvironmentFile. Это локальные CLI processes; health обращается к actuator
работающего daemon, import status читает локальный service ledger.

Status показывает агрегаты состояний и сохранённые head sequence/state/age,
retry count/delay и безопасный diagnostic code. IOC values, source paths,
filenames и digests намеренно не выводятся. Повторяемая голова удерживает FIFO;
следующие доставки не обгоняют её. Exit `0` означает успешный query, а не
пустую или здоровую очередь. Флаг `Recovery complete` принадлежит oneshot
context и может быть false при здоровом daemon; readiness проверяйте через его
`dataframeImport` health component.

`CLAIMING` — получение владения; `STAGING` — подготовка; `PROMOTING` — граница
канонической транзакции; `FINALIZING` — сходимость projection/report/source
disposition после commit. Временная недоступность source или retrying head может
дать `DEGRADED`; incompatible capability или failed recovery — `DOWN`.
Не удаляйте head record для разблокирования очереди.

Packaged low-latency preset использует import listing/stability/retry
`2s/2s/2s`, export coalescing/backstop/max-cap `1s/10s/30s`, ordinary ingest
polling/stability `5s/2s`, SMB notification debounce `1s`. Эти значения ускоряют
реакцию на события, сохраняя ограниченные correctness scans. Увеличивайте
stability window, если producer не публикует atomic rename.

## Итоги и восстановление

- `SUCCEEDED` и `COMPLETED_WITH_ERRORS` публикуют защищённый local terminal
  source/report unit; `REJECTED` использует quarantine. `accept-valid` может
  зафиксировать корректные строки, сообщив об отклонённых как
  `COMPLETED_WITH_ERRORS`; частичный успех не является rejected delivery.
  Для SMB forward deliveries retention сначала удаляет точный remote terminal/
  quarantine regular object, затем local terminal/workspace/snapshot/receipt,
  а durable ledger row — последней.
- Malformed file, ambiguous contract или hard parser limit отклоняют всю
  доставку. При `accept-valid` отдельная invalid row не отбрасывает остальные
  valid rows; `reject-delivery` — строгая альтернатива.
- Canonical promotion — одна cross-artifact transaction. Сохранённый dataframe
  receipt предотвращает повторную мутацию после crash.
- Startup recovery предшествует ordinary ingestion/import intake. Не перемещайте,
  не редактируйте и не удаляйте private runtime files во время recovery.
- Ручной пропуск очереди и forced completion намеренно отсутствуют.
- Завершайте nonterminal deliveries до изменения processing/validation semantics.
  Unsealed retry должен воспроизвести pinned contract; новая политика может
  заблокировать global lane. Sealed stage или canonical receipt имеют другие
  recovery evidence. Возврат YAML не отменяет канонические commits.

Для replay retained terminal evidence как новой occurrence замените пример ID
на `deliveryId` из её сохранённого terminal report:

```bash
sudo /srv/ioc-extractor/bin/ioc import replay --delivery 'retained-delivery-id'
```

Replay не открывает прежнюю terminal record заново. Он получает новые delivery
ID/sequence, сохраняет причинную ссылку и отделён от источника: не выполняет
SMB disposition/purge. Снова применяются активные source allowlist и catalog;
это не точное повторение старого контракта или rollback. Terminal ledger entry
и защищённая local source unit должны ещё сохраняться. Успешная команда
подтверждает admission; daemon завершает обработку позже. Используйте
`--new-delivery` со стабильным выбранным ID при повторе того же replay request;
иначе каждый вызов создаёт ещё одну occurrence.

Reports содержат безопасные counts, номера логических CSV records и diagnostic
codes, а не raw IOC cells или producer paths. Warning details ограничены и не
являются точным числом всех встреченных warnings. Defaults хранят успешные units
`30d`, неуспешные, включая `COMPLETED_WITH_ERRORS`, — `90d`. Retention action
`archive` архивирует защищённую local unit; SMB forward source object она не
сохраняет: он всё равно удаляется первым. Retention/replay не удаляют canonical
records, отсутствующие во входном файле.

## Чек-лист инцидента

1. Прекратите добавлять файлы; не меняйте private managed-import directories.
2. Сохраните `ioc import status`, `ioc health` и свежие service logs.
3. Проверьте свободное место для `var/import`, обеих БД и их WAL sidecars.
4. Проверьте source permissions, SMB reachability и не удерживает/меняет ли
   producer файл.
5. Исправляйте конфигурацию через `ioc-config check` и `ioc-config apply`;
   не редактируйте live YAML на месте.
6. Используйте replay только для retained terminal delivery после понимания причины.

Внутренние механизмы и инварианты описывают
[capability document](../../dev/ru/dataframe-import.md) и
[ADR-0024](../../ADR/0024-managed-dataframe-import.md).
