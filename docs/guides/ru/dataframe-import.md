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

Скопируйте файл под временным именем внутри настроенного source-каталога,
завершите и fsync-ните его, затем атомарно переименуйте в окончательное имя
`.csv`. Не пишите сразу в окончательное имя и не помещайте файлы в `processing`,
`snapshots`, `staging`, `terminal` или `quarantine`.

Сервис ждёт стабильную metadata, атомарно claim-ит файл и создаёт private
immutable snapshot до parsing. Каждый стабилизированный CSV является отдельной
delivery, включая byte-identical повторную подачу.

## Подача SMB delivery

Используйте выделенный каталог настроенной шары. Service account нужны права на
list, read, rename/move и create-directory внутри этого каталога и его private
namespace `.ioc-managed-import`. Producer и consumer должны использовать один
server-side filesystem, чтобы claim выполнялся rename без copy/delete.

Включите SMB encryption, если доверие к сети не обеспечено другим
документированным control. `CHANGE_NOTIFY` уменьшает latency; complete listing
остаётся включённым и восстанавливает потерю notifications, disconnect и restart.

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
