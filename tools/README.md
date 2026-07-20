# tools

## Назначение

Воспроизводимые developer/CI-инструменты поверх Maven wrapper и bootable jar.
Каталог содержит логику, которую затем могут вызывать Makefile, GitHub Actions
или разработчик напрямую. Он не заменяет Maven lifecycle и не содержит
production deployment automation — она остаётся в [`packaging/`](../packaging/README.md).

## Состав

| Каталог | Ответственность |
|---|---|
| [`dev/`](dev/README.md) | Doctor, IOC fixtures, изолированный daemon runtime, smoke и ECS log queries |
| [`ci/`](ci/README.md) | Неинтерактивные leaf-gates для build, docs, packaging и Dependency-Check |
| [`tests/`](tests/README.md) | Contract-тесты самого tools-layer |

Все runtime-файлы developer environment создаются только под `/.dev/` и
игнорируются Git. Скрипты не должны писать business data напрямую в SQLite:
fixtures проходят через публичные `extract`/daemon ingest пути.
