# Эксплуатация daemon

[English version](../daemon-operations.md)

Runbook описывает повседневную эксплуатацию установленного daemon ioc-extractor:
подачу документов, health, разбор неудачных/заблокированных заданий и сохранение
состояния для восстановления. Внутренний механизм описан в
[ingestion](../../dev/ru/ingestion.md), установка и обновление — в
[deployment](deployment.md). Для CSV inbox действует отдельное
[руководство import](dataframe-import.md).

## Управление сервисом

```bash
sudo systemctl start ioc-extractor
sudo systemctl stop ioc-extractor
sudo systemctl restart ioc-extractor
sudo systemctl status ioc-extractor --no-pager
sudo journalctl -u ioc-extractor -f
```

Для команд приложения используйте установленный launcher:

```bash
sudo /opt/ioc-extractor/bin/ioc --version
sudo /opt/ioc-extractor/bin/ioc health
sudo /opt/ioc-extractor/bin/ioc health --json
```

Примеры используют prefix установщика `/opt/ioc-extractor` и пользователя `ioc`.
Подставьте выбранные prefix/учётную запись; у `deploy-local.sh` значение по
умолчанию — `/srv/ioc-extractor`. Launcher требует root и запускает команду от
служебного пользователя с настроенным окружением. `health` опрашивает работающий
daemon; он не запускает второй daemon и не восстанавливает его очередь. Не
запускайте второй daemon на том же пространстве inbox/БД.

### Безопасный плановый перезапуск

Startup recovery завершается до открытия обычного intake. Обычный restart умеет
восстанавливать захваченную работу, но не гарантирует свободную от заданий точку
для смены конфигурации или backup. Для такой точки:

1. Приостановите локальных и удалённых производителей, включая запланированные
   поступления в import inbox, если они включены. Учтите уже начатые remote fetch:
   остановка записи upstream сама по себе не выключает расписание fetch daemon.
2. Дождитесь завершения принятой работы. Проверьте `var/inbox`, `var/processing`,
   временные файлы, сохранённые pending/blocked admissions, import и publication
   backlog. Пустые каталоги сами по себе не доказывают завершение всей работы.
3. Проверьте локальный health и свежие `INGEST.*`. Заблокированный старейший допуск
   требует разбора; restart не очищает очередь.
4. Остановите сервис и убедитесь, что он остановлен, прежде чем менять recovery
   state или делать файловую резервную копию. Для обычного restart без backup или
   изменений используйте команду выше.
5. Запустите сервис, подтвердите recovery и локальную readiness, затем возобновите
   производителей.

Unit допускает 60 секунд на остановку; ожидание рабочих потоков dispatcher тоже
ограничено. Timeout или принудительное завершение не означают чистый drain.
Сохраните recovery files и проверьте следующий startup. Если свободное окно
невозможно, используйте согласованную процедуру обслуживания, не удаляя ledger.

## Подача исходного документа

Daemon обрабатывает целые файлы, а не дописываемый поток байтов:

```text
inbox -> stability wait -> durable order -> atomic claim -> private sealed source
      -> bounded preparation -> ordered promotion -> canonical commit
      -> CSV projection -> done
verified source rejection -> failed
pre-hash retry exhaustion -> blocked admission, owned token retained
pre-reservation failure -> may remain in inbox (ING-13)
```

По умолчанию принимаются `.htm`, `.html`, `.docx`, а `.tmp`, `.part` и скрытые
имена исключены. При смене конфигурации проверьте `ioc.ingestion.patterns`.
Используйте уникальное имя поступления и исключённый временный суффикс, затем
переименуйте в пределах ФС inbox после закрытия записи производителем:

```bash
sudo -u ioc cp report.docx /opt/ioc-extractor/var/inbox/report.docx.part
sudo -u ioc mv /opt/ioc-extractor/var/inbox/report.docx.part \
  /opt/ioc-extractor/var/inbox/report.docx
```

Целевое имя должно быть свободно; не перезаписывайте прежнее поступление и не
используйте одно временное имя для двух производителей. Quiet period по умолчанию
составляет 10 секунд, интервал полного обхода — 30 секунд. Это наблюдения, а не
протокол завершения записи: приостановленная копия под конечным именем может
слишком рано выглядеть стабильной.

На сетевых/ненадёжных ФС сохраняйте полный опрос
(`ioc.ingestion.detect.use-watch-service=false`). Опциональный WatchService не
имеет периодического полного обхода; потеря события может оставить файл
необнаруженным. Сканирование durable journal восстанавливает только уже
допущенную работу.

Не пишите напрямую в `processing`, `done`, `failed` и не меняйте уже захваченный
изолированный файл. Архивные имена содержат ownership/hash metadata и могут
заканчиваться на `.sealed`; не полагайтесь на прежний суффикс. Не редактируйте
`dataframe/`: это проекции канонического хранилища.

## Интерпретация результата

Архив в `var/done` означает завершённый lifecycle исходного файла. Новые canonical
rows получают непереиспользуемую внутреннюю identity; при той же row identity
сохраняется существующая строка, а принятые наблюдения могут обновлять её
lifecycle/provenance по политике. Другой документ не сбрасывает набор данных.
В immutable export `id` — отдельный переиспользуемый slot; см.
[руководство lifecycle](canonical-record-lifecycle.md).

При `collect-and-continue` корректные строки могут фиксироваться при наличии
диагностики отдельных элементов. Поэтому `COMPLETED_WITH_ERRORS` требует разбора,
даже если файл попал в `done`. Запись документа фиксируется по артефактам: сбой
позднего артефакта/проекции не откатывает прежние canonical writes. Health не
является отчётом о commit конкретного источника. Ограничение partial-run resume
отслеживается как `ING-11` в [известных проблемах](../../KNOWN-ISSUES.md).

| Ситуация | Где смотреть / действие |
|---|---|
| Проверенный источник отклонён после ограниченных попыток | `var/failed`, sidecar `.error` и диагностика; сохраните источник для расследования |
| Достоверного ключа содержимого ещё нет, попытки исчерпаны | Owned token/admission может остаться в `processing`; `dataProcessingCapacity` имеет `DOWN`, следующие promotion ждут за заблокированным первым заданием |
| Слишком большой незахваченный файл или насыщение count/byte admission | Файл остаётся в `inbox`; полный опрос повторяет допуск после изменения давления |
| Сбой до reservation | Может остаться в `inbox` (`ING-13`); сохраните файл и логи |
| Прерванные claim, promotion или projection | Сохраните journals и owned files для startup recovery |

Поддерживаемой команды clear/requeue очереди документов нет. Возврат терминального
источника в inbox или удаление ledger/SQLite records не являются поддерживаемым
повтором; идентичный контент может остаться терминально дедуплицированным.
Исправьте причину и используйте согласованное recovery или coordinated restore.
Не используйте команду managed-import `replay` для документов.

## Проверки health

`ioc health` опрашивает actuator и выводит таблицу; `--json` возвращает тело
ответа. Exit code относится к запрошенному aggregate или выбранному компоненту:

| Exit | Значение |
|---|---|
| `0` | Разобранный `status` в точности равен `UP` |
| `1` | Любой другой разобранный статус, включая `DEGRADED`, `UNKNOWN`, `DOWN` |
| `2` | Endpoint недоступен, запрос не выполнен или ответ не удалось разобрать |

```bash
sudo /opt/ioc-extractor/bin/ioc health --component ingestionLifecycle
sudo /opt/ioc-extractor/bin/ioc health --component dataProcessingCapacity --json
sudo /opt/ioc-extractor/bin/ioc health --component sync
curl --noproxy '*' --silent --show-error http://127.0.0.1:8081/actuator/health
```

При другом настроенном порте actuator подставьте его. Команда curl сохраняет
тела ошибочных ответов для разбора; её exit code сам по себе не проверяет health
JSON. `ingestionLifecycle` имеет `UP` только после recovery и запуска intake.
`dataProcessingCapacity` имеет `DOWN`, когда dispatch остановлен или допуск
заблокирован; details показывают возраст/байты очереди, фазы и давление
writer/workspace. `UP` не подтверждает бюджет ресурсов или успех каждого источника.

Опциональный sync может иметь `UNKNOWN` до первого наблюдения и `DEGRADED` при
recovery/backlog. Проверяйте компоненты отдельно: aggregate status не является
доказательством локальной readiness. Deployment gate проверяет локальные
зависимости отдельно от доступности удалённых endpoint. При включённом managed
import чтение status/health может упасть на pending head без retry deadline
(`OPS-10` в [известных проблемах](../../KNOWN-ISSUES.md)); это сбой наблюдения,
а не доказательство отката или исчезновения поступления.

## Логи и диагностика

С поставляемым профилем `daemon` структурированный ECS JSON записывается в
`var/logs/ioc-extractor.ecs.json` и ротируется там же. Console output доступен
через systemd journal; он не обязан иметь тот же JSON-формат.

```bash
sudo journalctl -u ioc-extractor --since "30 minutes ago" --no-pager
```

Сопоставляйте имя файла, observation/source identity, run id и время; приватный
token не обязан сохранять точное исходное имя. Используйте коды (`CONFIG.*`,
`SOURCE.*`, `SINK.*`, `INGEST.*`, `EXPORT.*`, `SYNC.*`) и application severity.
Один лишь journal priority filter может пропустить Java warnings в console stream.
INFO/WARN намеренно не содержат raw IOC и credentials. Включайте per-item TRACE
только на контролируемое диагностическое окно.

При startup failure прочитайте все действия `CONFIG.*`, проверьте value-free
отчёт выигравших ключей и сопоставьте YAML с
[руководством конфигурации](configuration.md). Исправьте каждый invalid/unknown
key до рестарта. Сбой ingestion recovery держит intake закрытым; не обходите
барьер ради возобновления опроса.

## Разбор неудачного или заблокированного источника

1. Приостановите автоматическую повторную подачу. Запишите имя, время, build
   version, доступные observation/run identity и diagnostic codes.
2. Сохраните источник там, где он остался (`failed`, `inbox` или owned token в
   `processing`), sidecars и journals; это потенциально чувствительные данные.
3. Проверьте формат/содержимое, права, свободный диск, storage и лимиты ресурсов.
   Для заблокированного pre-hash head сохраните исходный порядок допуска.
4. Изучите run/receipt и projection evidence до вывода о совершённых commit.
   Одного failed source или здорового aggregate недостаточно.
5. Исправьте причину и следуйте согласованной процедуре без удаления durable state.

Обычный startup восстанавливает распознанные claims и незавершённые committed
runs. Нераспознанные orphan files могут быть изолированы как ошибки; не
переименовывайте их вручную в успешные. Recovery сохраняет полномочия и порядок,
а не придумывает новый ранг для незавершённой legacy-работы.

## Очередь и ёмкость

Отслеживайте pending count, source bytes, oldest age и blocked count в
`dataProcessingCapacity`, а также возраст inbox, свободный диск, размер DB/WAL,
давление workspace и export/publish lag. Счётчики фаз/завершений дополняют осмотр
каталогов; часть из них принадлежит процессу и сбрасывается при restart.

`ioc.ingestion.concurrency` должен оставаться `1`. Это последовательное обнаружение
и promotion, тогда как `ioc.ingestion.execution.preparation-workers` допускает
до двух параллельных подготовок. Окно по умолчанию — четыре, очередь — 64 документа,
4 GiB ожидающих исходных файлов и 512 MiB на файл. Это отдельные бюджеты от
memory/disk workspace, а не обещание предела памяти процесса целиком. Увеличение
лимита требует измерений ёмкости, не только успешного startup. До настройки очереди
исследуйте blocked head, медленный storage/parser, копирование и remote outage.

## Retention

Согласуйте политики: у них разные владельцы.

- `ioc.maintenance.retention` очищает настроенные leaf directories, например
  `done` и `failed`; в поставляемых defaults выключен до согласования окон
  расследования и backup.
- `ioc.export.retention` очищает старые незакреплённые immutable slices; ожидающая
  доставка может их закреплять. См. [remote storage](remote-storage-sync.md).
- Очистка lifecycle history/receipts и managed-import terminal имеет отдельные
  обязательства восстановления; см. [lifecycle](canonical-record-lifecycle.md)
  и [import](dataframe-import.md). Ротация логов тоже независима.

Не применяйте leaf-file retention к DB/export roots, inbox, processing, admission
journals или preparation workspaces. Не удаляйте закреплённые файлы ради свободного
места; восстановите ёмкость или используйте процедуру очистки владельца.

## Резервное копирование и восстановление

Согласованный backup остановленного сервиса включает обе БД — canonical и service,
файловые journals (по умолчанию `var/ledger`), owned sources/admissions и все
referenced workspace/snapshot/export files, а также соответствующие binary и
configuration. Учтите все настроенные пути, включая внешние относительно prefix.
Копия одних БД может оставить восстанавливаемые claims без источника или receipts.

Не копируйте один main file работающей SQLite и не отбрасывайте WAL. Перед
файловым snapshot остановите всех writers и сохраните database sidecars в
согласованном комплекте; для online backup нужна отдельно квалифицированная
процедура SQLite. Mutable CSV projections восстанавливаются, но pinned immutable
slices и recovery inputs должны сохраниться вместе с ledgers. Не смешивайте
файлы БД и journals из разных моментов backup.

Сверяйте upgrade/rollback compatibility с [deployment guide](deployment.md);
одна копия DB directory не образует полноценную точку rollback. Проверьте
согласованный restore на отдельном host, прежде чем считать recovery проверенным.

## Регулярная проверка

- Сервис, recovery barrier и обязательные локальные компоненты готовы.
- Возраст inbox, допущенная очередь и blocked count проверены.
- Failed files и partial outcomes имеют решение по дальнейшим действиям.
- Диск покрывает databases/WAL, workspaces, slices, логи и backup.
- Retention сохраняет recovery pins и evidence для расследования.
- После deployment записана идентичность build/configuration.
- Опциональные sync и включённый import проверены по собственным руководствам.
