# 0011 — Синхронизация с внешними хранилищами (delivery/SMB)

## Статус

**Проектирование, не реализовано.** Ветка `feature/delivery/smb`. Документ
фиксирует согласованные решения и открытые вопросы до начала реализации; код,
основные доки (`architecture.md`, новый `sync.md`/`ingestion.md`) и конфиг
обновляются по факту. Номер бэклога ING — TBD.

## Контекст

Нужна двунаправленная синхронизация с внешними хранилищами:

1. **Вход:** забрать файлы с IOC с удалённого хранилища и положить в `var/inbox`;
   дальше приложение отрабатывает штатно (существующий демон-инжест).
2. **Выход:** после прогона по конвейеру и формирования датафреймов — отправить
   их на удалённое хранилище. Хост/путь/транспорт на выходе **независимы** от
   входа.

Принципы (от постановки):

- **Сервис синхронизации не зависит от транспорта.** Транспорт внешний, его цель —
  доставить файлы; ему не нужно знать, какой он. Вся специфика транспорта — в
  стороне. Сейчас транспорт один (SMB), в будущем возможны другие.
- **Параметры подключения и пр. — через конфиг** (`application.yml`/`IocProperties`),
  не хардкод.

Как ложится на текущую систему: вход переиспользует уже готовый стриминговый
демон-инжест ([0001](0001-streaming-ingestion.md)) — его работа заканчивается,
когда файл лёг в `var/inbox`. Выход увозит **сформированные** артефакты; *как*
они формируются над непрерывным потоком (окна/триггеры/output-mode, immutable-
снимки) — отдельное cross-cutting направление в
[0012](0012-streaming-dataframe-emission.md). Доставка к этому **агностична**:
шлёт то, что сформировано (полный снимок или инкремент).

## Решения

**1. Корень конфига — `ioc.sync`** (двунаправленно: `fetch` + `publish`) с общими
именованными `endpoints` (эндпоинт = транспорт + параметры подключения + базовый
путь). Имя честнее «delivery» (фича двунаправленная). Вход и выход ссылаются на
эндпоинты по имени, поэтому хост/транспорт у них независимы.

**2. Транспорт-агностичность = порт.** SPI транспорта живёт в `application`
(`port/out/sync`: `FileTransport`/`RemoteSession`), вся специфика SMB — в
адаптере-модуле `adapters/adapter-transport-smb` на **smbj** (чистый Java SMB2/3,
без SMB1). Правило «один внешний lib = один модуль»: новый транспорт (SFTP/S3/…)
= новый `adapter-transport-*` за тем же портом, **без правок use-case** (OCP).
Типы smbj **не утекают в `core`** (закрепить ArchUnit). Выбор транспорта по id
(`transport: smb`) через реестр в bootstrap — как `ioc.engine` → `PatternEngine`.

**3. Два use-case в `application/sync`** (чистые, без Spring):
- `RemoteFetchUseCase` — вход: листинг источника → скачивание в **существующий**
  `ioc.ingestion.dirs.inbox`. Пайплайн **не дублируется**; работа fetch
  заканчивается на «файл атомарно лёг в inbox».
- `ArtifactPublishUseCase` — выход: заливка готовых датафреймов на приёмник.

**4. Вход: идемпотентность без записи в источник.** Источник трогаем как
read-only; дубли отсекает локальный **fetch-ledger** (ключ remote `path+size+mtime`),
по образцу `FileIngestionLedger`. Отклонено для v1: «claim»-семантика
move/delete на источнике (требует прав на запись/удаление; оставлено как опция
`after:` на будущее).

**5. Выход: публикуем сформированный Export Slice, а не сырые файлы.** Источник для
publish — **storage-порт** (`CanonicalArtifactRepository`) / готовый срез экспорта
([0012](0012-streaming-dataframe-emission.md)), а **не** глоб `dataframe/*_generated.csv`:
эволюция хранилища доставку не ломает. Доставке всё равно, снимок это или инкремент.
- **Атомарная публикация среза-как-каталога:** файлы заливаются во временные имена →
  rename, **маркер `_SUCCESS` пишется последним** — удалённый потребитель забирает срез
  только целиком (закрывает torn-read на приёмнике на уровне контракта).
- **Верификация доставки:** сверка checksum/`_SUCCESS` после заливки перед
  подтверждением доставки (стык с export-сагой — решение 6).
- Имена/immutability/штамп покрытия — свойства *сформированного* среза (0012); доставка
  их сохраняет (`masks_list_2026-06-24_08-26-44Z.csv`).

**6. Триггер publish — новый срез с `_SUCCESS` / таймер; стык с export-сагой.**
Доставка реагирует на **появление нового Export Slice с маркером `_SUCCESS`**
(`on-new-output`) либо опрашивает по интервалу, а **не** решает сама, «полон ли набор» —
это вопрос формирования ([0012](0012-streaming-dataframe-emission.md) §4a). Конфиг-форма:
`publish.trigger: on-new-output | interval | both`. **Стык с export-сагой:** publish —
шаг доставки, связанный с export-прогоном по общему `run_id`/watermark (замыкает
писать→доставить end-to-end); форма ledger'а доставки (фаза `PUBLISHED` в `export_run`
vs отдельный publish-run) — деталь, решаем при проектировании саги.

**7. Объём v1 — демон-автоцикл + CLI `ioc sync`.** Авто fetch по таймеру и publish
по барьеру/таймеру в режиме `daemon`; ручная подкоманда `ioc sync` (fetch/publish
по требованию, для ops и тестов, симметрично `ioc health`). **Отклонено для v1:**
publish-после-oneshot-`extract`.

**8. Планирование/жизненный цикл — `SmartLifecycle`-бины в `bootstrap`**
(`DaemonFetchScheduler`, `DaemonPublishScheduler`) по образцу существующего
`DaemonMaintenanceScheduler`, под `@ConditionalOnProperty mode=daemon`. Сама стратегия
каденса (`interval`/`quiet-period`+max-cap) — **framework-free `CadenceSource`**
(переиспускаемый и 0012-экспортом, и под ING-7 локальную проекцию — guardrail Q2), а
Spring-обвязка остаётся в bootstrap. `TransportRegistry` (name → транспорт+коннект) —
тоже в bootstrap. Use-case остаются чистыми. (Согласует размещение с
[0012](0012-streaming-dataframe-emission.md): планировщик в bootstrap, не отдельный
`adapter-scheduler`.)

**9. Секреты — через `${ENV}`, не коммитим.** Пароли SMB — env-ссылки в
`configs/application.yml` (gitignored) или systemd env-файле из
[packaging/](../../packaging/). SMB3 + шифрование где доступно; адаптер **не
логирует** креды.

**10. Наблюдаемость.** Отдельная категория диагностических кодов для sync
(`platform-diagnostics`) + ECS-события; статусы последнего fetch/publish (время,
счётчики, ошибки) — в `ioc health`. `--dry-run` для sync (показать, что было бы
скачано/залито) — в духе `extract --dry-run`.

**11. Изоляция сбоев.** Сеть/авторизация не валят демон и не портят локальные
артефакты; retry/backoff по образцу `ioc.ingestion.retry`; упавший проход
повторяется на следующем цикле; publish **не блокирует** пайплайн.

**12. Библиотеки — smbj за тонким портом + переиспользование Spring Integration.**
- **Транспорт SMB → `smbj`** (чистый SMB2/3, без SMB1, SMB3-шифрование) за нашим
  **тонким file-ops портом** (`list/get/put-atomic/rename/delete`); типы smbj не
  утекают в `core` (ArchUnit).
- **Driving-сторона (поллинг/планировщик) → Spring Integration / Spring scheduling,
  которые уже в проекте** (`spring-integration-file` в `adapter-ingest`) — не новая
  зависимость; fetch-поллинг по образцу существующего inbox-poll.
- **Apache Camel — отклонён:** EIP-фреймворк-центр-тяжести (роуты вместо use-cases),
  оверкилл для тонкого среза доставки (та же логика, что отказ от Spark в 0012).
- **Apache Commons VFS — на радар, не сейчас:** единый file-API дублирует наш
  `FileTransport`-порт, а его SMB-провайдер на jcifs слабее по SMB2/3, чем smbj
  (широта ценой качества SMB). Кандидат в будущий `adapter-transport-vfs` за тем же
  портом, если мульти-протокольность когда-нибудь перевесит. Смена транспорта у нас и
  так решена архитектурой (новый `adapter-transport-*` за портом, OCP).
- **EIP-паттерны заимствуем концептуально** (Polling Consumer, Idempotent Receiver =
  fetch-ledger, atomic publication = staging+`_SUCCESS`), без принятия тяжёлого
  EIP-фреймворка — как Dataflow-модель без Spark в 0012.

### Эскиз конфига

```yaml
ioc:
  sync:
    enabled: false
    endpoints:
      - { name: intel-share, transport: smb,
          smb: { host: 10.0.0.5, share: intel, domain: CORP,
                 username: ${SMB_USER}, password: ${SMB_PASS}, encrypt: true } }
      - { name: dist-share,  transport: smb,
          smb: { host: 10.0.0.9, share: dist, username: ${SMB_USER2}, password: ${SMB_PASS2} } }
    fetch:                              # вход: remote -> var/inbox
      enabled: true
      interval: 1m
      sources:
        - { endpoint: intel-share, remote-path: "/incoming",
            include: [ "*.htm", "*.docx" ] }   # источник read-only, дедуп через ledger
    publish:                            # выход: сформированные артефакты -> remote
      enabled: true
      trigger: on-new-output            # on-new-output | interval | both
      interval: 5m                      # output-mode/окно/маркер — см. 0012
      targets:
        - { endpoint: dist-share, remote-path: "/reputation-lists",
            artifacts: [ masks, ip_list, hashes ] }
```

## Следствия

Планируемые (кода ещё нет):

- Новый модуль `adapters/adapter-transport-smb` (smbj в parent `dependencyManagement`).
- Новые пакеты: `application/sync` (use-case), `port/in/sync`, `port/out/sync`
  (`FileTransport`, `RemoteSession`, `RemoteFetchLedger`).
- `bootstrap`: `DaemonFetchScheduler`, `DaemonPublishScheduler` (`SmartLifecycle`, по
  образцу `DaemonMaintenanceScheduler`), `TransportRegistry`, ветка `ioc.sync` в
  `IocProperties`.
- Чистый `CadenceSource` (`interval`/`quiet-period`+max-cap) — переиспускается
  0012-экспортом и ING-7 (guardrail Q2).
- **Ретеншен на приёмнике** (старые срезы на remote) — открытый под-вопрос: наш publish
  с правами delete vs забота получателя (перекликается с отклонённой claim-семантикой
  fetch); локальный ретеншен — reaper ING-1 (0012 Q5).
- `adapter-cli-picocli`: подкоманда `ioc sync`.
- `application.yml`: блок `ioc.sync`; ArchUnit-правило «smbj не виден из `core`».
- Доки: пользовательский `docs/sync.md` (или раздел в `ingestion.md`), строка в
  индексе `docs/dev/README.md`.

## Открытые вопросы

1. **Где проходит граница транспорт-агностичности?** **Склоняемся к тонкому file-ops
   порту** (`list/get/put-atomic/rename/delete` над именованным эндпоинтом +
   относительный путь; сервис — оркестратор, лучше переиспускается под SFTP/S3),
   образец в репо — `PatternEngine`/`SourceReader`. **Ещё не решено:** жизненный цикл
   сессии/соединения, переподключения, и главное — откуда резолвятся креды (внутри
   адаптера vs резолвер-порт). **Это и есть следующая тема проектирования.**

> Вопросы формирования датафреймов над непрерывным потоком (что значит «полный
> набор», output-mode `complete`/`append`, каденс/триггер экспорта) вынесены в
> [0012](0012-streaming-dataframe-emission.md) — это отдельное направление, к
> которому доставка агностична.
