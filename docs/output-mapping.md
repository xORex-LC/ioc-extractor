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

Перед маппингом артефакт также может применить **маршрутизирующий фильтр**
поверх `accepts`: тип индикатора остаётся грубым маршрутом, а `include`/`exclude`
уточняют его по признакам (`is-bare-ip`, `has-path-or-port`, `is-ip` и т.п.).

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
| `address.url` | значение для blacklist-колонки: всё, кроме голого IP (домены, URL, IP-URL); голый IP → `NULL` |
| `address.ip` | значение для blacklist-колонки: только голый IP (`IPV4` без port/path/query); остальное → `NULL` |
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
- артефакт = `accepts` + опц. `include`/`exclude` — списки именованных
  предикатов над уже вычисляемыми признаками индикатора;
- **нет** вложенных выражений, арифметики/строковых операций в YAML;
- на уровне колонки **нет** условий сложнее `when-type` (одно равенство типа);
- любая логика сложнее — это новый тонкий `ValueProvider`/`Transform` (код), а не
  выражение в конфиге.

`include` работает как AND: все перечисленные предикаты должны выполниться.
`exclude` отбрасывает индикатор, если сработал хотя бы один предикат. Пустые
списки означают «без дополнительного фильтра».

## Id и lookup baseline

Артефакты имеют независимые id-space. Для `id.start: auto` baseline берётся не
из общего max id, а из lookup path конкретного артефакта:

```yaml
ioc:
  lookup:
    artifacts:
      - { name: masks,   path: "./dataframe/masks_list.csv" }
      - { name: ip_list, path: "./dataframe/ip_list.csv" }
      - { name: hashes,  path: "./dataframe/hashes_list.csv" }
```

Если artifact-specific path не задан, используется старый fallback
`lookup.path`/`LookupRepository.maxId()`. Артефакты без `id` в колонках
(`address_blacklist`) могут не иметь lookup baseline.

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
  `has-path-or-port`, `is-ip`, `is-subdomain`, `is-registrable`, `is-onion`.
  Артефактные фильтры используют тот же реестр плюс специализированный
  `is-bare-ip` (тип `IPV4`, IP-host, без port/path/query). Единственный источник
  истины — `domain.feature.NetworkAddressClassifier.isBareIp(...)`; его же
  используют провайдеры `address.url`/`address.ip` (никакого дублирования формул).
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
[dev/0002](dev/0002-output-mapping-and-matching.md).

## Пример конфигурации

```yaml
artifacts:
  - name: masks
    accepts: [ IPV4, DOMAIN, URL ]
    exclude: [ is-bare-ip ]
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
  - name: ip_list
    accepts: [ IPV4 ]
    include: [ is-bare-ip ]
    id: { strategy: ascending, start: auto }
    columns:
      - { name: id,              from: id }
      - { name: ip,              from: value }
      - { name: score,           from: const }
      - { name: time_last_seen,  from: const }
      - { name: time_first_seen, from: const }
      - { name: threat_type,     from: const }
      - { name: source,          from: source.label, transform: [ "strip-prefix:Письмо " ] }
      - { name: description,     from: const }
  - name: address_blacklist        # bare IP -> forbidden_ip; остальное -> forbidden_url
    accepts: [ IPV4, DOMAIN, URL ]
    columns:
      - { name: forbidden_url, from: address.url, transform: [ lower-host ] }
      - { name: forbidden_ip,  from: address.ip,  transform: [ lower-host ] }
  - name: hashes
    accepts: [ MD5, SHA1, SHA256 ]
    id: { strategy: ascending, start: auto }
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

## Кодировка вывода (граница выхода)

`ioc.sink.csv.charset` (дефолт `UTF-8`) — кодировка **всех** CSV-артефактов и
**чтения существующих** артефактов в lookup/агрегации, чтобы запись и чтение
всегда совпадали (один «диалект dataframe» — без рассинхрона read/write):

- применяется во всех писателях (`CsvIocSink`, партиционный синк,
  `CsvArtifactRepositories`, `CsvStableIdIndex`) и читателях (lookup-репозитории);
- **непредставимый символ** в целевой кодировке (напр. emoji в cp1251)
  **заменяется**, а не роняет батч (`CodingErrorAction.REPLACE`); для UTF-8 это
  no-op;
- неизвестное имя кодировки → fail-fast на старте.

Входная кодировка источника — отдельный knob `ioc.source.charset` (см.
extraction).

## Что устраняем

- Хардкод порядка/набора колонок и правил заполнения в Java.
- Привязку «один артефакт = один класс-маппер».
- `value-case`/`source-strip`/`header`-строку в коде — теперь это `transform`/
  `columns` в конфиге.
