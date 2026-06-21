# com.iocextractor.adapter.out.psl

## Назначение

Выходной адаптер доменного порта `HostClassifier` на основе Public Suffix List
(Guava `InternetDomainName`).

**Правило слоя:** реализует доменный порт; **Guava живёт только здесь** — домен о
PSL-библиотеке не знает (граница стережётся ArchUnit).

## Структура

| Файл | Назначение |
|---|---|
| `PslHostClassifier.java` | host → `HostKind` через PSL (incl. private-секция); IP/`.onion` — отдельно |

## Заметки

Используется public-suffix-список **с private-секцией**: `x.duckdns.org`/
`*.workers.dev` → `REGISTRABLE`; провайдер вне PSL (`tw1.ru`) → `cs371620.tw1.ru`
= `SUBDOMAIN`. Подключается в стадии 3 (`RuleBasedMatchPolicy`).
