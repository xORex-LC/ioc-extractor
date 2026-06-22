# packaging

Установка и эксплуатация **ioc-extractor** как системного демона (`systemd`) на
Debian 11+. Релиз `0.1.0`.

## Содержимое

| Файл | Назначение |
|---|---|
| `install.sh` | Установщик с нуля (root): JDK 21 вручную, пользователь, каталоги, jar, конфиг, systemd-unit. |
| `uninstall.sh` | Остановка/удаление сервиса; `--purge` — снести каталог и пользователя. |
| `templates/ioc-extractor.service` | Шаблон unit; плейсхолдеры `@PREFIX@/@VERSION@/@JAVA_BIN@/@USER@` подставляются при установке. |
| `templates/application.yml` | Прод-override (daemon-режим); кладётся в `<prefix>/etc/application.yml`. |
| `templates/ioc-extractor.env` | `EnvironmentFile` с `JAVA_OPTS`. |

**Правило слоя:** только материалы развёртывания (shell + шаблоны), без Java-кода и
бизнес-логики. Демон-режим и конфигурация описаны в
[docs/ingestion.md](../docs/ingestion.md) и
[application.yml](../bootstrap/ioc-app/src/main/resources/application.yml).

## Java 21 — только вручную

В репозиториях Debian 11 нет JDK 21, поэтому установщик ставит **Temurin 21 из
tarball** (apt-репозитории не подключаются) в `<prefix>/jdk` — самодостаточно:

- офлайн-хост: `--jdk-tarball /path/OpenJDK21-jdk_x64_linux_hotspot.tar.gz`;
- хост с интернетом: tarball скачивается с Adoptium автоматически (или `--jdk-url`);
- если на хосте уже есть `java ≥ 21`: `--system-java`.

## Раскладка (single-dir)

```
<prefix>/                         # по умолчанию /opt/ioc-extractor
├── jdk/                          # Temurin 21 (установлен вручную)
├── lib/ioc-app-0.1.0.jar
├── etc/application.yml           # override (правится оператором)
├── etc/ioc-extractor.env         # JAVA_OPTS
├── var/                          # inbox/ processing/ done/ failed/ ledger/ logs/
└── dataframe/                    # partitions/ + сгенерированные артефакты + lookup-seed
```

`WorkingDirectory` сервиса = `<prefix>`, поэтому относительные пути приложения
(`./var/...`, `./dataframe/...`) разрешаются внутри каталога установки.

## Установка

```bash
# 1) собрать релизный jar (нужен JDK 21; см. корневой README/CLAUDE.md)
./mvnw -q -DskipTests package        # -> bootstrap/ioc-app/target/ioc-app-0.1.0.jar

# 2) установить (jar найдётся автоматически рядом или в target/)
sudo packaging/install.sh                         # спросит каталог (деф. /opt/ioc-extractor)
sudo packaging/install.sh --prefix /opt/ioc-extractor
sudo packaging/install.sh --jdk-tarball /tmp/temurin21.tar.gz   # офлайн-хост
sudo packaging/install.sh --jar /path/ioc-app-0.1.0.jar --no-start
```

Установщик **идемпотентен**: повторный запуск обновляет jar и unit, существующий
`etc/application.yml` не перетирается (рядом пишется `*.new`), если не передан `--force`.
Установщик **откажется** ставить в каталог с `pom.xml`/`.git` (дерево исходников) без `--force`.

## Эксплуатация

```bash
systemctl status ioc-extractor
journalctl -u ioc-extractor -f             # ECS-JSON логи (+ <prefix>/var/logs/)
cp report.htm /opt/ioc-extractor/var/inbox/   # подать источник на обработку
```

Поток: `var/inbox` → (quiet-period) → `var/processing` → `var/done` (или `var/failed`),
запись партиции в `dataframe/partitions/`, периодическая агрегация (стабильные id) в
`dataframe/repListMasks_generated.csv` и `dataframe/hashes_generated.csv`.

После правки `etc/application.yml`: `systemctl restart ioc-extractor`.

## Удаление

```bash
sudo packaging/uninstall.sh                 # снять сервис, данные сохранить
sudo packaging/uninstall.sh --purge         # + удалить каталог и пользователя ioc
```

## Вне скоупа (0.1.0)

`.deb`-пакет с maintainer-скриптами; логротация средствами ОС (сейчас встроенный
rolling-appender Logback).
