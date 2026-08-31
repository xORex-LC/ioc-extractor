# Развёртывание, обновление и rollback

Гайд описывает установку и жизненный цикл daemon ioc-extractor. Проверенные
installer baselines — Debian 11 и 12. Другие systemd-дистрибутивы работают в
режиме best effort и требуют операторской проверки.

## Выбор способа

| Способ | Назначение | Гарантии |
|---|---|---|
| `packaging/install.sh` | Новый host/prefix или контролируемое обновление внутри marked layout, введённого в 0.2.0 | Host provisioning, verified JDK, safe marked layout, immutable activation, сохранение config и local storage health gate |
| `packaging/deploy-local.sh` | Повторные deploy 0.2.0+ из локального checkout на Debian/WSL test host | Clean `verify`, build identity, DB backup, atomic activation, health gate и automatic rollback |

Если поздний шаг установки завершится ошибкой, `install.sh` возвращает предыдущие
release/unit и перезапускает ранее активный service, но не создаёт и не
восстанавливает DB backup. Перед production upgrade сделайте и проверьте backup,
сохраните предыдущий release directory.

## Требования

- root или `sudo` для host activation;
- systemd;
- обычный bootable jar ioc-extractor;
- SHA-256 checksum для release installation;
- JDK 21 либо возможность установить Temurin archive;
- local storage для SQLite DB и ingestion directories.

Не устанавливайте приложение поверх source checkout. Рекомендуемый production
prefix — `/opt/ioc-extractor`, default local deployment — `/srv/ioc-extractor`.
Prefix должен быть нормализованной выделенной директорией: системные корни,
защищённые системные деревья, symlink traversal и непустые посторонние директории
отклоняются. Daemon account не может иметь UID 0.

## Новая установка

Соберите и проверьте проект от обычного пользователя:

```bash
./mvnw -B -ntp -T 1C clean verify
APP_VERSION="$(./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout)"
APP_JAR="bootstrap/ioc-app/target/ioc-app-${APP_VERSION}.jar"
sha256sum "${APP_JAR}" > "${APP_JAR}.sha256"
```

Установите с явным jar и checksum:

```bash
sudo packaging/install.sh \
  --prefix /opt/ioc-extractor \
  --jar "${APP_JAR}" \
  --checksum "${APP_JAR}.sha256"
```

Installer создаёт account `ioc`, устанавливает pinned Temurin 21 (если не выбран
`--system-java`), сверяет archive с pinned SHA-256 до staged extraction,
размещает immutable release, launcher и systemd unit, затем требует `UP` от local
storage health components.

Fresh production configuration включает canonical record validity с TTL `12h`.
Перед изменением policy прочитайте
[гайд по lifecycle canonical-записей](canonical-record-lifecycle.md). При
upgrade этот fresh preset не объединяется с существующей operator configuration
автоматически.

Для offline host перенесите доверенный Temurin 21 tarball:

```bash
sudo packaging/install.sh \
  --jar /tmp/ioc-extractor.jar \
  --checksum /tmp/ioc-extractor.jar.sha256 \
  --jdk-tarball /tmp/temurin-21.tar.gz \
  --jdk-sha256 <trusted-archive-sha256>
```

Custom `--jdk-url` также требует `--jdk-sha256` и HTTPS. Default URL и digest для
каждой поддержанной архитектуры зафиксированы в release script и не следуют за
изменяемым endpoint `latest`.

`--no-start` позволяет проверить config перед первым запуском. Все options:
`packaging/install.sh --help`.

## Installed layout

```text
<prefix>/
├── current -> releases/<release-id>
├── releases/<release-id>/ioc-app.jar
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
`dataframe/`, а не внутри release directory. Root-owned installation marker
связывает точный prefix, service name и service user; не редактируйте и не
копируйте его в другую директорию.

Все managed-import каталоги являются service-owned state с режимом `0750`. Не
помещайте операторскую drop location внутрь `processing`, `snapshots`, `staging`,
`terminal` или `quarantine`; producer boundary — только настроенный `inbox` либо
выделенный SMB path.

## Конфигурация и проверка

Подготовьте отдельный YAML candidate по
[справочнику конфигурации](configuration.md). Секреты помещайте в
`<prefix>/etc/ioc-extractor.env`. Не редактируйте live-файл на месте: проверка и
замена должны быть одной контролируемой операцией.

```bash
sudo /opt/ioc-extractor/bin/ioc-config check ./application.candidate.yml
sudo /opt/ioc-extractor/bin/ioc-config apply ./application.candidate.yml
sudo systemctl status ioc-extractor --no-pager
sudo /opt/ioc-extractor/bin/ioc --version
sudo /opt/ioc-extractor/bin/ioc health
sudo journalctl -u ioc-extractor -n 100 --no-pager
```

Helper создаёт service-readable staged copy и проверяет YAML syntax, unknown
keys, typed binding, conversion, semantic invariants и registry references в
отдельном configuration-only Spring context. Проверка получает установленный
service environment и JVM overrides, но не открывает DB, не инициализирует
transport и не собирает runtime graph. После этого `apply` атомарно заменяет
установленный YAML и ожидает health; при startup failure предыдущий файл
восстанавливается. При прямом restart ту же проверку выполняет systemd
`ExecCondition`: exit `78` пропускает activation без детерминированного restart
loop.

Health endpoint по умолчанию доступен только на loopback. Healthy local service
не означает, что optional SMB endpoints уже прошли authentication: sync
components могут оставаться `UNKNOWN` до первой операции.

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

## Upgrade внутри marked layout через `install.sh`

1. Проверьте новый jar/checksum на trusted build host.
2. Остановите подачу inputs или откройте maintenance window.
3. Остановите сервис и скопируйте `var/db`.
4. Запустите installer с новым immutable `--release-id`.
5. Согласуйте появившиеся `*.new` config files.
6. Перезапустите и проверьте version, health, logs и representative operation.

```bash
sudo systemctl stop ioc-extractor
sudo install -d -m 0750 /opt/ioc-extractor/backups/manual-before-upgrade
sudo cp -a /opt/ioc-extractor/var/db \
  /opt/ioc-extractor/backups/manual-before-upgrade/

sudo packaging/install.sh \
  --prefix /opt/ioc-extractor \
  --jar /tmp/ioc-extractor-new.jar \
  --checksum /tmp/ioc-extractor-new.jar.sha256 \
  --release-id v0.3.0
```

Для нестандартного actuator port передайте также `--server-port PORT`. Installer
рендерит его как command-line override `--server.port` и проверяет health на том
же порту. Timing health gate настраивается через `--health-attempts` и
`--health-interval`.

Существующий operator file не перезаписывается. Если packaged template изменён,
installer создаёт `application.yml.new` или `ioc-extractor.env.new`. Сравните
файлы, перенесите новые supported properties, сохранив site-specific paths,
policies и secrets, и удалите `.new` после успешной проверки.

Если старый несогласованный `.new` отличается от нового packaged template,
deployment останавливается с `PACKAGING.CONFIG_CANDIDATE_CONFLICT`, не
перезаписывая candidate. Отчёт показывает пути всех трёх файлов, timestamps и
SHA-256, а также готовые команды `diff` и archive, но не выводит содержимое.
Запускайте предложенный diff только в trusted terminal: сам diff может раскрыть
secrets. Согласуйте старый candidate, затем архивируйте или удалите его и
повторите deployment.

При upgrade на TTL-capable binary оставьте lifecycle mode disabled для первого
compatibility startup. Последующий cutover на fixed validity destructive для
legacy active membership и выполняется по отдельной
[процедуре canonical lifecycle](canonical-record-lifecycle.md#обновление-существующей-установки).
Та же additive dataframe migration устанавливает export-slot registry. Первый
active export seed-ит текущие внешние IDs без перенумерации survivors; rollback
поэтому по-прежнему требует matching binary/configuration и backup обеих БД, а
не частичного schema downgrade.

```bash
sudo diff -u /opt/ioc-extractor/etc/application.yml \
  /opt/ioc-extractor/etc/application.yml.new
```

Не применяйте `--force` только ради устранения `.new`: он перезаписывает operator
file packaged template.

## Deployment из local checkout

От обычного пользователя:

```bash
./packaging/deploy-local.sh --prefix /srv/ioc-extractor
```

Скрипт отклоняет dirty checkout без явного `--allow-dirty`, выполняет полный
Maven gate, проверяет, что build не изменил checkout, создаёт release с
commit/build time и проверяет effective installed config новым jar от имени
service account. При config failure работающий service и SQLite DB остаются
нетронутыми. Только после этого скрипт копирует обе DB и matching systemd unit,
атомарно переключает `current`, запускает сервис и выполняет local health gate.
При ошибке восстанавливает прежний symlink, unit и DB backup. `--port PORT`
становится high-precedence значением `--server.port` daemon. Это путь для test
stand, а не замена reviewed production release process. Он bootstrap'ит чистый
prefix и обновляет только текущий marked release layout; это не команда миграции
с 0.1.0.

Rollback намеренно ограничен application symlink и двумя SQLite DB. Он не может
отменить input files, уже перемещённые успевшим запуститься новым daemon,
сгенерированные CSV/export files или завершённые remote writes. Перед
rollback-sensitive migration остановите подачу input и optional synchronization.

## Ручной application rollback

Если upgrade через `install.sh` неуспешен, а schema compatibility известна:

1. остановите сервис;
2. направьте временный symlink на предыдущий immutable release;
3. атомарно замените `current`;
4. восстановите matching DB backup, если failed release мог записать данные;
5. запустите и проверьте сервис.

```bash
sudo systemctl stop ioc-extractor
cd /opt/ioc-extractor
sudo ln -s releases/<previous-release-id> .current.rollback
sudo mv -Tf .current.rollback current
sudo systemctl start ioc-extractor
sudo ./bin/ioc health
```

Не объединяйте старый application release со случайной новой database.
Application, configuration и DB backup образуют одну rollback point.

## Восстановление DB

1. остановите сервис;
2. сохраните failed `var/db` для расследования;
3. скопируйте полный backup DB directory вместе с sidecars;
4. восстановите ownership и restrictive permissions;
5. запустите сервис, проверьте health/logs, затем верните input.

```bash
sudo systemctl stop ioc-extractor
sudo mv /opt/ioc-extractor/var/db /opt/ioc-extractor/var/db.failed
sudo cp -a /opt/ioc-extractor/backups/<backup-id>/db /opt/ioc-extractor/var/db
sudo chown -R ioc:ioc /opt/ioc-extractor/var/db
sudo systemctl start ioc-extractor
```

## Uninstall

Безопасный default удаляет service, но сохраняет account и все данные:

```bash
sudo packaging/uninstall.sh --prefix /opt/ioc-extractor
```

`--purge` необратимо удаляет prefix, config, databases, artifacts, JDK и service
account. Он требует valid installation marker и отклоняет UID 0. Pre-marker
**0.2 release layout** должен быть сначала принят одним запуском текущего
installer. Single-directory установка 0.1.0 намеренно не принимается: сохраните
её для rollback или используйте matching uninstaller. Перед purge создайте
внешний backup и проверьте точный prefix.

## Post-deployment checklist

- `ioc --version` совпадает с нужным release/commit.
- `systemctl is-active ioc-extractor` возвращает `active`.
- В `ioc health` нет необъяснённого `DOWN`.
- Startup logs не содержат `CONFIG.*` или неожиданного override.
- YAML/environment имеют restrictive ownership.
- Backup/restore paths не входят в retention targets.
- Inbox/processing/done/failed различаются и имеют минимальные права.
- Remote sync проходит checklist из [гайда remote storage](remote-storage-sync.md).
