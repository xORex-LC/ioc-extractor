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

**5. Выход: сформированные артефакты, не внутренние партиции.** Публикуем то, что
эмитит формирование ([0012](0012-streaming-dataframe-emission.md)) — полный снимок
или инкремент, доставке всё равно. Источник для publish — **storage-порт**
(`CanonicalArtifactRepository`), а **не** глоб `dataframe/*.csv`: тогда переход на
полноценное хранилище доставку не ломает (она переэкспортирует из хранилища).
- Заливка **атомарная**: temp-имя на приёмнике → rename.
- Имена/immutability/маркер готовности — свойства *сформированного* артефакта (0012);
  доставка их сохраняет (имена с таймстемпом в формате проекта,
  `masks_list_2026-06-24_08-26-44Z.csv`).

**6. Триггер publish — «появился новый сформированный артефакт» / таймер.**
Доставка реагирует на emit формирования (или периодически опрашивает storage-порт),
а **не** решает сама, «полон ли набор» — это вопрос формирования ([0012](0012-streaming-dataframe-emission.md)).
Конфиг-форма: `publish.trigger: on-new-output | interval | both`.

**7. Объём v1 — демон-автоцикл + CLI `ioc sync`.** Авто fetch по таймеру и publish
по барьеру/таймеру в режиме `daemon`; ручная подкоманда `ioc sync` (fetch/publish
по требованию, для ops и тестов, симметрично `ioc health`). **Отклонено для v1:**
publish-после-oneshot-`extract`.

**8. Планирование/жизненный цикл — `SmartLifecycle`-бины в `bootstrap`**
(`DaemonFetchScheduler`, `DaemonPublishScheduler`) по образцу
`DaemonAggregationScheduler`, под `@ConditionalOnProperty mode=daemon`. `TransportRegistry`
(name → транспорт+коннект) — тоже в bootstrap. Use-case остаются чистыми.

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
- `bootstrap`: `DaemonFetchScheduler`, `DaemonPublishScheduler`, `TransportRegistry`,
  ветка `ioc.sync` в `IocProperties`.
- `adapter-cli-picocli`: подкоманда `ioc sync`.
- `application.yml`: блок `ioc.sync`; ArchUnit-правило «smbj не виден из `core`».
- Доки: пользовательский `docs/sync.md` (или раздел в `ingestion.md`), строка в
  индексе `docs/dev/README.md`.

## Открытые вопросы

1. **Где проходит граница транспорт-агностичности?** Что именно знает порт, а что
   целиком прячется в адаптер: жизненный цикл сессии/соединения, переподключения,
   и главное — откуда резолвятся креды. **Не решено — следующая тема.**

> Вопросы формирования датафреймов над непрерывным потоком (что значит «полный
> набор», output-mode `complete`/`append`, «датафрейм по партиции») вынесены в
> [0012](0012-streaming-dataframe-emission.md) — это отдельное направление, к
> которому доставка агностична.
