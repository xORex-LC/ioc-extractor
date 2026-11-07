# Развёртывание, обновление и rollback

[English version](../deployment.md)

Руководство описывает установку и жизненный цикл daemon ioc-extractor.
Проверенные базовые среды установщика — Debian 11 и 12. Другие дистрибутивы
с systemd требуют проверки оператором. Здесь описаны первоначальное
развёртывание и приёмка; [эксплуатация daemon](daemon-operations.md) отвечает
за последующую работу с intake, retry, retention и резервными копиями.

## Выбор способа

| Способ | Назначение | Гарантии |
|---|---|---|
| `packaging/install.sh` | Новый хост/prefix или контролируемое обновление в marked layout, введённом в 0.2.0 | Подготовка хоста, проверенный JDK, безопасный layout, immutable activation, сохранение конфигурации и проверка локального storage |
| `packaging/deploy-local.sh` | Повторные развёртывания 0.2.0+ из checkout на Debian/WSL test host | Чистый `verify`, build identity, DB backup, atomic activation, health gate и автоматический rollback |

При ошибке позднего шага `install.sh` возвращает предыдущие release/unit и
пытается запустить ранее активный сервис, но не создаёт и не восстанавливает
DB backup. Перед production upgrade создайте и проверьте резервную копию,
сохраните предыдущий release directory. Ни один путь не объединяет операторскую
конфигурацию и не отменяет внешние эффекты автоматически. Используйте scripts
и templates выбранного релиза: установщик из произвольного checkout может
быть несовместим со старым JAR.

## Требования

| Зависимость | Требование |
|---|---|
| Хост | GNU/Linux с Bash, GNU coreutils/findutils/tar и systemd как PID 1. Native Windows/macOS и containers без systemd этими scripts не поддерживаются. В WSL нужно включить systemd. |
| Права и инструменты | Root или `sudo` для активации; `curl`, доверенные CA, `flock` (`util-linux`), account tools (`passwd`), `systemctl`, `systemd-run`, `journalctl`. Установщик проверяет команды, но не устанавливает OS packages. |
| Java | Проверенная runtime/build baseline — Java 21. Установщик принимает system Java 21+ либо проверенный archive; это не квалифицирует более новый major. Default archives предназначены для Linux x86-64/AArch64; другие CPU/libc требуют проверки. |
| Приложение | Bootable JAR приложения, доверенный SHA-256, matching packaging files и release notes. Обычные module/library JAR реактора не являются исполняемой поставкой. |
| Файловая система | Локальное хранилище с надёжными SQLite locks и atomic rename в одной файловой системе для передачи файлов. Учтите обе DB/WAL, source snapshots/workspaces, exports, backups, JDK и сохраняемые releases. Не размещайте live DB на SMB/NFS. |
| Необязательный SMB | DNS/routing и outbound TCP к настроенным SMB ports (обычно 445), существующая share, credentials и ACLs. Required encryption требует фактически зашифрованной SMB3 session. Приложение не создаёт shares/ACLs. |
| Только build host | Git, полный JDK 21, Maven wrapper и доступные repositories либо подготовленный cache. Хосту с готовым JAR не нужны Maven, Git, отдельная установка Spring/Tomcat или настройка публикации библиотек. |

SQLite встроен через sqlite-jdbc; для штатной работы не нужны DB server или
системная команда `sqlite3`. Native library нужен доступный для записи и загрузки
временный каталог. Mount с `noexec` или неподдерживаемые CPU/libc могут сорвать
старт; см. [native loader](https://github.com/xerial/sqlite-jdbc/blob/3.53.2.0/src/main/java/org/sqlite/SQLiteJDBCLoader.java).
Для поддерживаемых HTML/PDF/DOCX/XLSX не нужны установленные Office/LibreOffice;
границы форматов описаны в [processing](../../dev/ru/processing.md).

JAR приложения содержит выбранные runtime libraries, включая общие модули
проекта; startup не скачивает их из Maven repositories. Используйте полный
matching JAR и не заменяйте отдельные вложенные библиотеки ради устранения
конфликта. Перед production promotion проверьте dependency/security evidence
выбранного bundle и [известные проблемы](../../KNOWN-ISSUES.md). Открытый разбор
advisories (`SRC-3`) не закрывается успешной установкой или health.

Не устанавливайте приложение поверх source checkout. Рекомендуемый production
prefix — `/opt/ioc-extractor`, default local deployment — `/srv/ioc-extractor`.
Prefix должен быть нормализованным выделенным каталогом: системные корни,
защищённые поддеревья, symlink traversal и непустые посторонние каталоги
отвергаются. Daemon account не может иметь UID 0. Имя сервиса фиксировано:
`ioc-extractor`; второй prefix не создаёт отдельный независимый сервис.
Используйте один daemon на storage/intake namespace. Java и состояние приложения
должны быть вне `/home`, скрытого через `ProtectHome`.

## Новая установка

Для установки релиза получите выбранный JAR, checksum и соответствующие packaging
files из доверенного выпуска; прочитайте upgrade notes. Новый самостоятельно
вычисленный checksum недоверенного файла не подтверждает его происхождение.

Разработчики собирают свой candidate от обычного пользователя:

```bash
./mvnw -B -ntp -T 1C clean verify
APP_VERSION="$(./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout)"
APP_JAR="bootstrap/ioc-app/target/ioc-app-${APP_VERSION}.jar"
sha256sum "${APP_JAR}" > "${APP_JAR}.sha256"
```

Устанавливайте с явными JAR и checksum:

```bash
sudo packaging/install.sh \
  --prefix /opt/ioc-extractor \
  --jar "${APP_JAR}" \
  --checksum "${APP_JAR}.sha256" \
  --no-start
```

Установщик создаёт отдельный account `ioc`, устанавливает pinned Temurin 21,
если не выбран `--system-java`, проверяет archive по pinned SHA-256 до staged
extraction, размещает immutable release, launcher и unit. Пример оставляет
сервис остановленным для проверки; без `--no-start` он запускается сразу,
после чего проверяются три локальных storage-компонента.

Новая production configuration включает canonical validity на `12h`.
Перед изменением прочитайте [lifecycle guide](canonical-record-lifecycle.md).
При upgrade этот preset не объединяется со старой конфигурацией автоматически.

На offline host перенесите доверенный Temurin 21 tarball вместе с приложением:

```bash
sudo packaging/install.sh \
  --jar /tmp/ioc-extractor.jar \
  --checksum /tmp/ioc-extractor.jar.sha256 \
  --jdk-tarball /tmp/temurin-21.tar.gz \
  --jdk-sha256 "REPLACE_WITH_TRUSTED_SHA256" \
  --no-start
```

Custom `--jdk-url` также требует `--jdk-sha256` и HTTPS. Default URL и digest
для архитектуры закреплены в release script, а не следуют mutable latest.
`--no-start` всё равно переключает `current`, записывает unit и включает сервис:
это не dry run и не защита от запуска при следующей загрузке хоста. Ранее активный
обновляемый сервис также останется остановленным. Все options показаны через
`packaging/install.sh --help`.

## Установленная структура

```text
<prefix>/
├── current -> releases/<release-id>
├── releases/<release-id>/ioc-app.jar
├── jdk/                 # unless using system Java
├── backups/
├── bin/
│   ├── ioc
│   └── ioc-config
├── etc/application.yml
├── etc/ioc-extractor.env
├── etc/ioc-extractor.installation
├── var/db/
├── var/export/
├── var/inbox/  var/processing/  var/done/  var/failed/
├── var/import/inbox/  var/import/processing/  var/import/snapshots/
├── var/import/staging/  var/import/terminal/  var/import/quarantine/
├── var/ledger/ var/logs/
└── dataframe/
```

`releases/` неизменяем. Операторское состояние находится в `etc/`, `var/` и
`dataframe/`, а не в release directory. Root-owned marker связывает точный
prefix, service name и service user; не редактируйте его и не копируйте в другое
место. Managed-import directories — service-owned `0750` state. Не используйте
`processing`, `snapshots`, `staging`, `terminal` или `quarantine` как место
передачи файлов: producer boundary — только настроенный `inbox` или SMB path.

Unit разрешает запись только в `var/` и `dataframe/`, даёт private `/tmp` и
ограничивает ресурсы. Для внешних custom paths нужны права service account и
проверенный systemd writable-path drop-in. Видимый из shell путь может быть
скрыт или read-only в сервисе. Согласуйте heap и unit limits по
[руководству по ресурсам](bulk-resource-sizing.md).

## Конфигурация и проверка

Для миллиона входных IOC задайте host/JVM/workspace/execution envelope по
[руководству по ресурсам](bulk-resource-sizing.md): малые packaged defaults
не являются гарантией такой нагрузки.

Подготовьте отдельный YAML candidate по [справочнику](configuration.md).
Секреты помещайте в `<prefix>/etc/ioc-extractor.env`, не в YAML. Не редактируйте
live file на месте: проверка и замена должны быть контролируемой операцией.
Поверх packaged defaults действуют external YAML, environment, JVM system
properties и CLI, именно в этом порядке. Unknown `ioc.*` отвергаются на каждом
канале, list overrides заменяют весь список. `IOC_` зарезервирован для свойств
приложения. EnvironmentFile — не shell syntax: не выполняйте `source`.
Изменения secrets/JVM environment управляются оператором и не входят в rollback
YAML helper.

```bash
sudo /opt/ioc-extractor/bin/ioc-config check ./application.candidate.yml
sudo /opt/ioc-extractor/bin/ioc-config apply ./application.candidate.yml
sudo systemctl status ioc-extractor --no-pager
sudo /opt/ioc-extractor/bin/ioc --version
sudo /opt/ioc-extractor/bin/ioc health
sudo journalctl -u ioc-extractor -n 100 --no-pager
```

После новой установки с `--no-start` достаточно `check`, затем `systemctl start`,
если YAML не менялся. `apply` запускает/перезапускает даже остановленный сервис;
это операция активации.

Helper создаёт service-readable staged copy и проверяет YAML syntax, unknown keys,
typed binding, conversion, semantic invariants и registry references в
configuration-only Spring context. Используются installed environment и JVM
overrides, но не открываются DB/transports и не собирается runtime graph.
`apply` атомарно заменяет YAML и ждёт health; при неудаче возвращает прошлый
файл. При обычном restart та же проверка идёт в `ExecCondition`: exit `78`
пропускает activation без детерминированного restart loop.

Успех не подтверждает DB/schema compatibility, доступ к путям, native-library
loading или SMB authentication. YAML rollback возвращает файл, а не runtime
state, изменённое успевшим запуститься процессом. Для destructive lifecycle/schema
changes нужна согласованная точка восстановления.

Daemon читает `JAVA_OPTS`; установленный `bin/ioc` запускает отдельный oneshot
с фиксированными JVM options и не передаёт `JAVA_OPTS`. Он читает service
EnvironmentFile, но не является daemon. Не выполняйте concurrent mutating CLI
work в namespace работающего daemon. Unit-only override `--server.port` этому
CLI не передаётся: используйте `ioc health --port PORT`.

### Приёмка активации и health

| Проверка | Смысл |
|---|---|
| `install.sh` / `deploy-local.sh` | Сервис active, а `jdbcStorage`, `dataframeStorage`, `artifactStorage` по отдельности `UP`; optional remote health не является promotion gate. |
| `ioc-config apply` | Сервис active и aggregate `/actuator/health` имеет `UP`; сбой другого компонента может отклонить корректный YAML candidate. |
| `ioc health` | Опрашивает daemon: exit `0` только при `UP`, `1` при другом сообщённом status, `2` при недоступном/некорректном ответе. Не запускает и не чинит сервис. |

По умолчанию endpoint доступен только через loopback. Здоровый локальный сервис
не доказывает аутентификацию необязательного SMB: sync может иметь `UNKNOWN`
до первой операции. До production intake проверьте release/commit, реальный
port, компоненты и logs. Подайте контролируемый representative document,
проверьте terminal outcome и ожидаемые DB/projection/export результаты.
SMB и managed import квалифицируются отдельно при включении; storage health
для этого недостаточно.

## Переход с 0.1.0 на 0.2.0

Версия 0.2.0 **не поддерживает in-place upgrade** установки 0.1.0. Старый релиз
использовал single-directory layout с `lib/ioc-app-0.1.0.jar`, CSV-centric state
и configuration contract, несовместимый с текущим immutable release/SQLite
layout. `--force` не обходит эту границу, а generated CSV artifacts 0.1.0 не
являются поддерживаемым форматом импорта в canonical database 0.2.0.

Используйте filesystem-side-by-side переход. Оба prefix остаются на диске, но
одновременно работает только один экземпляр общего `ioc-extractor.service`:

1. Остановите подачу новых input и optional synchronization.
2. Остановите 0.1.0 и сделайте проверенный внешний backup полного prefix,
   configuration и systemd unit.
3. Оставьте старый prefix неизменным как rollback point.
4. Установите 0.2.0 в новый пустой prefix с `--no-start`.
5. Настройте 0.2.0 на основе поставляемого template. Переносите только
   проверенные site-specific значения; не копируйте старые YAML/environment
   files целиком.
6. Запустите 0.2.0, затем скопируйте проверенные исходные документы в новый
   inbox, чтобы построить SQLite truth. Не меняйте старые source evidence.
7. До приёма новых input проверьте version, health, logs, canonical row counts и
   generated artifacts.
8. Храните старый prefix и backup unit в течение всего rollback window.

Пример cutover с исторического default prefix:

```bash
OLD_PREFIX=/opt/ioc-extractor
NEW_PREFIX=/opt/ioc-extractor-0.2
OLD_UNIT_BACKUP=/root/ioc-extractor-v0.1.0.service

sudo systemctl stop ioc-extractor
sudo install -o root -g root -m 0600 \
  /etc/systemd/system/ioc-extractor.service "${OLD_UNIT_BACKUP}"
sudo tar -C "$(dirname "${OLD_PREFIX}")" -cpf /root/ioc-extractor-v0.1.0-prefix.tar \
  "$(basename "${OLD_PREFIX}")"

sudo packaging/install.sh \
  --prefix "${NEW_PREFIX}" \
  --jar /tmp/ioc-extractor-0.2.0.jar \
  --checksum /tmp/ioc-extractor-0.2.0.jar.sha256 \
  --release-id v0.2.0 \
  --no-start
```

Перед запуском service проверьте `${NEW_PREFIX}/etc/application.yml` и
environment file. Повторно подайте доверенные исходные документы; не
инициализируйте новую database из generated CSV projections 0.1.0.

Rollback через границу 0.1.0/0.2.0 восстанавливает неизменный prefix 0.1.0 и
сохранённый unit. Он никогда не направляет 0.1.0 на SQLite databases 0.2.0:

```bash
sudo systemctl stop ioc-extractor
sudo install -o root -g root -m 0644 "${OLD_UNIT_BACKUP}" \
  /etc/systemd/system/ioc-extractor.service
sudo systemctl daemon-reload
sudo systemctl start ioc-extractor
```

Input, принятые только после cutover на 0.2.0, отсутствуют в старом prefix.
Сохраните их и явно согласуйте/повторно подайте при rollback.

## Обновление внутри marked layout через `install.sh`

Допустимый layout не доказывает поддержку upgrade. Определите installed
binary/commit, YAML/environment, обе DB, lifecycle mode и artifact identities;
сверьте upgrade/rollback contract выбранного релиза. Startup может мигрировать
обе схемы ещё до intake. Общего schema downgrade нет.

1. Проверьте новый JAR и checksum на доверенном build host.
2. Приостановите producers, optional sync и mutating CLI; остановите сервис.
3. Создайте внешний защищённый backup prefix и unit: обе DB с sidecars,
   config/secrets, private intake files, exports/projections, helpers и Java/runtime
   selection образуют одну точку восстановления.
4. Запустите matching `install.sh` с новым immutable `--release-id` и `--no-start`.
5. Согласуйте `*.new` с release notes; проверьте candidate новым binary и
   installed environment до runtime startup.
6. Запустите, проверьте migrations/health/logs и representative operation до
   возобновления input. One-way feature activation выполняйте отдельно.

```bash
sudo systemctl stop ioc-extractor
CHANGE_ID="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="/root/ioc-extractor-${CHANGE_ID}"
sudo install -d -o root -g root -m 0700 "${BACKUP_DIR}"
sudo tar --acls --xattrs -C /opt -cpf "${BACKUP_DIR}/prefix.tar" ioc-extractor
sudo install -o root -g root -m 0600 \
  /etc/systemd/system/ioc-extractor.service "${BACKUP_DIR}/unit.service"

sudo packaging/install.sh \
  --prefix /opt/ioc-extractor \
  --jar /tmp/ioc-extractor-new.jar \
  --checksum /tmp/ioc-extractor-new.jar.sha256 \
  --release-id v0.3.0 \
  --no-start
```

При нестандартном actuator port передайте `--server-port PORT`: это CLI override
`--server.port` daemon и тот же адрес health gate. Timing задают
`--health-attempts` и `--health-interval`. Согласуйте фактический port с
YAML/helper/probe: изменение только YAML может оставить probe на старом/default.

Существующие YAML/environment сохраняются; изменённые templates получают
`application.yml.new` или `ioc-extractor.env.new`. Переносите supported properties,
сохраняя site-specific paths/policies/secrets; удаляйте `.new` после проверки.
Если старый `.new` отличается от incoming template, операция прекращается с
`PACKAGING.CONFIG_CANDIDATE_CONFLICT`, не перезаписывая candidate. Отчёт содержит
три пути, timestamps, SHA-256 и diff/archive commands, но не содержимое. Сам diff
может раскрыть secrets: используйте trusted terminal, согласуйте и архивируйте
либо удалите старый candidate перед повтором.

При переходе на TTL-capable binary оставьте lifecycle disabled для первого
compatibility start. Destructive fixed-validity cutover выполняется по отдельной
[процедуре](canonical-record-lifecycle.md#обновление-существующей-установки).
`CONFIG.LEGACY_ARTIFACT_IDENTITY` ожидаем только для точного built-in v0.2.0 list;
это не разрешение опускать versioned fields в custom entries. Согласуйте явные
definitions после вывода v0.2.0 из rollback targets. Additive migration добавляет
export-slot registry: первый active export сохраняет внешние IDs survivors.
Rollback требует matching binary/configuration и обе DB, а не partial downgrade.

```bash
sudo diff -u /opt/ioc-extractor/etc/application.yml \
  /opt/ioc-extractor/etc/application.yml.new
```

`--force` перезаписывает operator file packaged template, а не объединяет их.
Прямой upgrade v0.2.0 принимает только четыре точных legacy built-in identity
entries. Временный SMB alias `encrypt` нельзя задавать вместе с `encryption`.
Сохраняйте совместимость keys с rollback binary до закрытия окна. Удалённый
`read-timeout` отвергается. Следуйте [configuration](configuration.md) и release
notes, не заменяйте operator YAML целиком fresh template.

## Развёртывание из checkout

От обычного пользователя:

```bash
./packaging/deploy-local.sh --prefix /srv/ioc-extractor
```

Скрипт отвергает dirty checkout без `--allow-dirty`, выполняет полный Maven gate,
проверяет неизменность дерева после build, создаёт commit/time release и новым
JAR проверяет effective installed config от service account. При отказе running
process и DB не меняются. После проверки создаёт backup обеих DB и старого unit,
переключает `current`, запускает и проверяет local health. При ошибке activation
возвращает symlink, unit и DB с POSIX ACLs/xattrs. `--port PORT` становится
high-precedence `--server.port`, а не только адресом probe.

Известное ограничение `OPS-13`: preflight rejection оставляет process/current/DB,
но unit и CLI helpers могли уже обновиться. Явный failure exit обходит error trap
восстановления unit. До следующего restart сравните unit с
`backups/<failed-release-id>-unit.service`, при необходимости восстановите matching
unit/helpers и выполните reload systemd. Отказ deployment не гарантирует
неизменности всех host files. Исправление учитывается в
[известных проблемах](../../KNOWN-ISSUES.md).

Build JVM проверяется до Maven; если default старше по возрасту и имеет major
ниже 21, явно выберите JDK 21:

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="${JAVA_HOME}/bin:${PATH}"
make doctor-core
```

Это путь для test stand, не замена reviewed production release process.
Он создаёт новый prefix или обновляет marked layout, но не мигрирует 0.1.0.
Автоматический rollback ограничен symlink, matching unit и двумя DB с ACLs/xattrs.
Он не возвращает перемещённые новым daemon inputs, CSV/exports и remote writes.
Перед чувствительной миграцией приостановите input и optional synchronization.

## Ручной application rollback

Если upgrade неуспешен и schema compatibility известна:

1. Остановите сервис.
2. Создайте временный symlink на предыдущий immutable release.
3. Атомарно замените `current`.
4. Восстановите matching DB backup, если новый binary мог писать.
5. Восстановите matching unit и перечитайте systemd.
6. Восстановите config/environment и нужные service-owned files, затем запустите
   и проверьте сервис.

Пример symlink предполагает совместимые DB и неизменённую конфигурацию; иначе
оставьте сервис остановленным до полного restore. Sidecar `*-unit.service`
создаёт local deployment. Для `install.sh` берите unit из внешнего backup:
временная recovery copy не сохраняется после завершения installer. Проверьте
также helpers и выбранную Java.

```bash
sudo systemctl stop ioc-extractor
cd /opt/ioc-extractor
sudo ln -s "releases/<previous-release-id>" .current.rollback
sudo mv -Tf .current.rollback current
sudo install -o root -g root -m 0644 \
  "backups/<failed-release-id>-unit.service" \
  /etc/systemd/system/ioc-extractor.service
sudo systemctl daemon-reload
sudo systemctl start ioc-extractor
sudo ./bin/ioc health
```

Не сочетайте старый binary со случайными новыми DB/unit. Application, unit,
configuration и DB — одна rollback point. Имя unit sidecar содержит ID failed
deployment, потому что файл хранит unit, предшествовавший этому deployment.

## Восстановление DB

1. Остановите сервис.
2. Сохраните failed `var/db` для расследования.
3. Извлеките весь DB directory с sidecars, ACLs и xattrs.
4. Проверьте matching ownership, ACLs и restrictive permissions.
5. Запустите и проверьте health/logs до возобновления input.

```bash
sudo systemctl stop ioc-extractor
sudo mv /opt/ioc-extractor/var/db /opt/ioc-extractor/var/db.failed
sudo tar --acls --xattrs -C /opt/ioc-extractor/var -xf \
  "/opt/ioc-extractor/backups/<failed-release-id>-db.tar"
sudo systemctl start ioc-extractor
```

Это имя archive относится к local deployment. В ручном upgrade создан полный
`prefix.tar`: восстанавливайте согласованное состояние и сохранённый unit из
него. Не смешивайте layouts архивов и не восстанавливайте одну DB отдельно.
Извлекайте в пустое место при остановленном сервисе, сохраняйте failed state
и проверяйте исходные ownership/ACLs вместо общей переустановки прав.

## Диагностика неудачного развёртывания

Сначала соберите read-only наблюдения, подставив реальный port:

```bash
sudo systemctl status ioc-extractor --no-pager
sudo journalctl -u ioc-extractor -n 100 --no-pager
sudo /opt/ioc-extractor/bin/ioc --version
sudo /opt/ioc-extractor/bin/ioc health --port 8081 --json
curl --noproxy '*' --silent --show-error --max-time 5 \
  http://127.0.0.1:8081/actuator/health
```

| Симптом | Проверка и действие |
|---|---|
| Unsafe prefix, mismatched marker или UID 0 | Выберите выделенный prefix вне checkout/protected trees и исходный non-root account. Не редактируйте marker и не обходите границу 0.1.0. |
| Checksum/archive/release-ID collision | Получите доверенные matching files. Release ID принимает только те же bytes. Custom JDK archive требует SHA-256; system Java и archive options несовместимы. |
| `UnsupportedClassVersionError`, missing main class или `NoSuchMethodError` | Проверьте фактическую Java сервиса и bootable JAR. Не используйте обычный module JAR и не смешивайте поколения зависимостей; верните matching bundle и runtime. |
| `PACKAGING.CONFIG_CANDIDATE_CONFLICT` | Согласуйте/архивируйте старый `.new` до повтора. Diff может содержать secrets; `--force` не выполняет merge. |
| `CONFIG.*`, exit `78`, skipped ExecCondition | Проверьте path/category, YAML/environment и system/CLI overrides, удалённые keys и полную замену lists. Выполните `ioc-config check`, исправьте candidate и активируйте явно. |
| Сервис active, health недоступен | Проверьте daemon mode, фактический port и logs. Unit CLI port override старше YAML по precedence. Для CLI задайте `--port`, для loopback curl исключите proxy. |
| Permission/read-only/path failure | Проверьте UID/group, traversal, `ReadWritePaths`, `ProtectHome` и private `/tmp`. DB/staging должны оставаться локальными и writable. Не переводите сервис на root и не расширяйте права глобально. |
| SQLite native-library load failure | Проверьте CPU/libc и temp mount restrictions. При необходимости создайте service-owned loadable directory под `var/`, выберите его JVM property `org.sqlite.tmpdir` и проверьте в реальном sandbox. |
| OOM, termination или restart limit | Сопоставьте journal/kernel/cgroup evidence и JVM/unit limits; уменьшите concurrency/input budgets либо увеличьте ресурсы. Один `reset-failed` причину не устраняет. |
| Schema/identity/lifecycle incompatibility | Прекратите дальнейшие записи; выполните migration или coordinated restore выбранного релиза. Не правьте version metadata вручную и не открывайте новые DB старым binary. |
| Storage gate прошёл, SMB нет | Отдельно проверьте server/port/credentials/ACLs и effective encryption. Local promotion не квалифицирует remote endpoint; см. [remote storage](remote-storage-sync.md). |
| Import health/status падает при pending head | Известный `OPS-10` касается nullable-deadline reader, а не доказывает failed canonical commit. Следуйте [managed import](dataframe-import.md), не исправляйте ledger SQL вручную. |
| Local deployment отклонён, unit/helpers изменены | До restart следуйте ограничению `OPS-13` выше. Сохраните failed release ID и backup evidence. |

До исправления fetch identity/landing `OPS-11`/`OPS-12` используйте непересекающиеся
remote paths/final filenames и согласованных inbox writers; см. remote storage.
При приёмке явно указывайте недоступные capabilities.

## Удаление

Безопасный default удаляет сервис, сохраняя account и всё состояние:

```bash
sudo packaging/uninstall.sh --prefix /opt/ioc-extractor
```

`--purge` необратимо удаляет prefix, config, DB, artifacts, JDK и service account.
Требует valid installation marker и отвергает UID 0. Pre-marker **0.2 release
layout** сначала принимает текущий installer. Single-directory 0.1.0 намеренно
не принимается: сохраните её для rollback или используйте matching uninstaller.
Сначала создайте внешний backup и проверьте точный prefix.

## Проверки после развёртывания

- `ioc --version` совпадает с нужным release/commit.
- `systemctl is-active ioc-extractor` возвращает `active`.
- В `ioc health` нет необъяснённого `DOWN`.
- Startup logs не содержат `CONFIG.*` failure или неожиданного override.
- `etc/application.yml` и environment file имеют restrictive ownership.
- Backup/restore locations находятся вне retention targets.
- Inbox/processing/done/failed различаются и доступны только нужным operator/service accounts.
- Включённый sync проходит checklist [remote storage](remote-storage-sync.md).

## Источники и сопровождение

Исполняемое поведение задают [packaging reference](../../../packaging/README.md),
[installer](../../../packaging/install.sh),
[local activation](../../../packaging/deploy-local-root.sh),
[unit](../../../packaging/templates/ioc-extractor.service),
[CLI launcher](../../../packaging/templates/ioc) и
[YAML helper](../../../packaging/templates/ioc-config).
Совместимость конкретной версии определяют её release notes. При изменении
flags, dependencies, validation, health или recovery обновляйте обе версии.
Выполняйте `make lint-shell`, нужные configuration/runtime tests и `make docs`
для links. Host activation/restore и optional endpoints всё равно требуют
проверки на реальном target.
