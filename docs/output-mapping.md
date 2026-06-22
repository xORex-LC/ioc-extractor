# Конфигурируемое заполнение артефактов

Логика заполнения выходных артефактов **не зашивается в код**. Какие колонки,
чем заполняются и как преобразуются — задаётся **конфигурацией**. Формат,
выразимый **существующими** провайдерами/трансформациями, добавляется блоком
конфигурации (без кода); новая семантика колонки — это новый тонкий
provider/transform (OCP, но это код).

> Статус: **реализовано**. `ConfigurableRowMapper` использует provider/transform
> registries и единую artifact definition как для oneshot canonical sinks, так и
> для daemon partition/canonical CSV adapters.

## Принцип

Один generic `ConfigurableRowMapper` интерпретирует декларативную спецификацию
колонок, опираясь на два **реестра** (расширяются точечно, OCP):

- **Провайдеры значений** (`ValueProvider`) — откуда брать значение колонки.
- **Трансформации** (`Transform`) — как его преобразовать.

```
ColumnSpec(name, from, value?, when-type?, transform[])
        │
        ▼
ConfigurableRowMapper ──uses──▶ Map<key, ValueProvider>
                       └─uses──▶ Map<key, Transform>
```

## Модель колонки

| Поле | Назначение |
|---|---|
| `name` | имя колонки (формирует заголовок) |
| `from` | ключ провайдера значения, либо `const` |
| `value` | значение для `from: const` (пусто → CSV `NULL`) |
| `when-type` | колонка заполняется только для этого `IndicatorType`, иначе `NULL` |
| `transform` | список трансформаций, применяются по порядку |

## Реестр провайдеров (`from`)

| Ключ | Возвращает |
|---|---|
| `id` | назначенный id записи |
| `value` | значение индикатора (URL/домен/IP/хэш) |
| `source.label` | метка провенанса (`source`) |
| `match.url` | код `url_match` от доменной `MatchPolicy` |
| `match.host` | код `host_match` от доменной `MatchPolicy` |
| `type` | тип индикатора (на будущее) |
| `const` | литерал из `value` (спец-случай, не провайдер) |

## Реестр трансформаций (`transform`)

| Ключ | Действие |
|---|---|
| `lower` | в нижний регистр (всё значение) |
| `lower-host` | в нижний регистр только host/scheme (путь/токен сохраняются) |
| `upper` | в верхний регистр |
| `strip-prefix:<arg>` | убрать префикс `<arg>` (напр. `strip-prefix:Письмо `) |

## Ограничения DSL (не интерпретатор)

Конфиг заполнения — **декларация, не язык**. Чтобы конфиг не превратился в «код
без компилятора», DSL намеренно ограничен:

- колонка = `from` (провайдер) **или** `const`, опц. `when-type`, опц.
  упорядоченный список `transform`;
- **нет** вложенных выражений, арифметики/строковых операций в YAML;
- **нет** условий сложнее `when-type` (одно равенство типа);
- любая логика сложнее — это новый тонкий `ValueProvider`/`Transform` (код), а не
  выражение в конфиге.

## Декларативная классификация масок (match.url / match.host)

Коды `url_match`/`host_match` **тоже не зашиты в код**: провайдеры `match.url`/
`match.host` делегируют тонкому **rule-based** вычислителю, читающему
декларативные правила. Логика «какой вариант» — в конфиге.

**Инструменты (тонкие, переиспользуемые):**
- `IndicatorFeatures` — признаки значения (`scheme/host/port/path/query`, `is-ip`),
  которые строит **доменный `IndicatorFeatureExtractor`** (поверх доменного
  `IndicatorNormalizer`) — не строковый парсинг в маппере. Вид хоста
  (`registrable`/`subdomain`) — через **порт `HostClassifier`** (адаптер на Guava
  PSL, `adapter-psl`), чтобы домен оставался без Guava (см.
  [boundaries.md](boundaries.md), [services.md](services.md)).
- Реестр **предикатов** над признаками: `has-query`, `has-path`, `has-port`,
  `has-path-or-port`, `is-ip`, `is-subdomain`, `is-registrable`.
- `RuleBasedMatchPolicy` — берёт первое правило, все предикаты `when` которого
  истинны (AND), и отдаёт его коды.

**Правила (декларативно, по порядку, first-match-wins):**
```yaml
ioc:
  classify:
    rules:
      - when: [ has-query ]            # вариант 4
        url-match: "u:hAS,pEX"
        host-match: null
      - when: [ has-path-or-port ]     # вариант 3
        url-match: "u:hEX,dEX"
        host-match: null
      - when: [ is-subdomain ]         # вариант 2
        url-match: "u:hEX"
        host-match: "h:dEX"
      - when: [ ]                       # по умолчанию → вариант 1 (registrable/IP)
        url-match: "u:hAS"
        host-match: "h:dAS"
```

Порядок правил кодирует подтверждённый приоритет: query важнее пути (вариант 4
раньше 3); IP без PSL не проходит `is-subdomain` и попадает в default → вариант 1.
Новый предикат = тонкий класс в реестре; новый вариант = правило в конфиге, без
изменения кода. Триггеры зафиксированы в
[notes/0002](notes/0002-output-mapping-and-matching.md).

## Пример конфигурации

```yaml
artifacts:
  - name: masks
    accepts: [ IPV4, DOMAIN, URL ]
    id: { strategy: ascending, start: auto }
    columns:
      - { name: id,              from: id }
      - { name: mask,            from: value, transform: [ lower-host ] }
      - { name: url_match,       from: match.url }
      - { name: host_match,      from: match.host }
      - { name: score,           from: const }
      - { name: time_last_seen,  from: const }
      - { name: time_first_seen, from: const }
      - { name: threat_type,     from: const }
      - { name: source,          from: source.label }
      - { name: description,     from: const }
  - name: hashes
    accepts: [ MD5, SHA1, SHA256 ]
    id: { strategy: ascending, start: 10024 }
    columns:
      - { name: id,          from: id }
      - { name: hash_md5,    from: value, when-type: MD5,    transform: [ upper ] }
      - { name: hash_sha256, from: value, when-type: SHA256, transform: [ upper ] }
      - { name: hash_sha1,   from: value, when-type: SHA1,   transform: [ upper ] }
      - { name: score,       from: const }
      - { name: time_last_seen,  from: const }
      - { name: time_first_seen, from: const }
      - { name: threat_type, from: const }
      - { name: source,      from: source.label, transform: [ "strip-prefix:Письмо " ] }
      - { name: description, from: const }
```

## Размещение и границы

- `ConfigurableRowMapper`, провайдеры и трансформации — в адаптере вывода
  (`adapter/out/sink/csv`): форматирование — адаптерная забота.
- Провайдеры `match.*` вызывают **доменную** `MatchPolicy` (классификация —
  доменная логика; коды и колонки — конфиг). См.
  [architecture.md](architecture.md#классификация-сетевых-масок).
- Расширение: новый провайдер/трансформация = новый класс, реализующий
  `ValueProvider`/`Transform` с уникальным ключом (бин, собирается в реестр) —
  конфиг ссылается по ключу. Код существующих артефактов не меняется (OCP).

## Что устраняем

- Хардкод порядка/набора колонок и правил заполнения в Java.
- Привязку «один артефакт = один класс-маппер».
- `value-case`/`source-strip`/`header`-строку в коде — теперь это `transform`/
  `columns` в конфиге.
