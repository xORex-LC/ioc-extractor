# Подбор ресурсов для массовой обработки

[English version](../bulk-resource-sizing.md)

Для репетиции million-input batch задайте явный ресурсный профиль. Штатные
packaged defaults рассчитаны на меньший сервис: два CPU, максимальный Java heap
512 MiB, порог reclaim/throttling cgroup 768 MiB и hard limit 1 GiB. Они не
гарантируют квалифицированную ёмкость в миллион входов. Production storage
backend — Native SQLite, единственный поддерживаемый canonical backend.

## Объявите нагрузку

Считайте входные IOC occurrences до reduction, документы, итоговые canonical rows
по артефактам и уже активную БД отдельно. Один вход может попасть в несколько
артефактов. Миллион URL, сводящихся к двадцати hosts, отличается от миллиона
различных indicators.

Запишите размеры и форматы документов, preparation concurrency, output policy,
retention и число полных export/publication operations. Без этих измерений
throughput недостаточен для подбора ресурсов. Включайте stabilization и export
cadence в end-to-end время. Независимые test oracles и дополнительные diagnostic
receipt scans относятся к qualification time, а не к интервалу успешной публикации
сервиса.

Выберите критерии приёмки до прогона: область completion, максимальную control
delay, допустимое давление на память/диск, recovery checks и устойчивую arrival
rate, если она требуется. Сохраните binary hash, конфигурацию, corpus hash/seed,
начальное состояние БД и условия host/JDK/storage/network. Для распределения
нужны несколько сопоставимых прогонов; один успешный batch не устанавливает
p95/p99 latency.

## Эталонный ресурсный профиль

Конечная bulk rehearsal использует следующий бюджет приложения на номинальном
host four-vCPU / eight-GiB. Это отправная точка deployment qualification,
а не гарантия latency или изменение installation defaults.

| Ресурс | Ориентир |
|---|---|
| CPU quota | Четыре CPU equivalents (`CPUQuota=400%`); последовательные этапы всё равно могут не использовать все четыре |
| JVM | `-Xms256m -Xmx2g` с сохранением существующих OOM, UTC и encoding options |
| Cgroup memory | High 5 GiB / hard limit 6 GiB; оставить 2 GiB остальным процессам host |
| Cgroup swap | Выключен для измеряемого сервиса |
| SQLite | `balanced` tuning для service и dataframe БД; штатный default — `low-memory` |
| Private workspace документа | Disk admission ceilings: 8 GiB на invocation / 32 GiB на весь root |

Memory limits охватывают native JVM memory, SQLite и filesystem cache вместе
с Java heap. Малый измеренный heap не доказывает безвредность низкого cgroup
limit. Фиксируйте `memory.events`, swap, pressure и filesystem cache, прежде чем
связывать задержку с garbage collection или CPU. Cumulative allocated bytes —
оборот выделений, а не одновременно требуемая RAM. `MemoryHigh` вызывает
reclaim/throttling и может замедлить прогон до OOM; `CPUQuota` ограничивает CPU
time, не выделенные физические ядра. Parent cgroups и доступные CPU могут
ограничивать эффективный профиль. См.
[systemd resource control](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html).

Disk admission ceilings — максимальное разрешённое использование workspace,
не preallocated space и не полная потребность host в storage. Резервируйте место
для обеих публичных БД и WALs, mutable projections, immutable slices, pinned
inputs, retained history и coordinated backup. Увеличение ceilings не разрешает
удалять recovery evidence или сохранённые данные ради успешного прогона.

## Проверьте независимые бюджеты приложения

У следующих штатных ограничений разные владельцы. Сверьте их с объявленным
входом до изменения heap или workspace disk ceilings:

| Бюджет | Штатное значение / смысл |
|---|---|
| Document admission | 64 pending documents, 4 GiB total source bytes, 512 MiB на source |
| Подготовка документа | Два workers, configured window четыре; workspace admission может уменьшить effective window |
| Workspace reservation | `memory-bytes=67108864` — общая оценка допуска, не process memory limit |
| Encoded row/field | `maximum-row-bytes=262144`, `maximum-field-bytes=65536`; строки и отдельные поля ограничены |
| Private document disk | 2 GiB на workspace / 8 GiB на root; source pin — не более четверти per-workspace allowance |
| Managed-import snapshot | Отдельный `maximum-snapshot-bytes=268435456` (256 MiB); document limits его не увеличивают |

Предлагаемый document disk profile 8/32 GiB сохраняет остальные ограничения.
В частности, увеличение workspace disk не поднимает source bound детектора
512 MiB и не увеличивает encoded fields. Oversized unclaimed documents остаются
в inbox, где full polling может увидеть их снова; их нужно исследовать даже при
health UP. Claimed work следует своему durable recovery state.
Отказ по HTML parser-region зависит и от структуры документа, даже при малом
source file; увеличение workspace disk не снимает это ограничение parser.

Workspace reservation может отвергнуть новую работу при свободном host disk:
live pins резервируют полный allowance, а сохранённые inactive pins также
учитываются. Поэтому увеличение per-workspace allowance может сократить число
доступных concurrent admissions, если root-wide budget недостаточен. Workspace
retention удаляет просроченные inactive pins при следующем open; отдельного
idle cleanup timer нет. Формулу допуска и границы владения см. во
[внутреннем описании ёмкости](../../dev/ru/native-capacity.md#текущие-ограничения-исполнения-и-допуска),
полные fields/validation — в [configuration reference](../configuration.md).

## Подготовьте deployment candidate

Следуйте [deployment](../deployment.md) для backup, validation, activation и
rollback. Соберите полный candidate из текущего YAML оператора; внесите
следующие scalar overrides в существующие sections, сохранив routes, schemas,
lifecycle и remote endpoints вместо их замены benchmark policy:

```yaml
ioc:
  storage:
    service:
      sqlite:
        tuning: balanced
    dataframe:
      sqlite:
        tuning: balanced
  processing:
    workspace:
      workspace-bytes: 8589934592
      total-disk-bytes: 34359738368
```

Сохраняйте credentials и существующие JVM safety options в установленном
EnvironmentFile при изменении heap bounds. Соответствующие systemd resource
overrides:

```ini
[Service]
CPUQuota=400%
MemoryHigh=5G
MemoryMax=6G
MemorySwapMax=0
```

Изменение одного YAML не меняет systemd или JVM resource limits сервиса.
Проверьте candidate через `ioc-config check` и примените все части вместе через
контролируемую deployment procedure. После activation сохраните effective limits
из running cgroup; запрошенные значения не доказывают их применение host.
Сначала выполните репетицию на isolated installation. Не используйте БД или
inbound/outbound transport namespace действующего сервиса для capacity probe.

`ioc-config check` проверяет поддерживаемую область конфигурации candidate;
он не измеряет доступные RAM/disk, не проверяет throughput и не применяет
systemd limits. У transient installed `ioc` CLI свои JVM launch options, поэтому
изменение daemon EnvironmentFile heap не квалифицирует memory envelope этого CLI.
Ограниченный heap validation helper также отличается от daemon run profile.

## Управляйте исполнением и публикацией отдельно

`ioc.ingestion.concurrency` ограничивает детектор. Чтобы допустить только один
подготавливаемый документ одновременно, задайте execution limits явно:

```yaml
ioc:
  ingestion:
    execution:
      preparation-workers: 1
      window: 1
```

Packaged defaults — два preparation workers и window четыре; canonical promotion
остаётся упорядоченным. Включайте эти значения в каждую measurement identity.
Не сравнивайте два workloads как имеющие одинаковую concurrency.

Для конечного burst существующий export quiet period позволяет объединять
immutable slices и remote publications. Более долгий quiet period также
добавляет задержку после последнего документа. Он не устраняет per-document
canonical writes или mutable CSV projection. Сохраняйте max-cap policy
ограниченной и проверяйте фактическое число slices/publications; одна итоговая
публикация не следует из конфигурации сама по себе. См.
[daemon operations](../daemon-operations.md) и
[configuration reference](../configuration.md).

Native SQLite может обрабатывать весь документ внутри artifact transaction;
document writes фиксируются по артефакту, тогда как managed import имеет
cross-artifact transaction. Поздний отказ документа может следовать за уже
состоявшимися durable writes. Поэтому giant document может уложиться в batch-time
goal, задерживая controls гораздо дольше, чем много меньших документов. Измеряйте
maximum writer hold и control delay отдельно от общего throughput. Не разделяйте
canonical transaction, не ослабляйте durability и не меняйте expiry semantics
ради сокращения этого измерения.

## Наблюдение за прогоном

На rehearsal host следующие read-only команды показывают настроенные unit limits
и daemon capacity contributor (подставьте выбранные unit/prefix):

```bash
systemctl show ioc-extractor.service \
  --property=ControlGroup,CPUQuotaPerSecUSec,MemoryHigh,MemoryMax,MemorySwapMax
sudo /opt/ioc-extractor/bin/ioc health --component dataProcessingCapacity --json
```

Health command возвращает 0 только для UP, 1 для полученного non-UP state и 2
при ошибке запроса/ответа. Сохраняйте nonzero result вместе с ответом; не теряйте
его в scripted rehearsal. Installed launcher требует root и использует service
account/environment. См. [daemon operations](../daemon-operations.md).

| Наблюдение | Интерпретация |
|---|---|
| `documentExecution` pending/oldest age/saturation | Backlog и admission pressure; дополнительно проверяйте unclaimed inbox files вне owned queue |
| `preparationPressure` leases и last checked bytes | Cached quota observations, не текущий RSS, свежий disk scan или полный reserved-disk amount |
| `writerOperations`, `queuedWriters` | Cumulative/max wait и hold по операции в наносекундах с process start; не текущая длительность операции или percentile |
| `activeSnapshotReaders`, `queuedSnapshotReaders`, `walBytes` | Read ownership/queue и dataframe WAL file size; обе пары database/WAL проверяются отдельно |
| `memory.current`, `memory.stat`, `memory.events`, `memory.pressure` | В cgroup v2 — общий charge/cache и reclaim/OOM/pressure; сохраните cgroup и before/after counters |
| `cpu.stat`, CPU pressure и I/O pressure | Throttling или contention, способные объяснить низкий throughput без heap shortage |

Читайте kernel counters по сообщённому unit `ControlGroup` на соответствующем
cgroup mount; учитывайте более строгие ancestor limits. Явно учитывайте расходы
collector/agent, если они разделяют cgroup. Счётчики определены в
[kernel cgroup v2 reference](https://docs.kernel.org/admin-guide/cgroup-v2.html),
их наличие зависит от host. Один health UP не обеспечивает queue, disk-pressure
или latency budgets. Restart обнуляет application counters, поэтому разделяйте
интервалы наблюдения на границе restart.

Долгоживущие readers могут удерживать WAL history. SQLite `journal_size_limit`
не является live WAL ceiling; успешное удаление history не обязано сразу
уменьшать файл БД. Измеряйте reusable free space и supported maintenance вместе
с физическим DB/WAL size; не удаляйте live WAL и не сокращайте retention ради
capacity result. Процедуры cleanup и backup принадлежат
[storage](../../dev/storage.md) и [daemon operations](../daemon-operations.md).

## Завершите репетицию

Проверьте полные canonical fields и keys, provenance, public IDs/slots,
terminal deliveries, receipt replay и успешную remote verification. Сохраняйте
неудачные прогоны как неудачные evidence. Итоговые DB/WAL/workspace sizes и
eligible cleanup debt проверяйте отдельно от protected retention. Конечный
успешный batch не доказывает устойчивое cleanup equilibrium без arrival rate
и retention workload.

Текущий reference использует настоящий SMB loopback stand под WSL. Он не
квалифицирует физическую one-Gbit/s LAN, выделенное four-core hardware, все
форматы документов, произвольную заполненную БД или неизмеренную operator policy.
Production acceptance всё ещё требует выбранного deployment profile и текущих
release-quality checks, отдельно от load acceptance. Останавливайте architecture
redesign, когда объявленный scenario проходит согласованные end-to-end и
operational requirements; явно фиксируйте оставшиеся stack costs и недоступное
evidence.

Исторические результаты собраны в [Native capacity](../../dev/ru/native-capacity.md).
`OUT-4` resource acceptance и `TOOL-10` lifecycle harness qualification остаются
открытыми в [known issues](../../KNOWN-ISSUES.md). В частности, текущий lifecycle
smoke/load может завершиться timeout при ожидании архивного суффикса
`.html.sealed`, до последующих assertions. Не объявляйте эти assertions
пройденными и не считайте timeout измеренным capacity limit.

## Источники и сопровождение

При изменении профиля сверяйте [production YAML](../../../packaging/templates/application.yml),
[daemon unit](../../../packaging/templates/ioc-extractor.service),
[JVM environment template](../../../packaging/templates/ioc-extractor.env) и
[CLI launcher](../../../packaging/templates/ioc).
Обновляйте обе версии при изменении defaults, admission, telemetry или
validation/deployment procedures. Новые capacity claims требуют
идентифицированного workload/target evidence; documentation examples и
deterministic tests не квалифицируют новый load profile.
