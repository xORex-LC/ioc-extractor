# tools

## Назначение

Воспроизводимые developer/CI-инструменты поверх Maven wrapper и bootable jar.
Корневой [`Makefile`](../Makefile) — стабильный интерфейс для людей и CI, а этот
каталог содержит вызываемую им реализацию. Скрипты можно вызывать напрямую для
расширенных параметров. Tools-layer не заменяет Maven lifecycle и не содержит
production deployment automation — она остаётся в [`packaging/`](../packaging/README.md).

## Состав

| Каталог | Ответственность |
|---|---|
| [`dev/`](dev/README.md) | Doctor, IOC fixtures, изолированный daemon runtime, smoke и ECS log queries |
| [`ci/`](ci/README.md) | Неинтерактивные leaf-gates для build, docs, packaging и Dependency-Check |
| [`tests/`](tests/README.md) | Contract-тесты самого tools-layer |

Файл [`eclipse-jdt.prefs`](eclipse-jdt.prefs) задаёт узкую IDE-политику для
Eclipse JDT: игнорируется только `nullUncheckedConversion` на границе generic
method references и сторонних null-аннотаций. Реальные и потенциальные
null-доступы не отключаются. VS Code Java подключает файл через локальный
`java.settings.url`; путь должен указывать на текущий checkout.

```json
{
  "java.compile.nullAnalysis.mode": "automatic",
  "java.settings.url": "/absolute/path/to/ioc-extractor/tools/eclipse-jdt.prefs"
}
```

Начальная точка:

```bash
make help
make doctor
make bootstrap  # repo-local lychee, если его нет в PATH
make pre-push
```

`make pre-push` последовательно выполняет те же leaf scripts, а значит те же
Maven, shell-contract и offline-documentation gates, что обычный GitHub CI.
Workflows вызывают scripts напрямую, не зависят от Makefile и остаются
прозрачными.
Dependency-Check остаётся отдельным security gate: `make security-update`
явно обновляет NVD data по сети, а `make security-scan` быстро и детерминированно
анализирует reactor только по имеющейся локальной базе. Scheduled workflow
выполняет эти операции последовательно отдельными шагами.

Все runtime-файлы developer environment создаются только под `/.dev/` и
игнорируются Git. Скрипты не должны писать business data напрямую в SQLite:
fixtures проходят через публичные `extract`/daemon ingest пути.
