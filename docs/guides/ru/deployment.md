# Развёртывание, обновление и rollback

Гайд описывает установку и жизненный цикл daemon ioc-extractor. Проверенные
installer baselines — Debian 11 и 12. Другие systemd-дистрибутивы работают в
режиме best effort и требуют операторской проверки.

## Выбор способа

| Способ | Назначение | Гарантии |
|---|---|---|
| `packaging/install.sh` | Новый хост или контролируемое обновление готовым jar | Host provisioning, JDK, account, layout, immutable activation, сохранение config |
| `packaging/deploy-local.sh` | Повторные deploy из локального checkout на Debian/WSL test host | Clean `verify`, build identity, DB backup, atomic activation, health gate и automatic rollback |

`install.sh` не даёт автоматическую backup-and-health rollback transaction,
которую выполняет `deploy-local.sh`. Перед production upgrade сделайте и
проверьте backup, сохраните предыдущий release directory.

## Требования

- root или `sudo` для host activation;
- systemd;
- обычный bootable jar ioc-extractor;
- SHA-256 checksum для release installation;
- JDK 21 либо возможность установить Temurin archive;
- local storage для SQLite DB и ingestion directories.

Не устанавливайте приложение поверх source checkout. Рекомендуемый production
prefix — `/opt/ioc-extractor`, default local deployment — `/srv/ioc-extractor`.

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

Installer создаёт account `ioc`, устанавливает JDK 21 (если не выбран
`--system-java`), размещает immutable release, launcher и systemd unit, затем
запускает сервис.

Для offline host перенесите доверенный Temurin 21 tarball:

```bash
sudo packaging/install.sh \
  --jar /tmp/ioc-extractor.jar \
  --checksum /tmp/ioc-extractor.jar.sha256 \
  --jdk-tarball /tmp/temurin-21.tar.gz
```

`--no-start` позволяет проверить config перед первым запуском. Все options:
`packaging/install.sh --help`.

## Installed layout

```text
<prefix>/
├── current -> releases/<release-id>
├── releases/<release-id>/ioc-app.jar
├── bin/ioc
├── etc/application.yml
├── etc/ioc-extractor.env
├── var/db/
├── var/export/
├── var/inbox/  var/processing/  var/done/  var/failed/
├── var/ledger/ var/logs/
└── dataframe/
```

`releases/` неизменяем. Операторское состояние находится в `etc/`, `var/` и
`dataframe/`, а не внутри release directory.

## Конфигурация и проверка

Редактируйте `<prefix>/etc/application.yml` по
[справочнику конфигурации](configuration.md). Секреты помещайте в
`<prefix>/etc/ioc-extractor.env`.

```bash
sudo systemctl restart ioc-extractor
sudo systemctl status ioc-extractor --no-pager
sudo -u ioc /opt/ioc-extractor/bin/ioc --version
sudo -u ioc /opt/ioc-extractor/bin/ioc health
sudo journalctl -u ioc-extractor -n 100 --no-pager
```

Health endpoint по умолчанию доступен только на loopback. Healthy local service
не означает, что optional SMB endpoints уже прошли authentication: sync
components могут оставаться `UNKNOWN` до первой операции.

## Upgrade через `install.sh`

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
  --release-id v0.1.1
```

Существующий operator file не перезаписывается. Если packaged template изменён,
installer создаёт `application.yml.new` или `ioc-extractor.env.new`. Сравните
файлы, перенесите новые supported properties, сохранив site-specific paths,
policies и secrets, и удалите `.new` после успешной проверки.

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
Maven gate, создаёт release с commit/build time, копирует обе SQLite DB, атомарно
переключает `current`, запускает сервис и выполняет local health gate. При ошибке
восстанавливает прежний symlink и DB backup. Это путь для test stand, а не замена
reviewed production release process.

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
account. Перед применением создайте внешний backup и проверьте точный prefix.

## Post-deployment checklist

- `ioc --version` совпадает с нужным release/commit.
- `systemctl is-active ioc-extractor` возвращает `active`.
- В `ioc health` нет необъяснённого `DOWN`.
- Startup logs не содержат `CONFIG.*` или неожиданного override.
- YAML/environment имеют restrictive ownership.
- Backup/restore paths не входят в retention targets.
- Inbox/processing/done/failed различаются и имеют минимальные права.
- Remote sync проходит checklist из [гайда remote storage](remote-storage-sync.md).
