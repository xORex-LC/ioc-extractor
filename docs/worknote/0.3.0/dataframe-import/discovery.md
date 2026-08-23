---
title: "DATA-IMPORT-01 — интервью о dataframe import"
version: "0.3.0"
status: "Interview completed"
document_type: "Discovery worknote"
source_of_truth: false
language: "ru"
---

# DATA-IMPORT-01 — интервью о dataframe import

## 1. Назначение

Документ сохраняет требования заказчика, неочевидные product-развилки,
архитектурные последствия и рекомендации по импорту готовых dataframe CSV в
canonical SQLite truth. Интервью должно определить наблюдаемое поведение,
операционный UX, failure/recovery semantics и compatibility contract до выбора
конкретных классов, SQL schema или библиотек.

## 2. Исходные бизнес-требования

### BR-01 — local и SMB intake с низкой задержкой

- Оператор помещает файлы в отдельный import-каталог в рабочей директории
  приложения либо в SMB share.
- Local и SMB варианты должны приводить к одному application import mechanism.
- Для SMB требуется сохранить аналогичный текущему `CHANGE_NOTIFY` fast path,
  чтобы минимизировать задержку между появлением данных и обработкой.

### BR-02 — декларативное распознавание и mapping

- Import не зависит от имени файла, порядка колонок или фиксированных внешних
  имён столбцов.
- Конфигурация декларативно описывает допустимые source schemas, правила
  распознавания типа списка и mapping внешних полей в canonical artifact row.
- После помещения CSV в каталог сервис сам определяет подходящее описание и
  целевой artifact, например `address_blacklist`, `ip_list`, `masks` или
  `hashes`.

### BR-03 — сохранение входного порядка `export_slot`

- Входной `id` трактуется как source `export_slot` и должен сохраняться строго,
  когда соответствующий slot свободен в локальном целевом namespace.
- Если requested slot уже занят локально другой записью, новой импортированной
  записи назначается ближайший свободный slot.
- Алгоритм должен согласовываться с действующей моделью stable sparse reusable
  export slots, а не превращать `id` в canonical row identity.

### BR-04 — два режима обработки

- Минимум один режим импортирует уже подготовленные значения без повторного
  business processing.
- Второй режим выполняет предобработку импортируемых данных через применимые
  правила текущего processing pipeline.
- Defaults: processing mode `as-is`, routing policy `target-only`.
- Точная семантика «как есть» и «через конвейер», включая допустимые стадии и
  failure policy, пока не определена.

### BR-05 — обогащение уже существующей записи

- Один business IOC может поступить из source list одного типа, быть
  распределён в смежный artifact, а затем повторно встретиться в source list
  другого типа.
- Если повтор имеет тот же canonical `row_key`, но содержит дополнительные или
  отличающиеся значения других public columns, canonical record должна быть
  обновлена, когда это требуется принятой merge policy.
- Понятия «дополнительные», «отличающиеся» и «необходимое обновление», включая
  обработку `NULL`, конфликтов и source precedence, пока не определены.

## 3. Подтверждённый архитектурный контекст

1. SQLite dataframe остаётся единственным business source of truth; import не
   возвращает CSV lookup/storage mode.
2. Mutable `*_generated.csv` и immutable slices остаются производными
   представлениями canonical data.
3. Canonical `row_key`, canonical row ID, lifecycle ID, source identity и
   export slot — разные namespaces.
4. Действующий export slot имеет scope `(profile, artifact)` и может быть
   переиспользован только после исчезновения прежней lifecycle из active set.
5. `CHANGE_NOTIFY` в текущей архитектуре является doorbell/latency hint.
   Correctness обеспечивают listing/detection, durable ledger, idempotency и
   periodic reconcile.
6. Текущая canonical write transaction атомарна в пределах одного artifact;
   общей ACID transaction на несколько artifacts нет.
7. ADR-0015 ранее удалил operator CSV seed. Новый явный import contract должен
   supersede это решение, не редактируя историю и не оживляя legacy lookup.

## 4. Предварительные наблюдения и ограничения

- Распознавание source schema должно завершаться ровно одним результатом.
  Ноль совпадений и неоднозначное совпадение — разные операторские ошибки; выбор
  «первого подходящего mapping» опасен и недетерминирован.
- Независимость от порядка и внешних имён колонок не означает отсутствие schema
  contract: aliases, required/optional fields, null grammar, types и semantic
  predicates должны быть versioned и проверяемы.
- Fast path не может быть authority. Local watch events и SMB `CHANGE_NOTIFY`
  могут теряться, дублироваться и приходить до завершения копирования.
- Строгое сохранение requested slots требует сначала определить точный target
  namespace. Один artifact потенциально может входить в несколько export
  profiles с независимыми slot registries.
- Наивное распределение конфликтов в порядке строк может занять slot, который
  следующая строка запрашивает без конфликта. Вероятно потребуется двухфазное
  deterministic allocation: сначала сохранить все свободные exact requests,
  затем размещать конфликты. Это пока рекомендация, не принятое решение.
- В режиме lifecycle `ACTIVE` source `id` нельзя записать как local canonical
  ID. Для выполнения BR-03 import должен передавать slot intent в export-owned
  registry через отдельный contract.
- «Предобработка через текущий конвейер» не может означать буквальный запуск
  document-reading stages над готовой artifact row. Нужно позднее определить,
  какие source values восстанавливают domain indicator и какие pipeline stages
  применимы без потери заданных оператором полей.

## 5. Правила интервью

- Один вопрос закрывает одну определяющую business-развилку.
- Для вопроса фиксируются сценарий, риск, варианты и текущая рекомендация.
- Формулировка ответа заказчика сохраняется; техническая интерпретация идёт
  отдельно.
- Новый вопрос задаётся после записи предыдущего решения и его последствий.
- Реализация начинается только после отдельного approved architecture и
  implementation plan.

## 6. Interview ledger

| ID | Тема | Статус |
|---|---|---|
| `I-01` | Граница одной import delivery и момент готовности набора файлов | `DECIDED` |
| `I-02` | Один target artifact на файл или explicit mixed-row routing | `DECIDED` |
| `I-03` | Merge/update policy для повторного canonical `row_key` | `DECIDED` |
| `I-04` | Present-row patch или authoritative complete snapshot | `DECIDED` |
| `I-05` | Изменение identity-bearing value и корреляция source record | `DECIDED` |
| `I-06` | Повторная поставка byte-identical CSV и TTL confirmation | `DECIDED` |
| `I-07` | Terminal file disposition и граница новой occurrence | `DECIDED` |
| `I-08` | File/row atomicity и partial-success policy | `DECIDED` |
| `I-09` | Атомарность одной row при multi-artifact fan-out | `DECIDED` |
| `I-10` | ACID/recovery boundary accepted write set | `DECIDED` |
| `I-11` | Точная семантика processing mode `as-is` | `DECIDED` |
| `I-12` | Ownership derived fields в processing mode `processed` | `DECIDED` |
| `I-13` | Processing mode и merge authority active DB | `DECIDED` |
| `I-14` | Public time fields и lifecycle/TTL boundary | `DECIDED` |
| `I-15` | Duplicate `row_key` внутри одной delivery | `DECIDED` |
| `I-16` | Namespace импортируемого export slot | `DECIDED` |
| `I-17` | Collision policy для requested export slot | `DECIDED` |
| `I-18` | Duplicate requested slot внутри одной delivery | `DECIDED` |
| `I-19` | Zero/ambiguous source-contract recognition | `DECIDED` |
| `I-20` | Versioned contract и retry/cutover semantics | `DECIDED` |
| `I-21` | CSV representation of `ABSENT`, `NULL` и empty value | `DECIDED` |
| `I-22` | CSV dialect/charset declaration versus auto-detection | `DECIDED` |
| `I-23` | Ordering одновременно готовых import deliveries | `DECIDED` |
| `I-24` | Authority import против обычного document ingestion | `DECIDED` |
| `I-25` | Manual replay terminal delivery | `DECIDED` |
| `I-26` | Terminal file fate и operator-facing delivery report | `DECIDED` |
| `I-27` | Retention original CSV, report и compact audit | `DECIDED` |
| `I-28` | Resource limits и oversized delivery behavior | `DECIDED` |
| `I-29` | Source-contract configuration activation boundary | `DECIDED` |
| `I-30` | Cardinality semantic observation в `processed` row | `DECIDED` |
| `I-31` | Matching частично заполненной compound record | `DECIDED` |
| `I-32` | Conflict policy для identifying fields compound record | `DECIDED` |
| `I-33` | Несколько значений одной роли в compound record | `DECIDED` |
| `I-34` | Trust/authority binding import source и source contract | `DECIDED` |
| `I-35` | Spreadsheet formula safety импортируемых free-text fields | `DECIDED` |
| `I-36` | Export/revision significance public-field update | `DECIDED` |
| `I-37` | Extra, duplicate и renamed source columns | `DECIDED` |
| `I-38` | Read-only preview/validate UX | `DECIDED` |
| `I-39` | Import health, backlog и manual recovery UX | `DECIDED` |
| `I-40` | Immutable-byte claim boundary local/SMB delivery | `DECIDED` |
| `I-41` | Requested slot против stable slot совпавшей active record | `DECIDED` |

### Оценка оставшегося discovery после I-15

До архитектурного проекта остаётся ориентировочно **8–12 самостоятельных
business-развилок**. Точное число может уменьшиться, если несколько связанных
решений будут приняты одним ответом, либо увеличиться, если ответ откроет новый
существенный сценарий. Планируемые блоки:

1. scope импортируемого export slot и правила collision/allocation;
2. неоднозначное распознавание source contract и version evolution;
3. CSV dialect, encoding, `NULL`/empty/missing representation и limits;
4. ordering/concurrency нескольких одновременно готовых deliveries;
5. durable idempotency, retry и управляемый replay уже terminal delivery;
6. provenance/audit и operator-facing import report;
7. processed-mode reconstruction для разных IOC/artifact shapes;
8. configuration activation, validation и restart/hot-reload boundary;
9. capacity/backpressure и защита от чрезмерно больших/опасных inputs;
10. security/SMB trust boundary и operational recovery UX.

После этого останутся не новые product-вопросы, а архитектурная сборка уже
принятых решений, review и отдельное утверждение implementation plan.

### Актуальная оценка оставшегося discovery после I-34

После уточнения compound-record semantics и trust boundary осталось
ориентировочно **5–7 самостоятельных product/operational решений**, включая
открытый I-35. Несколько из них могут закрыться одним ответом:

1. spreadsheet safety импортируемого free text без silent mutation (I-35);
2. revision/export/slice semantics при изменении public fields существующей
   active record;
3. tolerance contract для extra/duplicate/renamed columns и безопасная schema
   evolution;
4. operator preview/validate-only UX перед фактическим import;
5. observable health/backlog/manual recovery contract import subsystem;
6. final file-integrity/claim boundary для local и SMB occurrence;
7. итоговый contradiction/round-trip review принятых default и optional
   policies; он может открыть не более одной дополнительной product-развилки.

После этих блоков интервью считается завершённым. Затем отдельно выполняются
architecture project, superseding ADR/release-scope decision и implementation
plan; это уже проектирование и планирование, а не продолжение product interview.

### I-01 — граница одной import delivery и момент готовности файлов

#### Сценарий

Оператор копирует в local/SMB import-каталог несколько CSV. События появляются
по одному; один файл может копироваться дольше, повториться, оказаться временно
неполным или вообще относиться к другой поставке. Имя файла по BR-02 не является
business identity. Quiet period может показать только стабильность bytes одного
файла, но не доказывает полноту бизнес-набора.

#### Вопрос заказчику

Несколько файлов, помещённых оператором примерно одновременно, являются
независимыми поставками, каждая из которых должна импортироваться сразу после
стабилизации, или они могут составлять один неделимый business batch, который
нельзя частично применять до появления явного признака комплектности?

#### Варианты

1. **Каждый файл — отдельная delivery.** Минимальная задержка и простой retry,
   но никакой атомарности или общей completeness между artifacts.
2. **Только явный batch.** Manifest/completion marker либо atomic directory
   publish объявляет точный состав; до него файлы не применяются.
3. **Оба режима задаются per import source.** Independent-file остаётся простым
   контуром, а coherent feeds требуют явного batch protocol.

#### Текущая рекомендация

Если реальные источники действительно бывают обоих типов, использовать вариант
3, но никогда не угадывать batch по близости timestamps, именам файлов или
quiet period. Для coherent batch нужен явный completion contract; для
independent file достаточно stability check, durable content identity и
идемпотентного ledger. В обоих случаях `CHANGE_NOTIFY` только запускает
повторную detection, а periodic full listing остаётся correctness backstop.

#### Ответ и решение

Ответ заказчика: «Каждый CSV — самостоятельная поставка и импортируется сразу
после стабилизации».

Принят вариант 1:

- один физический CSV после успешной проверки стабильности образует одну
  independent business delivery;
- файлы не объединяются в batch по каталогу, близости времени, общему
  `CHANGE_NOTIFY`, схожим колонкам или одному SMB listing;
- import одного файла не ждёт появления других artifacts и не требует общего
  completion marker/manifest;
- отказ или задержка одного файла не блокирует независимые deliveries.

#### Архитектурные последствия

1. Каждая delivery получает собственные observation/import-run identity,
   content identity, ledger state и terminal outcome.
2. Stability check является admission одного файла: размер/mtime должны быть
   неизменны в течение quiet period, после чего файл повторно открывается и
   проверяется до claim/import. Конкретный алгоритм и TOCTOU-защита будут
   определены позже.
3. `CHANGE_NOTIFY`, local watch event и periodic listing могут обнаружить один
   файл несколько раз. Все пути обязаны сходиться в одну detection/idempotency
   boundary; event не является доказательством новой delivery или готовности
   bytes.
4. Cross-file transaction, rollback и completeness protocol не требуются.
   Граница атомарности внутри одного файла зависит от решения I-02: файл может
   оказаться one-artifact command либо явно разрешённым multi-artifact source.
5. Повторное помещение тех же bytes как новой операторской поставки и retry
   одной уже claimed delivery пока не отождествляются; их lifecycle semantics
   будут рассмотрены отдельным вопросом.

### I-02 — один target artifact на файл или explicit mixed-row routing

#### Сценарий

Декларативные mappings могут пересекаться. Например, `id`, `source`, `score` и
`description` встречаются в нескольких artifacts, aliases могут скрыть реальные
имена, а generic feed может содержать колонку `type` и смешанные IP, URL, domain
и hash rows. Тогда совпадение только по набору headers либо даст несколько
кандидатов, либо ошибочно назначит всему файлу один artifact.

#### Вопрос заказчику

Должен ли каждый CSV после распознавания целиком соответствовать ровно одному
target artifact (`masks`, `ip_list`, `hashes`, `address_blacklist`), или в
реальных поставках ожидается один mixed CSV, строки которого по явному
discriminator должны маршрутизироваться в разные artifacts?

#### Варианты

1. **Один файл → один artifact.** Configured source schema распознаёт весь файл
   и задаёт один mapping/target; смешанные строки являются ошибкой delivery.
2. **Explicit mixed source.** Один распознанный source contract содержит
   обязательный discriminator и декларативные row-routing rules в несколько
   artifacts.
3. **Неявное multi-match/priority.** Несколько mappings пробуются по очереди, а
   выигрывает первый или наиболее приоритетный.

#### Текущая рекомендация

Вариант 3 отклонить: результат зависит от порядка конфигурации и может тихо
измениться при добавлении нового mapping. File-level detection должна выбрать
ровно один versioned source contract; ноль и несколько совпадений приводят к
разным typed failures и quarantine без canonical write.

Если mixed feeds являются реальным requirement, поддержать вариант 2 только
явно: сам файл всё равно распознаётся как один source contract, а уже внутри
него deterministic discriminator маршрутизирует строки. Если таких источников
нет, V1 следует ограничить вариантом 1 и не строить преждевременный routing DSL.

#### Ответ и решение

Заказчик уточнил, что один CSV по смыслу может содержать observations,
применимые к нескольким выходным спискам. Например, IP из импортируемого
`address_blacklist` также должен быть способен попасть в `ip_list`. При
последующем импорте `ip_list` тот же IP может принести заполненные значения
других columns, которыми существующую запись следует при необходимости
обновить.

Принято следующее разделение:

1. Один файл по-прежнему распознаётся ровно как один configured source
   contract. Неоднозначный file-level multi-match и priority-first detection
   запрещены.
2. Source contract задаёт configurable routing policy:
   - `target-only` — применять observations только к primary artifact,
     объявленному contract-ом;
   - `related-artifacts` — дополнительно направлять semantic observations во
     все совместимые artifacts по тем же classification/routing rules, которые
     использует обычный document ingest.
3. Одна input row при `related-artifacts` может создать несколько prepared
   artifact rows. Это один управляемый fan-out, а не несколько независимо
   совпавших source mappings.
4. Названия config tokens предварительные и будут утверждены вместе с полной
   configuration model.

#### Архитектурные последствия

- External CSV model должен проходить через anti-corruption mapping в
  storage-neutral semantic import observation. Внешние column names и source
  layout не должны становиться domain/application contract.
- Artifact routing и network/hash classification нельзя дублировать в importer:
  `related-artifacts` переиспользует действующие decisions/providers либо их
  выделенную общую policy boundary.
- Routing scope и processing mode BR-04 являются независимыми измерениями.
  Например, raw-mapped observation может быть `target-only`, а normalized
  observation — `related-artifacts`; допустимые комбинации будут рассмотрены
  отдельно.
- Fan-out одного файла вновь открывает вопрос file-level atomicity: текущие
  canonical transactions разделены по artifacts. До решения failure semantics
  нельзя считать multi-artifact import атомарным.
- Требование обновлять существующие public values является новым behavior
  contract поверх действующего keep-first storage и выделено в BR-05/I-03.

### I-03 — merge/update policy для повторного canonical `row_key`

#### Сценарий

В canonical `ip_list` уже есть строка:

```text
ip=203.0.113.7, score=NULL, source=feed-A, description=curated
```

Новая delivery содержит тот же identity key:

```text
ip=203.0.113.7, score=80, source=feed-B, description=NULL
```

Действующий keep-first contract оставил бы public row без изменений, обновив
только provenance и lifecycle confirmation. BR-05 требует возможность изменить
public content, но «обновить заполненные данные» ещё не определяет, должен ли
новый `NULL` очистить старое значение, может ли менее доверенный source
перезаписать curated data и что делать с двумя разными non-null значениями.

#### Вопрос заказчику

Какое бизнес-правило должно определять итоговые значения non-identity columns
при повторном `row_key`: только заполнять ранее пустые поля, всегда принимать
последнюю delivery, учитывать приоритет источника либо задавать policy отдельно
для каждой колонки? В частности, имеет ли входной `NULL` право очистить уже
заполненное значение?

#### Варианты

1. **Fill-missing only.** Входной non-null заполняет только canonical `NULL`;
   существующее non-null никогда не заменяется, входной `NULL` ничего не
   очищает.
2. **Last-write-wins.** Последняя delivery заменяет все non-identity fields,
   включая очистку через `NULL` по отдельному правилу.
3. **Source precedence.** Более приоритетный configured source может заменить
   менее приоритетный; равный/низший источник применяет ограниченную policy.
4. **Per-field merge policy.** Для каждой public column настраивается
   `keep-first`, `fill-missing`, `replace` либо source-aware selection.

#### Текущая рекомендация

Не терять исходные source observations и не выполнять необратимый случайный
overwrite прямо в active public row. Identity columns и local export slot
остаются стабильными, а effective public values вычисляются детерминированной
policy из source-scoped evidence.

Для безопасного default предлагается `fill-missing`: новый `NULL` не очищает
старое значение, конфликтующие non-null сохраняют прежнее значение и дают
diagnostic. Явный `source-priority` или per-field `replace` можно разрешать
конфигурацией только там, где оператор действительно владеет authority.
Изменение effective public bytes должно продвигать `artifact_revision` и
создавать новый export candidate; отсутствие effective change обновляет только
provenance/lifecycle facts.

Это сильнее текущей compact provenance model: для пересчёта после expiry,
изменения priority либо удаления source evidence может понадобиться хранение
typed source-scoped values, а не только occurrence counters.

#### Ответ и решение

Заказчик принял безопасную default policy и потребовал дополнительные
декларативные варианты. Merge учитывает только active records текущей canonical
БД. Historical lifecycles не участвуют в matching, выборе effective values или
восстановлении полей и не важны для import behavior.

Приняты следующие правила:

1. Defaults для import source: processing `as-is`, routing `target-only`, update
   `fill-missing`.
2. Default `fill-missing` переносит входной non-null только в canonical `NULL`.
   Входной `NULL` не очищает active value; два разных non-null сохраняют прежнее
   значение и дают typed conflict diagnostic.
3. Update policy конфигурируется декларативно для мест, где требуется иное
   authority. Source contract может объявить импортируемое значение
   source-of-truth: тогда входной value заменяет active value, а явный входной
   `NULL` очищает его.
4. Policy должна быть применима к public fields всех artifact schemas, но
   identity-bearing columns требуют отдельного решения: изменение `ip`, `mask`,
   URL/address либо выбранного hash меняет `row_key`, а не является обычным
   обновлением того же record.
5. Реальное изменение effective public bytes сохраняет identity стабильной
   только для non-identity update, продвигает `artifact_revision` и mutable
   projection generation. No-op меняет лишь confirmation/provenance facts.

#### Трёхсостояние входного поля

Mapping обязан различать:

- `ABSENT` — external field отсутствует или mapping к нему не применим; source
  не высказывает требования и canonical value не меняется;
- `NULL` — field присутствует и содержит configured null representation;
  authoritative policy может явно очистить canonical value;
- `VALUE` — field присутствует с типизированным значением, которое policy может
  заполнить, заменить или отклонить.

Без такого различия optional source column и намеренная очистка становятся
неотличимы. Null grammar, blank handling и aliases принадлежат versioned source
contract и проходят strict preflight/contract tests.

#### Предварительная policy vocabulary

- `keep-existing` — никогда не менять заполненное active value;
- `fill-missing` — заполнить только canonical `NULL`;
- `replace-non-null` — заменить входным `VALUE`, но игнорировать входной `NULL`;
- `authoritative` — входной `VALUE` заменяет, входной `NULL` очищает;
- `reject-conflict` — отличающиеся non-null values делают row/delivery ошибкой
  согласно failure policy.

Названия и precedence ещё не утверждены. Вероятная иерархия — source default,
artifact override, column override — должна быть однозначной, value-free
проверяться при startup и не зависеть от порядка YAML entries.

#### Active-only boundary

Import merge получает один `asOf` и рассматривает только record с
`valid_until > asOf`. Если совпадение осталось лишь в history, incoming
observation создаёт новую lifecycle по правилам ADR-0020; значения из history в
неё не переносятся. Это не требует удаления существующей history или изменения
её retention: history просто не является input для import decision.

Если source-aware selection потребует помнить, кто установил effective field,
такая ownership/evidence должна жить только рядом с active lifecycle и не
превращать historical rows в merge authority.

### I-04 — present-row patch или authoritative complete snapshot

#### Сценарий

Configured source объявлен authoritative. В сегодняшнем CSV есть IP A с
`score=NULL`, поэтому policy явно очищает `score` у active A. Но active IP B,
ранее поступивший из того же source, в новом файле вообще отсутствует. При этом
B мог также подтверждаться document ingest или другим import source.

Authority отдельных присутствующих fields ещё не отвечает, означает ли
отсутствие всей строки отрицательное утверждение: «этого IOC больше не должно
быть в active set».

#### Вопрос заказчику

Должен ли source-of-truth import влиять только на rows, которые реально
присутствуют в CSV, или файл может быть полным snapshot списка, где отсутствие
row требует убрать её из active данных? Если omission является withdrawal,
должно ли оно затрагивать только observations этого import source или удалять
record даже при подтверждении из других источников?

#### Варианты

1. **Additive/patch delivery.** Обрабатываются только присутствующие rows;
   отсутствие IOC ничего не означает.
2. **Global complete snapshot.** Отсутствующая row удаляется из target artifact
   независимо от других sources.
3. **Source-scoped complete snapshot.** Omission снимает подтверждение только
   данного configured source; record остаётся active, если её поддерживает
   другая действующая authority.
4. **Configurable delivery semantics.** Default `observations`, а explicit
   `complete-snapshot` использует строго определённую source-scoped withdrawal
   model.

#### Текущая рекомендация

Default — вариант 1: каждая delivery является набором положительных
observations, отсутствие строки ничего не отзывает. Если complete snapshots
реально нужны, принять вариант 4, но разрешать snapshot только для стабильной
configured source identity и применять omission source-scoped.

Global delete опасен: один импорт не должен уничтожить active IOC, который
подтверждён другим каналом. Source-scoped withdrawal при этом не является
маленькой настройкой текущего TTL: ADR-0020 намеренно делает validity
record-scoped, а provenance не владеет отдельным active deadline. Snapshot mode
потребует нового решения о per-source assertions и вычислении active membership;
до него нельзя обещать корректное удаление отсутствующих rows.

#### Ответ и решение

Заказчик уточнил, что `authoritative` относится только к сопоставленным active
records, которые уже есть на нашей стороне. Если IP B отсутствует в
импортируемом CSV, import не должен удалять или модифицировать B, а также не
должен продлевать его TTL. Delivery обновляет присутствующие rows и добавляет
новые, если соответствующего active record ещё нет.

Принят вариант 1 без snapshot mode:

1. Каждая CSV delivery является набором положительных row-level `upsert`
   observations, а не декларацией полного membership списка.
2. `authoritative` — field merge policy для присутствующей и успешно
   сопоставленной row. Она не придаёт отсутствию row отрицательную семантику.
3. Active records, identity которых не представлена в delivery, полностью вне
   write set: их public values, lifecycle deadline, provenance/confirmation и
   export membership не изменяются.
4. Import не выполняет diff всего active artifact с содержимым файла и не
   создаёт implicit withdrawal, tombstone или expiry для omissions.
5. Complete-snapshot и source-scoped withdrawal не входят в текущий
   requirement. Их нельзя добавить позже как простое значение merge policy:
   это будет отдельное изменение business contract и lifecycle data model.

#### Архитектурные последствия

- Admission и merge можно выполнять как row-present command processing; importer
  не требуется загружать весь active artifact только для поиска omissions.
- File-level success не является подтверждением всех ранее известных rows
  данного source. TTL может затрагиваться только successful observations,
  реально присутствующие в delivery; точный confirmation contract будет
  рассмотрен отдельно.
- Идемпотентность delivery нельзя реализовывать через «сведение локального
  списка к файлу»: единственными кандидатами на изменение являются принятые
  input rows и их deterministic fan-out.
- Политики `fill-missing`, `authoritative` и другие из I-03 применяются после
  identity matching. Они не могут выбирать records, отсутствующие во входе.

### I-05 — изменение identity-bearing value и корреляция source record

#### Сценарий

В active `ip_list` уже есть IP A. В новой delivery появляется IP B. Варианты
`ip`, `mask`, URL/address и выбранного hash входят в canonical `row_key`,
поэтому B не совпадает с A и обычная field merge policy из I-03 к нему не
применима.

Даже если source row запрашивает тот же внешний `id`, этот `id` по BR-03
является только пожеланием занять `export_slot`. Slot может быть локально занят,
переназначен в ближайший свободный, позже переиспользован и имеет scope
`(profile, artifact)`, поэтому он не доказывает, что A и B — одна business
record.

Практический случай — оператор исправил ошибочный IOC в своей системе: вчера в
строке был A, сегодня в логически той же строке стал B. По принятому I-04
простое отсутствие A ничего не отзывает. Без отдельной стабильной source
identity import увидит только новую observation B и оставит A active до обычной
expiry.

#### Вопрос заказчику

Нужно ли в первой версии уметь распознавать такую замену как исправление одной
source record и явно отзывать прежний IOC A, либо изменение `ip`/`mask`/URL/hash
всегда означает новую observation B, а A остаётся без изменений и живёт по
обычным TTL-правилам?

#### Варианты

1. **Identity value defines the record.** B всегда новая observation; A не
   затрагивается. Requested `id` участвует только в slot allocation.
2. **Explicit source-record replacement.** Source contract предоставляет
   отдельный неизменяемый `source_record_key`; смена identity value по тому же
   key означает явную замену source assertion A на B.
3. **Correlate by imported `id`.** Одинаковый source `id` связывает A и B и
   разрешает замену canonical identity.

#### Текущая рекомендация

Для V1 принять вариант 1. Он следует текущей canonical identity, I-04 и
правилу, что `export_slot` не является row identity. Тогда identity-bearing
columns не получают `authoritative` update policy: новое значение создаёт новую
row, а старое значение не меняется.

Вариант 3 следует явно запретить как смешение namespaces. Если реальный source
умеет отдавать стабильный record key и business требует corrections, вариант 2
можно спроектировать отдельно. Но это не обычный update: потребуется определить
source-scoped assertion, поведение при подтверждении A другими sources,
закрытие lifecycle и судьбу slot. Глобально удалять A только из-за correction
одного source небезопасно.

#### Ответ и решение

Заказчик подтвердил, что изменение идентифицирующего значения само по себе
означает новые данные. Например, другой IP — это другой IOC, а не изменение
прежней canonical record.

Принят вариант 1:

1. После применимой import normalization другое canonical identity-bearing
   value создаёт новую observation с собственным `row_key`.
2. Прежняя active record не изменяется, не закрывается и живёт по обычным
   lifecycle/TTL-правилам.
3. Если новая identity уже существует в active set, к ней применяются обычные
   merge policies I-03; иначе создаётся новая lifecycle.
4. Source `id` остаётся только requested `export_slot` и никогда не связывает
   две разные identities.
5. `source_record_key` и специальный correction/replacement protocol не входят
   в requirement первой версии.

#### Архитектурные последствия

- Identity-bearing columns не поддерживают in-place `authoritative` update.
  Они участвуют в создании/matching `row_key`; field policies применяются к
  non-identity columns уже выбранной record.
- Сравниваются canonical values после применимых mapping/normalization rules.
  Изменение только внешнего написания, которое нормализуется в то же значение,
  не создаёт новый IOC.
- Slot conflict между прежней и новой identity разрешается allocation policy
  BR-03, а не превращается в update или replacement.

### I-06 — повторная поставка byte-identical CSV и TTL confirmation

#### Сценарий

Один физический файл неизбежно может быть обнаружен несколько раз: SMB
`CHANGE_NOTIFY`, local watch и periodic listing работают at-least-once. Все эти
срабатывания одной file occurrence должны сходиться в один import result.

Но оператор может через сутки снова положить в import-каталог CSV с абсолютно
теми же bytes. Это может быть либо случайный повтор, либо новая business
delivery, подтверждающая, что перечисленные IOC всё ещё актуальны. Во втором
случае успешные присутствующие observations потенциально должны продлить TTL,
даже если ни один public field не изменился.

Content hash сам по себе не различает эти случаи. Если хранить его как вечный
idempotency key, намеренная повторная поставка никогда не станет новым
подтверждением. Если считать каждое detection новой delivery, один неизменный
файл будет импортироваться снова на каждом reconcile.

#### Вопрос заказчику

Должен ли CSV с полностью идентичным содержимым, заново помещённый оператором
после завершения предыдущего импорта, считаться новой business delivery и
повторно подтверждать присутствующие IOC, либо одинаковые bytes всегда означают
дубль, который больше никогда не применяется?

#### Варианты

1. **Permanent content idempotency.** Одинаковый digest в рамках configured
   source всегда означает старую delivery; повтор не обновляет данные и TTL.
2. **Occurrence-aware delivery.** Повторные detection одной file occurrence
   идемпотентны, но новое помещение файла после terminal disposition образует
   новую delivery даже при тех же bytes.
3. **Time-window deduplication.** Одинаковые bytes считаются дублем только в
   течение настроенного периода, после которого автоматически становятся новой
   delivery.

#### Текущая рекомендация

Вариант 2. Он отделяет transport idempotency от business occurrence: ledger
гарантирует один effective import для одной admitted occurrence, а повторная
осознанная поставка может подтвердить актуальность IOC. Digest остаётся
integrity/content fact и частью защиты от повторной обработки, но не становится
вечной business identity.

Такой контракт потребует однозначного terminal file disposition: после
успешного импорта occurrence должна быть перемещена из intake, либо intake
должен иметь другой явный re-arm boundary. Иначе невозможно надёжно отличить
«тот же файл всё ещё лежит» от «оператор поставил его заново». Конкретный UX
успеха/ошибки будет следующим отдельным вопросом.

Вариант 3 не рекомендуется: временное окно создаёт зависимость business outcome
от скорости копирования, polling cadence и случайного времени повтора.

#### Ответ и решение

Заказчик подтвердил вариант 2: CSV, заново помещённый после предыдущей
завершённой обработки, является новой business delivery даже при полностью
одинаковых bytes. Если effective data также не изменились, присутствующие
active records по умолчанию получают TTL renewal. Возможность такого renewal
должна управляться флагом конфигурации.

Приняты следующие правила:

1. Идемпотентность имеет scope одной admitted file occurrence/import run, а не
   вечный scope content digest.
2. Повторные watch/notify/listing detections одной occurrence не создают новых
   confirmations и сходятся в один ledger result.
3. Новая occurrence после terminal/re-arm boundary создаёт новую delivery и
   новый import run, даже если content digest ранее встречался.
4. Для успешно сопоставленной active row без effective public change default —
   продлить lifecycle TTL.
5. Source contract получает configurable boolean policy с предварительным
   смыслом `renew-ttl-on-unchanged: true|false`; default `true`. Финальное имя
   будет утверждено вместе с configuration model.

#### Область TTL-флага

Флаг оценивается per accepted row после mapping, normalization, identity match
и merge, а не по равенству bytes всего файла:

- новая row всегда создаёт lifecycle с нормальным validity deadline;
- active row с effective public update является новым успешным подтверждением
  и renews TTL независимо от флага;
- active row без effective public update renews TTL только при
  `renew-ttl-on-unchanged=true`;
- rejected/invalid row, отсутствующая row и повторный detection той же delivery
  TTL не меняют.

Это позволяет byte-identical delivery подтвердить актуальность IOC, но не
делает content digest business identity. Если флаг выключен, import receipt и
row outcome всё равно фиксируют successful no-op; меняется только lifecycle
confirmation effect.

### I-07 — terminal file disposition и граница новой occurrence

#### Сценарий

После успешного import файл может продолжать лежать в local/SMB intake.
Periodic listing будет находить его снова, но ledger обязан считать это той же
terminal occurrence. Если оператор затем запишет byte-identical файл по тому же
пути, content digest не изменится, а filesystem metadata не всегда даёт
надёжную новую identity. Без явного re-arm boundary сервис не сможет доказать,
что это новая delivery из I-06.

Дополнительно permanent validation failure нельзя бесконечно retry-ить на каждом
reconcile, а transient I/O/DB failure нельзя сразу считать окончательно плохим
файлом. Нужна наблюдаемая судьба source bytes и понятное оператору разделение
retryable и terminal outcomes.

#### Вопрос заказчику

Может ли сервис после стабилизации переименовывать или перемещать входные файлы
из intake — в том числе на SMB share — чтобы явно claim-ить occurrence, после
успеха переносить её в archive, а после неисправимой ошибки в quarantine? Или
SMB/local source должен оставаться read-only и файлы нельзя трогать?

#### Варианты

1. **Consumer-managed inbox.** Сервис имеет rename/move permission: атомарно
   claim-ит stable file, successful delivery переносит в archive, permanent
   failure — в quarantine; transient failure остаётся в retry state.
2. **Read-only source.** Сервис только читает и ведёт ledger. Для повторной
   byte-identical delivery потребуется внешний уникальный occurrence marker,
   version или explicit operator reprocess command.
3. **Configurable disposition.** Managed mode используется там, где rename
   доступен; read-only mode требует отдельного явного re-arm contract и не
   обещает распознать перезапись identical bytes по тому же path автоматически.

#### Текущая рекомендация

Вариант 3 как capability contract, с вариантом 1 по умолчанию для специально
выделенного import inbox. Claim/archive/quarantine создают наиболее ясный
операционный UX и надёжную occurrence boundary. Исходные bytes следует
сохранять в archive/quarantine с import-run identity и retention policy, а не
удалять сразу.

Для read-only SMB нельзя молча угадывать новую delivery по mtime или notify:
это latency signals, а не надёжная business identity. Если move запрещён,
потребуется явный producer/operator protocol; его форму следует выбирать только
если такой deployment действительно нужен.

#### Ответ и решение

Заказчик разрешил сервису перемещать входные файлы и подтвердил ожидаемое
сходство с обычным механизмом чтения новых источников.

Принят consumer-managed lifecycle для специально выделенных import sources:

1. После stability check сервис получает ownership над occurrence через
   transport-appropriate atomic claim/rename.
2. Successful delivery переносится в `archive`/`done`, permanent failure — в
   `quarantine`/`failed` с безопасным diagnostic sidecar или эквивалентным
   result metadata; transient failure остаётся recoverable/retryable.
3. Terminal disposition создаёт re-arm boundary: новый файл в intake является
   новой occurrence, в том числе при прежнем content digest.
4. Исходные bytes не удаляются сразу. Archive/quarantine получают отдельную
   configurable retention policy; recovery не может удалить ещё нужный source.
5. Разрешение на move/delete относится только к явно configured import
   каталогам. Оно не расширяет authority существующих fetch/publish endpoints.

#### Сопоставление с действующим ingest

На application-уровне переиспользуется тот же coordination pattern:

```text
detect/list + optional signal
  -> stability
  -> durable occurrence/claim
  -> business use case
  -> durable terminal result
  -> archive | quarantine
  -> periodic reconcile/recovery
```

Это не означает запуск CSV через тот же document handler:

- local ingest уже реализует `inbox -> processing -> done|failed` через
  `FileSystemSourceLifecycle`; его file lifecycle и ledger/recovery semantics
  являются прямым reusable pattern;
- CSV import получает отдельный application use case для detection contract,
  mapping, routing, merge и slot intent, но вызывается тем же типом driving
  file-intake boundary;
- текущий SMB `RemoteFetchService` в v1 read-only: он идентифицирует remote
  object по `path + size + modifiedAt`, атомарно скачивает copy в local inbox и
  не перемещает remote source;
- managed SMB import поэтому не является уже готовым флагом fetch. Ему нужен
  transport-neutral source-lifecycle port и SMB adapter, который выполняет
  remote claim/rename, local staging/get и terminal remote archive/quarantine.

Таким образом, общий алгоритм и application invariants едины, а операции
ownership остаются деталями local filesystem и SMB adapters. Существующий
read-only fetch contract не меняется неявно.

### I-08 — file/row atomicity и partial-success policy

#### Сценарий

В delivery находится 10 000 rows. У одной строки неверный IP, отсутствует
required value либо возникает `reject-conflict`; остальные 9 999 корректны.
При `related-artifacts` одна input row также может подготовить записи сразу для
нескольких artifacts, и commit одного artifact может пройти до отказа другого.

Оператору нужно предсказуемо понимать, применена ли поставка и что произойдёт
после исправления/retry. Без явной policy один и тот же diagnostic может либо
отменить весь файл, либо оставить частично изменённую canonical БД.

#### Вопрос заказчику

Если отдельные rows CSV ошибочны или конфликтуют, должна ли вся delivery
отклоняться без единого canonical изменения, либо сервис должен импортировать
корректные rows, завершить файл с ошибками и позволить оператору позже повторно
поставить исправленные данные? То же решение должно определить, допустим ли
успех только части multi-artifact fan-out.

#### Варианты

1. **Atomic delivery.** Любая policy-significant row error отменяет все writes
   файла во все artifacts; source уходит в quarantine.
2. **Partial row success.** Корректные rows commit-ятся, ошибочные получают
   typed outcomes; delivery имеет terminal `COMPLETED_WITH_ERRORS`.
3. **Configurable failure policy.** Source contract выбирает `reject-delivery`
   либо `accept-valid`; structural file errors всегда отклоняют файл целиком.

#### Текущая рекомендация

Вариант 3 с безопасным default `reject-delivery`:

- ноль/несколько source-contract matches, неподдерживаемый dialect/encoding,
  malformed header и отсутствие required columns всегда являются file-level
  failure до durable business write;
- row validation и merge conflicts проходят единый pre-write failure-policy
  checkpoint;
- default не оставляет оператору скрыто частично применённую delivery;
- explicit `accept-valid` полезен для больших внешних feeds, но обязан дать
  row-level diagnostics, accurate counters и отдельный terminal outcome, не
  маскируя его под полный success.

Для `reject-delivery` обещание должно распространяться на deterministic
multi-artifact fan-out. Текущий writer commit-ит artifacts по одному и не даёт
такой cross-artifact atomicity; следовательно, принятие strict policy потребует
отдельного application unit-of-work port и одной JDBC transaction либо
эквивалентного recoverable staging protocol. Нельзя заявить atomic delivery,
оставив существующий последовательный writer без изменения.

#### Ответ и решение

Заказчик выбрал configurable failure policy и уточнил default: весь import
отклоняется только при critical error, например при структурной ошибке файла.
Ошибка, локализованная в одной записи, по умолчанию не должна отменять импорт
остальных корректных records.

Принят вариант 3 со следующей корректировкой рекомендации:

1. Default row policy — `accept-valid`, а не `reject-delivery`.
2. Source contract может явно выбрать strict `reject-delivery`, при котором
   policy-significant row error отменяет business writes всего файла.
3. Critical file-level errors не понижаются конфигурацией до partial success:
   delivery целиком отвергается до canonical write.
4. В `accept-valid` корректные rows применяются, rejected rows не меняют БД и
   получают typed diagnostics с безопасной row location и reason category.
5. Delivery с одновременно accepted и rejected rows получает отдельный
   terminal outcome `COMPLETED_WITH_ERRORS`, а не маскируется под полный success.
6. Если не принята ни одна row, canonical write отсутствует; outcome является
   полным rejection, даже если каждая ошибка формально row-local.

#### Предварительная taxonomy критичности

**File-level / critical:**

- source contract не найден либо detection неоднозначен;
- CSV structure нельзя однозначно разобрать: malformed quoting/record grammar,
  unsupported или ambiguous dialect/encoding;
- malformed/duplicate header либо отсутствуют contract-required columns;
- нарушен file-level integrity/size/security limit, поэтому доверять границам
  rows нельзя.

**Row-level / isolatable:**

- значение не проходит declared type/domain validation;
- отсутствует value, required только для конкретной row/route;
- provider/transform отклонил отдельное поле;
- identity row некорректна;
- возник merge conflict, для которого field policy выбрала `reject-conflict`.

Ошибки stability, claim, SMB/I/O, DB и crash/recovery не классифицируются как
«плохая row»: это operational failures с retry/recovery semantics. Invalid
startup configuration также не превращается в rejected delivery — строгий
preflight не должен запускать intake с недействительным contract.

#### Последствия partial success

- До write всё равно выполняются file contract validation и подготовка row
  outcomes; partial success не означает streaming writes во время parsing.
- Result обязан считать как минимум total/accepted/rejected/inserted/updated/
  renewed/unchanged rows и группировать diagnostics по стабильным codes.
- Повторно поставленный исправленный CSV является новой delivery: ранее
  принятые rows безопасно проходят merge/no-op, а исправленные могут быть
  добавлены. TTL effect для unchanged rows следует I-06.
- `COMPLETED_WITH_ERRORS` требует отдельного понятного file disposition/result
  UX; archive и quarantine не должны создавать впечатление, что partial writes
  отсутствовали. Это будет рассмотрено отдельно.

### I-09 — атомарность одной row при multi-artifact fan-out

#### Сценарий

При routing `related-artifacts` одна входная IP-row должна породить записи,
например, одновременно в `address_blacklist` и `ip_list`. Branch для primary
artifact корректен, а branch для related artifact не проходит required-field,
transform или `reject-conflict` policy.

I-08 разрешает продолжить обработку других input rows, но не определяет, можно
ли применить только успешную половину этой конкретной row. Тогда одна source
observation частично подтверждает IOC в одном списке и отсутствует в другом,
хотя оба результата были выведены одной routing decision.

#### Вопрос заказчику

Должна ли одна input row быть атомарной относительно своего fan-out: если хотя
бы один её target branch отклонён, не применять эту row ни в один artifact? Или
допустимо применить успешные branches этой row, отклонив только неуспешные?

#### Варианты

1. **Row-atomic fan-out.** Все prepared targets одной input row принимаются
   вместе либо вся row получает rejected outcome; другие rows продолжаются.
2. **Per-target partial success.** Каждый branch независим; одна input row может
   быть применена только в часть artifacts.
3. **Configurable fan-out atomicity.** Source contract выбирает behavior сверх
   file/row failure policy I-08.

#### Текущая рекомендация

Вариант 1 как единый invariant, без дополнительного config switch. Business
единицей partial success остаётся исходная row, а не внутренний artifact branch.
Это упрощает объяснение результата и не оставляет routing policy частично
выполненной.

Все branches должны быть prepared/validated до включения row в write set и
нести общий input-row correlation. Это решает logical validation failure.
Инфраструктурный отказ SQLite между commit-ами artifacts — отдельная проблема
transaction/recovery atomicity; её нельзя считать обычной rejected row и она
будет рассмотрена следующим вопросом.

#### Ответ и решение

Заказчик согласился с рекомендацией. Принят вариант 1 как обязательный
invariant, а не configurable policy:

1. Все target branches одной input row проходят mapping, normalization,
   validation, merge decision и slot feasibility до включения row в accepted
   write set.
2. Если любой branch получает rejected outcome, вся input row отклоняется и ни
   один её branch не изменяет canonical values, lifecycle/TTL, provenance,
   revision или export-slot state.
3. Другие input rows продолжают обработку согласно `accept-valid` I-08.
4. Row-level diagnostic сохраняет общий input-row correlation и branch-specific
   reasons, поэтому оператор видит первичную row и все причины отказа её routes.
5. Дополнительный переключатель per-target partial success не вводится.

#### Архитектурные последствия

- Anti-corruption/mapping layer должен сохранять стабильную internal
  `input_row_id`, не зависящую от artifact branch и не использующую public
  source values как coordination key.
- Prepared branches сначала группируются по `input_row_id`; write plans строятся
  только из полностью accepted groups.
- Slot reservation/intent для rejected group не может оставлять holes, claims
  или долговременное влияние на последующие rows.
- Logical row rejection полностью решается до DB commit. Atomicity при
  инфраструктурном сбое во время записи accepted set является отдельным
  run-level contract I-10.

### I-10 — ACID/recovery boundary accepted write set

#### Сценарий

После I-08/I-09 importer сформировал accepted write set: ошибочные rows уже
исключены, а каждая оставшаяся row имеет полностью успешный fan-out. Затем
canonical write обновляет `ip_list`, но процесс падает до обновления
`address_blacklist`, lifecycle confirmations или requested slot state.

Это не business row error: повтор может произойти после неизвестного partial
commit. Если каждый artifact commit-ится независимо, canonical truth временно
или постоянно отражает только часть уже принятой delivery. Если пытаться
компенсировать откатом, merge с существующими active values может сделать
обратную операцию неоднозначной.

#### Вопрос заказчику

Должны ли все business effects accepted write set одного CSV — во всех target
artifacts — commit-иться одной SQLite transaction, чтобы crash давал строго
«всё или ничего»? Или допустимы последовательные per-artifact commits с
durable resume, при которых после сбоя некоторое время видна только часть
delivery?

#### Варианты

1. **Single canonical transaction.** Все accepted rows, lifecycle effects и
   связанное slot state во всех artifacts commit-ятся атомарно в dataframe DB.
2. **Per-artifact saga/resume.** Каждый artifact commit независим; durable plan
   возобновляет оставшиеся steps после crash, а intermediate canonical state
   может быть частичным.
3. **Configurable consistency.** Source contract выбирает transaction либо
   saga behavior.

#### Текущая рекомендация

Вариант 1 как invariant, не конфигурация. Все business tables принадлежат одной
SQLite dataframe DB, поэтому ACID transaction является естественной и наиболее
понятной границей:

- `accept-valid` определяет, какие rows войдут в transaction, но не дробит
  accepted set на отдельные commits;
- unexpected DB/constraint failure откатывает весь accepted set и становится
  operational retry/recovery, а не новым row rejection;
- canonical inserts/updates, TTL confirmation и import-owned slot intent должны
  наблюдаться вместе;
- mutable CSV projection и terminal file move выполняются после commit и
  восстанавливаются идемпотентно по durable run state.

Service DB ledger и dataframe DB не получают distributed transaction. Между
ними остаётся действующая saga discipline: observation/import-run identity,
идемпотентный canonical command и recovery, способный определить или повторить
уже committed business step. Это не ослабляет атомарность business effects
внутри dataframe DB.

Текущий canonical writer commit-ит artifacts последовательно, поэтому target
design потребует отдельного framework-free application unit-of-work port и JDBC
adapter transaction. Размер/время transaction и SQLite contention нужно будет
ограничить admission limits; это отдельная capacity-развилка.

#### Ответ и решение

Заказчик согласился с рекомендацией. Принят вариант 1 как обязательный
consistency invariant:

1. Все accepted rows одной CSV delivery и все их target artifacts составляют
   один canonical write set.
2. Canonical inserts/updates, active lifecycle creation/confirmation, TTL
   effects, provenance, artifact revisions, projection generations и принятое
   import-owned slot state commit-ятся одной transaction в dataframe SQLite.
3. Любой unexpected DB/constraint failure откатывает transaction полностью.
   Такой failure является operational и retryable/recoverable, а не причиной
   превратить одну row в business rejection после начала commit.
4. Transaction boundary не конфигурируется per source и не ослабляется режимом
   `accept-valid`: этот режим только исключает rejected rows до начала write.
5. Canonical change event не публикуется до успешного commit.

#### Recovery boundary вне dataframe transaction

Service DB, filesystem/SMB lifecycle и CSV projection не включаются в
distributed transaction с dataframe DB:

- durable import-run/observation identity связывает шаги saga;
- canonical command идемпотентен для recovery после неизвестного результата;
- mutable projections сходятся из committed SQLite truth;
- source остаётся в `processing` до доказанного business commit и terminal run
  outcome, после чего архивируется;
- crash после DB commit, но до ledger transition/projection/archive, доводится
  вперёд и не запускает второй business effect.

Application-слой получает framework-free import unit-of-work port. JDBC adapter
владеет Spring/JDBC transaction и не протаскивает transaction framework в
domain/application. Действующий последовательный per-artifact writer нельзя
выдать за выполнение этого контракта: import path должен расширить storage
boundary явной multi-artifact operation.

### I-11 — точная семантика processing mode `as-is`

#### Сценарий

Source contract распознал CSV как `hashes`, mapped identity column в
`hash_md5`, но значение записано lower-case. Действующий canonical artifact
формирует hashes в upper-case. Аналогично mask/address может иметь
неcanonical host case, а URL — содержать defanged `hxxp[:]//`.

Фраза «импортировать как есть» допускает разные толкования:

- сохранить внешние bytes буквально, даже если они нарушают artifact invariant;
- незаметно выполнить refang/normalization и тем самым фактически включить
  часть processing pipeline;
- считать row уже подготовленной, проверить её как final artifact data и
  отклонить нарушение, если source mapping явно не объявил нужный transform.

Полностью отключить parsing/validation невозможно: в обоих режимах нужны
source-contract detection, CSV grammar, aliases, tri-state null, types,
identity construction, limits и merge policy.

#### Вопрос заказчику

Как должен вести себя `as-is`, если mapped value не соответствует canonical
формату target artifact: сохранить его буквально, автоматически исправить или
отклонить row, пока оператор явно не добавит normalization transform в source
mapping?

#### Варианты

1. **Literal bytes.** После CSV decoding mapped public values сохраняются без
   business validation/transformation.
2. **Implicit normalization.** Importer автоматически refang-ит и нормализует
   values даже в `as-is`.
3. **Final-row validation.** Mapped values считаются готовыми; importer
   проверяет canonical invariants, но не исправляет их неявно. Нужное изменение
   задаётся explicit mapping transform либо используется processed mode.

#### Текущая рекомендация

Вариант 3. `as-is` означает «не запускать document IOC processing», а не
«разрешить неканонические данные в SQLite»:

```text
CSV parse -> source mapping/declared transforms -> typed final-row validation
          -> identity/merge -> accepted write set
```

- `read document`, refang, regex extraction, source attribution и
  classification не выполняются;
- mapped derived fields (`url_match`, `host_match`, score и другие) принимаются
  как final values и валидируются, но не пересчитываются;
- common safety/schema checks нельзя отключить;
- normalization происходит только там, где source contract явно объявляет
  transform; иначе invariant violation является row-level error I-08;
- identity всегда строится из валидного canonical value, поэтому `as-is` не
  создаёт параллельные row keys только из-за некорректного case/defang form.

Вариант 1 способен нарушить artifact identity и export contract. Вариант 2
стирает различие между `as-is` и processed mode и делает outcome зависимым от
неявного набора стадий.

#### Ответ и решение

Заказчик согласился с рекомендацией. Принят вариант 3:

1. `as-is` принимает mapped values как уже подготовленную final artifact row,
   но не отключает schema, type, identity, safety и canonical invariant checks.
2. Importer не выполняет implicit refang, case normalization, classification
   или пересчёт derived fields.
3. Нужное преобразование объявляется явно в versioned source mapping. Например,
   lower-case hash допустим только при configured `upper` transform; без него
   row отклоняется как isolatable validation error по I-08.
4. Mapped `url_match`, `host_match`, score и другие non-identity values
   считаются final input values, проходят validation и затем merge I-03.
5. `as-is` остаётся default processing mode BR-04.

#### Архитектурные последствия

- Source-contract fingerprint включает ordered mapping transforms и version
  canonical validators; изменение semantics не может переиспользовать старое
  prepared receipt как эквивалентное.
- Common parsing/ACL строит typed tri-state row до mode selection. `as-is`
  направляет её в final-row validator, не в document extraction pipeline.
- Diagnostic обязан различать explicit transform failure и invariant violation;
  оба остаются row-level, пока не делают недостоверной структуру всего файла.
- Dry-run/result позднее должен показывать применённые transform keys без
  публикации чувствительных IOC values.

### I-12 — ownership derived fields в processing mode `processed`

#### Сценарий

Импортируемая `masks` row содержит identity value с path и одновременно
`url_match=u:hAS`, `host_match=NULL`. Текущий classification pipeline для этого
value вычисляет другой match code. Аналогично pipeline может иначе определить
IOC type, выбрать hash column, разделить `forbidden_url|forbidden_ip` или
направить observation в related artifacts.

Если `processed` после вычисления снова отдаёт precedence imported derived
columns, фактическая обработка не гарантирует действующие правила. Если молча
перезаписать их pipeline-значениями, оператор не узнает, что его CSV
противоречил результату.

При этом metadata вроде `score`, `source`, `description` и `threat_type` не
вычисляется classification pipeline из одного IOC value и может оставаться
import-provided business data. Public time fields пока не включаются в эту
категорию автоматически: их mapping требует отдельного явного решения.

#### Вопрос заказчику

В режиме `processed` должны ли pipeline-owned derived fields всегда
определяться текущими refang/normalization/classification/routing rules, даже
если CSV содержит другие значения? А импортируемые `score`, `source`,
`description` и подобная metadata при этом остаются inputs для merge policy?

#### Варианты

1. **Pipeline-authoritative derived fields.** Identity/type/routing/match и
   artifact-branch values вычисляет pipeline; imported copies не заменяют
   результат. Metadata остаётся mapped input.
2. **Imported precedence.** Pipeline заполняет только missing derived values, а
   явно указанный CSV value выигрывает.
3. **Per-field authority.** Любое derived поле может выбрать imported либо
   pipeline precedence в source contract.

#### Текущая рекомендация

Вариант 1 как смысл `processed` mode:

```text
mapped raw observation
  -> applicable refang/parse/normalize/classify/routing policies
  -> pipeline-owned derived artifact branches
  + imported business metadata
  -> final-row validation/merge
```

- imported derived columns либо запрещаются mapping preflight для processed
  source, либо объявляются assertion-only и при несовпадении дают diagnostic;
- они не участвуют в merge как competing values;
- imported metadata сохраняет tri-state и использует field policies I-03;
- для доверия уже вычисленным match/routing values оператор выбирает `as-is`,
  а не ослабляет `processed` per-field switches.

Это сохраняет один authority для классификации и не дублирует действующие
domain rules в importer. Точный список pipeline-owned и metadata fields должен
быть частью versioned artifact/import contract, а не эвристикой по имени
колонки.

#### Ответ и решение

Заказчик подтвердил рекомендованную границу: в `as-is` используются значения из
импортируемого файла, а в processed mode применяется действующий механизм
обработки проекта.

Принят вариант 1 для формирования incoming row:

1. В `processed` pipeline является единственным authority для IOC
   normalization/type, classification, routing и artifact-derived fields.
2. Imported copies pipeline-owned fields не переопределяют результат. Source
   contract может запретить их либо использовать как assertion с diagnostic при
   несовпадении.
3. Import-provided business metadata остаётся typed tri-state input и не
   исчезает только из-за включения processing.
4. В `as-is` pipeline-owned вычисление не запускается: validated mapped values
   из CSV становятся incoming final-row values.
5. Выбор между `as-is` и `processed` происходит до storage merge и не меняет
   canonical identity/history namespaces.

Фраза заказчика «в режиме as-is переопределяем на те, что в импортируемом
файле, если есть отличия» оставляет одну развилку: относится ли
«переопределяем» к построению incoming row либо также автоматически даёт ей
authority над уже заполненной active DB. Это вынесено в I-13, чтобы не отменить
неявно принятый в I-03 default `fill-missing`.

### I-13 — processing mode и merge authority active DB

#### Сценарий

Active canonical row уже содержит:

```text
score=10, description=curated
```

`as-is` CSV для того же `row_key` содержит:

```text
score=20, description=NULL
```

После I-11/I-12 importer однозначно строит incoming values `20` и explicit
`NULL`. Но I-03 по умолчанию использует `fill-missing`: existing non-null
сохраняется, а конфликт диагностируется. Только explicit `authoritative` policy
заменяет `10 -> 20` и очищает `description`.

Если сам `as-is` автоматически означает overwrite, processing mode неявно
переключает merge policy и прежний default перестаёт действовать.

#### Вопрос заказчику

Должен ли `as-is` автоматически делать все присутствующие mapped fields
authoritative над active DB, либо он только определяет incoming values, после
чего независимые merge policies I-03 решают, можно ли заменить существующие
значения?

#### Варианты

1. **Independent axes.** `as-is` выбирает imported incoming row; default
   `fill-missing` сохраняется. Для overwrite/clear оператор явно задаёт
   `authoritative` source/artifact/field policy.
2. **Implicit authority.** Само включение `as-is` заменяет все present active
   values imported `VALUE`/`NULL` независимо от merge defaults.
3. **Mode-specific merge default.** `as-is` по умолчанию authoritative, но
   отдельные fields могут вернуться к `fill-missing`/`keep-existing`.

#### Текущая рекомендация

Вариант 1. Processing и merge отвечают на разные вопросы и должны
комбинироваться явно:

```text
as-is + fill-missing    -> imported candidate, безопасное обогащение
as-is + authoritative  -> imported candidate, overwrite/explicit clear
processed + fill-missing -> pipeline candidate, безопасное обогащение
```

Это сохраняет уже выбранные defaults `as-is + target-only + fill-missing`, не
делает смену processing mode скрытой destructive настройкой и позволяет
назначать authority только нужным fields. UI/result должен показывать effective
processing и merge policies отдельно.

#### Ответ и решение

Заказчик согласился с рекомендацией. Принят вариант 1:

1. Processing mode определяет provenance incoming candidate: imported final
   values для `as-is` либо pipeline-derived values для `processed`.
2. Storage merge policy независимо решает effect candidate на совпавшую active
   record.
3. Defaults остаются `as-is + target-only + fill-missing`.
4. `as-is` не включает implicit overwrite/clear. Для этого source/artifact/
   field получает explicit `authoritative` policy.
5. Identity-bearing value по-прежнему следует I-05: другое identity value —
   новая record, а не field overwrite.

#### Архитектурные последствия

- Configuration model хранит processing, routing и merge в разных typed
  sections; shorthand, который неявно меняет соседнюю policy, не вводится.
- Preflight вычисляет effective merge policy с однозначной precedence и может
  показать её value-free в startup diagnostics/override report.
- Import result различает candidate conflict, retained existing value, applied
  replacement и explicit clear; одного общего `updated` недостаточно для
  объяснения authority behavior.
- Mode switch не становится destructive change сам по себе. Изменение merge
  authority имеет отдельный configuration diff и audit significance.

### I-14 — public time fields и lifecycle/TTL boundary

#### Сценарий

Экспортируемые schemas содержат public columns `time_first_seen` и
`time_last_seen`, которые сейчас остаются business fields со значением `NULL`.
Internal lifecycle использует отдельные timestamps/deadline, включая
`_valid_until_epoch_ms`; они не экспортируются как эти columns.

Импортируемый CSV той же структуры может содержать значения public time fields.
Если importer автоматически примет их как lifecycle facts, внешний файл сможет
сдвинуть TTL, подделать момент confirmation или вмешаться в history. Если
полностью запретить mapping, будущий интегратор не сможет сохранить реальные
business timestamps, даже когда они являются частью feed contract.

Кроме того, названия `first`/`last` подсказывают специальные min/max merge
semantics, но текущая schema пока не утверждает их grammar, timezone или
business meaning. Их нельзя вывести только из имён columns.

#### Вопрос заказчику

Нужно ли первой версии уметь импортировать значения public
`time_first_seen`/`time_last_seen`, или эти columns должны оставаться `NULL` и
игнорироваться даже при наличии в CSV? Если импорт нужен, предлагаю разрешать
его только явным opt-in mapping per source и никогда не связывать с TTL.

#### Варианты

1. **Not importable in V1.** Columns распознаются как часть schema, но values не
   становятся observations и public fields остаются `NULL`.
2. **Ordinary metadata.** Если columns присутствуют, они автоматически
   импортируются как обычные fields по общей merge policy.
3. **Explicit business-time mapping.** Default — `ABSENT`/не импортировать;
   source contract отдельно включает field, задаёт grammar/type/timezone и
   merge policy. Lifecycle/TTL authority остаётся недоступной.

#### Текущая рекомендация

Вариант 3, только если реальный integration use case требует эти значения;
иначе V1 может оставить mapping capability выключенной. Жёсткие инварианты:

- public time values никогда не задают observation time, detected/confirmed
  time, lifecycle start/end, `_valid_until_epoch_ms` или history timestamps;
- наличие/изменение public time field влияет на public row bytes/revision как
  обычное business update, но TTL renewal определяется I-06;
- отсутствие explicit mapping означает `ABSENT`, даже если header участвует в
  source-contract detection;
- до включения нужны точная canonical representation и отдельные merge
  semantics; `first=min`/`last=max` нельзя предполагать без решения заказчика.

#### Ответ и решение

Заказчик решил импортировать значения `time_first_seen` и `time_last_seen`, не
меняя никакого другого поведения этих fields.

Принят import public business-time values со следующей границей:

1. Source contract может mapping-ом включить оба public fields; mapped
   `ABSENT`/`NULL`/`VALUE` обрабатываются по общей tri-state model.
2. Storage merge использует policies I-03/I-13, включая default
   `fill-missing` и explicit `authoritative` overwrite/clear.
3. Специальные `earliest`/`latest`, min/max aggregation, datetime arithmetic
   или автоматическая timezone normalization не вводятся. Representation и
   validation остаются частью объявленного public/source schema contract.
4. В `as-is` mapped values являются final public candidates; в `processed` они
   остаются imported metadata, а не pipeline-derived fields.
5. Public time update меняет public row/revision/projection по общим правилам,
   но не задаёт и не изменяет internal lifecycle timestamps, observation time,
   `_valid_until_epoch_ms`, history либо retention.
6. TTL confirmation/renewal определяется фактом accepted observation и I-06,
   а не значением или отсутствием public time fields.

Дополнительный opt-in flag не требуется: явный field mapping в versioned source
contract и есть authority импортировать value. Если mapping отсутствует, field
имеет `ABSENT` и не меняется.

### I-15 — duplicate `row_key` внутри одной delivery

#### Сценарий

Один CSV может содержать один canonical IOC несколько раз. После
normalization две строки дают одинаковый `row_key`, но могут отличаться:

```text
row 10: id=5, ip=203.0.113.7, score=20, description=ABSENT
row 47: id=8, ip=203.0.113.7, score=30, description=analyst
```

Последовательная обработка сделает outcome зависимым от порядка rows. Особенно
опасны разные requested slots и explicit `NULL` против `VALUE`: правило
«последняя строка победила» превратит перестановку CSV в business change.

Полностью одинаковые дубли, напротив, не должны несколько раз подтверждать TTL
или создавать несколько branches/provenance occurrences в рамках одной
delivery.

#### Вопрос заказчику

Как поступать с несколькими input rows одного CSV, которые после обработки
имеют одинаковый canonical `row_key`: объединять совместимые значения и
отклонять конфликтующую группу, отклонять любые дубли либо применять rows по
порядку файла?

#### Варианты

1. **Deterministic coalescing.** Identical и complementary rows объединяются в
   одну observation; противоречащие values/slots отклоняют всю duplicate group.
2. **Reject every duplicate.** Любое повторение `row_key` является row-level
   ошибкой независимо от равенства values.
3. **Ordered first/last wins.** CSV row order определяет effective candidate.
4. **Configurable duplicate policy.** Source выбирает coalesce/reject/order.

#### Текущая рекомендация

Вариант 1 как invariant, без order-dependent режима:

- одинаковые `VALUE`, одинаковые `NULL` и `ABSENT + X` совместимы;
- разные non-null `VALUE`, а также explicit `NULL` против `VALUE`, являются
  конфликтом одной source delivery;
- requested slots совместимы, если равны либо присутствует только один;
  разные requested slots конфликтуют;
- compatible group образует один incoming candidate, один fan-out и не более
  одного TTL confirmation;
- conflicting group отклоняется целиком как одна logical row по I-09; остальные
  identities продолжаются при `accept-valid`, а strict `reject-delivery`
  повышает конфликт до отказа файла;
- diagnostic перечисляет безопасные row numbers и field/slot conflict codes,
  но outcome не зависит от порядка строк.

Grouping выполняется после mode-specific normalization/routing, но до merge с
active DB, slot allocation и canonical transaction. Иначе raw-различия,
которые дают одну canonical identity, обойдут duplicate contract.

#### Ответ и решение

Заказчик потребовал configurable policy минимум с двумя business-вариантами:
объединять повторяющиеся по ключу values либо оставлять input rows как есть.
Первоначальная рекомендация сделать coalescing единственным invariant не
принята.

Зафиксировано:

1. Duplicate policy принадлежит versioned source contract и не выводится из
   содержимого конкретного файла.
2. Preliminary mode `coalesce` группирует rows одного normalized `row_key` и
   строит один candidate до canonical merge.
3. Второй requested mode сохраняет отдельные input occurrences до storage
   boundary; его окончательное имя и effect требуют уточнения.
4. В любом mode canonical active table сохраняет `UNIQUE(row_key)`. Политика не
   разрешает несколько active canonical records одного artifact с одним key.
5. Original row numbers/outcomes не теряются даже при coalescing: result/audit
   должен показывать, какие input occurrences вошли в logical candidate.

#### Почему «оставить как есть» требует точного определения

Текущий обычный pipeline при включённом batch dedup сохраняет первую
`Indicator` occurrence, а последующие пропускает. Canonical storage независимо
использует unique `row_key`/keep-first. Для dataframe import есть два разных
способа сохранить входные occurrences, и они дают разный business outcome:

1. **`keep-first` (рекомендация).** Первая normalized row становится candidate;
   последующие rows того же key сохраняются как `DUPLICATE_SKIPPED` outcomes,
   но не меняют fields, slot intent или TTL. Это соответствует существующему
   batch-local dedup поведению.
2. **`apply-in-order`.** Каждая occurrence последовательно проходит merge к
   тому же canonical key. Тогда CSV row order определяет overwrite/clear,
   несколько requested slots конкурируют, а понятие одного TTL confirmation
   требует дополнительного правила.

Оба варианта «не объединяют fields», но только второй делает каждую повторную
row business command. Две canonical rows всё равно не появятся.

#### Уточняющий вопрос заказчику

Под «оставляем как есть» имеется в виду `keep-first`: первая строка по key
применяется, а последующие сохраняются в отчёте как пропущенные дубли? Или все
повторные rows нужно последовательно применять к одной canonical record в
порядке CSV?

Текущая рекомендация — `duplicate-policy: coalesce|keep-first`, default
`coalesce`; не вводить `apply-in-order`, поскольку он делает перестановку строк
скрытым business update. Названия tokens предварительные.

#### Итоговое решение

Заказчик согласился с рекомендацией. I-15 закрыт следующим contract:

1. Source contract явно выбирает `coalesce` либо `keep-first`; default —
   `coalesce`.
2. `coalesce` объединяет compatible occurrences по правилам выше в один
   candidate, один fan-out и не более одного TTL confirmation. Conflict
   отклоняет всю duplicate group как одну logical row.
3. `keep-first` выбирает первую occurrence в физическом порядке CSV после
   mode-specific normalization. Последующие occurrences получают отдельные
   `DUPLICATE_SKIPPED` outcomes, но не влияют на fields, requested slot, merge
   или TTL.
4. `apply-in-order` не поддерживается: порядок дублирующих rows не становится
   неявным языком обновления active record.
5. Policy входит в versioned/fingerprinted source contract, а canonical
   uniqueness по `row_key` сохраняется во всех режимах.

### I-16 — namespace импортируемого export slot

#### Сценарий

Импортируемый `id` по BR-03 является requested export slot, а не canonical ID.
Но export slot в текущем contract существует только в scope
`(profile, artifact)`. Поэтому `id=7` для одного artifact может быть свободен в
одном export profile и занят в другом. При `related-artifacts` одна source row
также может породить secondary branches, для которых то же число не имеет
исходного business-смысла. `address_blacklist` вообще не имеет external `id` и
не участвует в slot allocation.

#### Вопрос заказчику

Должен ли source contract явно указывать один export profile, в namespace
которого импортируемый `id` применяется только к primary artifact, тогда как
secondary related-artifact branches получают slots обычным механизмом?

#### Текущая рекомендация

Да: обязателен один explicit scope `(slot-profile, primary-artifact)` для
source contract, который маппит `id`:

- не выводить profile неявно даже при текущем единственном совпадении, чтобы
  добавление второго profile позднее не изменило смысл старого contract;
- preflight отклоняет отсутствующий/disabled profile, отсутствие primary
  artifact в profile, artifact без external ID и mapping `id` без однозначного
  slot scope;
- imported requested slot относится только к primary branch;
- related secondary branches используют штатную allocation policy;
- правила occupied-slot collision, направление поиска и tie-break будут
  отдельным следующим решением.

Tokens вроде `slot-profile` пока являются проектными именами, а не утверждённой
configuration schema.

#### Ответ и решение

Заказчик подтвердил сохранение действующего namespace: импортируемый requested
slot имеет scope строго внутри пары `(profile, artifact)`.

Зафиксировано:

1. Глобального export ID между profiles или artifacts не появляется.
2. Source contract с mapped `id` обязан однозначно назвать один
   `(slot-profile, primary-artifact)` scope.
3. Imported `id` влияет только на primary branch в этой паре.
4. Secondary branches, созданные `related-artifacts`, используют собственную
   штатную slot allocation, если их artifact вообще поддерживает external ID.
5. `address_blacklist` остаётся без external ID и вне export-slot registry;
   mapping импортируемого `id` для него является configuration error.

### I-17 — collision policy для requested export slot

#### Сценарий

После I-16 один imported `id` означает requested slot в конкретном
`(profile, artifact)`. Если slot свободен, требование BR-03 однозначно: новая
lifecycle должна получить именно его. Если slot уже принадлежит другой active
lifecycle, выражение «ближайший свободный» допускает разные результаты.

Например, requested slot `10` занят, а свободны `8`, `9`, `11` и `15`:

- numeric-nearest выберет `9`;
- upward-only выберет `11`;
- действующий обычный allocator новых lifecycles выберет наименьший свободный
  положительный slot, то есть `8`.

Следовательно, «ближайший к requested ID» и «как сейчас для новых данных» — не
один и тот же алгоритм. Кроме того, allocation по одной row за раз опасна:
fallback от занятого `10` не должен забрать свободный exact request `11` у
другой row той же delivery.

#### Вопрос заказчику

Какой fallback нужен при занятом requested slot: численно ближайший к
импортированному ID, ближайший только в сторону увеличения либо действующий
smallest-free-positive allocator независимо от requested ID?

#### Варианты

1. **Numeric nearest.** Минимальный `abs(candidate - requested)`; при равном
   расстоянии нужен tie-break между меньшим и большим slot.
2. **Upward only.** Первый свободный slot больше requested; проще объяснить,
   но игнорирует близкие holes ниже.
3. **Current normal allocation.** После занятого exact request использовать
   наименьший свободный положительный slot во всём namespace. Максимально
   переиспользует существующий механизм, но может далеко отнести imported ID.

#### Текущая рекомендация

Учитывая требование строго сохранять source IDs, использовать вариант 1:
numeric-nearest с tie-break в пользу меньшего положительного slot. Однако план
всей delivery должен строиться детерминированно внутри одной transaction:

1. сначала зарезервировать все непротиворечивые exact requests, свободные в
   registry до delivery;
2. только затем распределять fallback slots, чтобы collision row не отняла
   точный ID у другой row;
3. collision rows планировать по `(requested slot, canonical row_key)`, а не по
   случайному порядку чтения/параллелизма;
4. учитывать в nearest-search active assignments, exact reservations и уже
   выбранные fallback slots;
5. положительная область начинается с `1`; отсутствие доступного slot является
   row error либо delivery error согласно I-08;
6. повтор одного requested slot у разных rows внутри самого CSV рассмотреть как
   отдельную source-integrity ошибку, а не как существующую DB collision.

Этот вариант расширяет текущий smallest-free allocator для import intent, но не
меняет allocation обычных новых данных. Гарантировать одновременно exact ID для
свободных requests и абсолютное сохранение относительного порядка при занятом
slot математически невозможно; exact request имеет приоритет.

#### Ответ и итоговое решение

Заказчик выбрал сохранение действующего allocation behavior. Предыдущая
рекомендация numeric-nearest не принята: численная близость к уже невыполнимому
requested ID не оправдывает второй алгоритм и расхождение с обычным поступлением
новых данных.

Зафиксировано:

1. Если requested slot свободен в его `(profile, artifact)` namespace, import
   назначает именно его.
2. Все свободные exact requests accepted delivery имеют приоритет перед
   fallback allocation. Это является частью import intent, а не новым общим
   allocator algorithm.
3. Если requested slot занят active assignment, запись теряет slot preference
   и передаётся действующему normal allocator: получает наименьший свободный
   положительный slot.
4. Несколько collision rows распределяются в том же deterministic order, что
   обычные новые lifecycles; точный технический key фиксируется на architecture
   stage с сохранением текущего lifecycle-order contract.
5. Import не вводит numeric-nearest или upward-only search и не меняет
   allocator для обычного ingestion/export.
6. После collision абсолютное сохранение source ID и относительного порядка
   source IDs не гарантируется. Это ожидаемое следствие: occupied requested ID
   уже не может быть соблюдён, а registry consistency имеет приоритет.

### I-18 — duplicate requested slot внутри одной delivery

#### Сценарий

Две разные canonical identities одного импортируемого CSV могут объявить один
requested slot:

```text
id=42; ip=192.0.2.1
id=42; ip=192.0.2.2
```

Такой файл не мог быть корректной export projection данного
`(profile, artifact)`: slot там уникален. Это отличается от I-17, где slot уже
занят ранее существующей active lifecycle. Здесь противоречие создано внутри
одной source delivery, и выбор одного «победителя» по row order скрыл бы
повреждение входных данных.

Duplicate rows одного `row_key` сначала регулируются I-15. I-18 применяется,
когда после duplicate-row policy разные accepted candidates всё ещё требуют
один slot.

#### Вопрос заказчику

Нужно ли отклонять обе конфликтующие rows, сохраняя импорт остальных valid rows
при default `accept-valid`, либо назначить exact slot одной row, а вторую
отправить в normal fallback allocator?

#### Текущая рекомендация

Отклонять всю conflicting-slot group, не выбирая победителя:

- обе rows получают linked `DUPLICATE_REQUESTED_SLOT` outcomes с безопасными
  row numbers;
- остальные независимые rows продолжают импортироваться при `accept-valid`;
- `reject-delivery` по I-08 повышает конфликт до отказа всего файла;
- exact/fallback allocation начинается только после удаления conflicting
  groups из accepted write set;
- автоматически исправлять внутренне противоречивый source через fallback
  нельзя, поскольку тогда успешный import скроет нарушение уникальности
  исходного export-shaped dataframe.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-18 закрыт:

1. Разные accepted candidates, запросившие один slot внутри delivery,
   образуют одну conflicting-slot group.
2. Все candidates группы отклоняются; exact slot не отдаётся одному
   произвольно выбранному победителю, а остальные не переводятся в fallback.
3. При default `accept-valid` остальные независимые rows продолжают import.
4. При `reject-delivery` конфликт отклоняет весь файл согласно I-08.
5. Duplicate requested slot диагностируется после I-15 coalesce/keep-first и
   до exact reservation, fallback allocation и canonical transaction.

### I-19 — zero/ambiguous source-contract recognition

#### Сценарий

Имя файла не участвует в распознавании по BR-02. Несколько configured source
contracts могут случайно или после config evolution принять один header:

```text
contract A: required aliases [indicator, score]
contract B: required aliases [value, score]
CSV header:  value;score;description
```

Если `value` также объявлен alias для `indicator` в contract A, совпадут оба.
Выбор первого по порядку config сделает перестановку YAML business change.
Scoring «наиболее похожего» contract тоже нестабилен: добавление нового
optional field или новой версии может без ошибки перенаправить прежний feed.

Проверка значений первых N rows не решает authority: пустой CSV, однородная
выборка или поздняя отличающаяся row могут дать другой result для той же schema.
Semantic row validation нужна после выбора contract, но не должна тайно
определять target artifact.

#### Вопрос заказчику

Должен ли import принимать файл только при ровно одном совпавшем source
contract, а при нуле или нескольких matches отклонять/quarantine всю delivery?

#### Варианты

1. **Exact-one contract.** Zero и ambiguous match являются разными critical
   file errors; никакого first-match или scoring fallback.
2. **Configured priority.** При нескольких matches побеждает contract с большим
   priority; удобно для migrations, но скрывает overlaps и делает config order
   или priority частью routing semantics.
3. **Content heuristic.** Target выбирается по sample rows; гибко, но result
   зависит от конкретных данных и sampling boundary.

#### Текущая рекомендация

Вариант 1 как invariant:

- recognition использует нормализованный header, configured aliases,
  required/forbidden columns и при необходимости explicit in-band
  discriminator column/value;
- порядок columns и имя файла не участвуют;
- zero match даёт critical `NO_SOURCE_CONTRACT` и quarantine;
- multiple matches дают critical `AMBIGUOUS_SOURCE_CONTRACT`, безопасно
  перечисляющий candidate contract IDs, и quarantine;
- priority/first-match/content scoring не используются для устранения
  неоднозначности;
- если два feeds имеют одинаковую внешнюю schema, оператор обязан добавить
  различимый in-band marker либо развести их по отдельным import sources с
  непересекающимися allowed-contract sets; directory/source scope может сузить
  множество разрешённых contracts, но не имя конкретного файла;
- startup preflight по возможности обнаруживает пересекающиеся декларации, но
  runtime exact-one остаётся authority, поскольку не все semantic intersections
  можно доказать статически.

Версионирование и cutover старой/новой версии одного contract будет уточнено
отдельно; две одновременно active версии не могут молча иметь одинаковый
recognition signature.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-19 закрыт:

1. Delivery может продолжить processing только после ровно одного match среди
   разрешённых для import source contracts.
2. Zero match и ambiguous match являются разными critical file errors и ведут
   к quarantine всей delivery.
3. Порядок declarations, priority, first-match и content scoring не выбирают
   target artifact.
4. Для одинаковых внешних schemas нужен explicit in-band discriminator либо
   разные import sources с непересекающимися allowed-contract sets.
5. Startup preflight выявляет доказуемые overlaps, но runtime exact-one check
   остаётся обязательным correctness boundary.

### I-20 — versioned contract и retry/cutover semantics

#### Сценарий

Source contract со временем изменяется: добавляется alias, меняется mapping,
null grammar, duplicate policy либо processing mode. Delivery была распознана
как `feed-a/v1`, затем processing упал и ожидает retry. До retry оператор
развёртывает `feed-a/v2`.

Если retry заново распознает тот же файл текущей конфигурацией, одна physical
delivery может сначала частично исполняться по v1, а после restart — по v2.
Даже без частичного canonical commit изменятся validation outcomes, row keys,
merge authority или routing. Idempotency по bytes этого не предотвращает:
важны bytes вместе с processing-contract fingerprint.

Также две версии могут быть одновременно нужны на migration interval. При
различимых signatures exact-one из I-19 выберет версию безопасно. При
одинаковом signature, но другой mapping автоматически определить intended
version невозможно.

#### Вопрос заказчику

Должна ли delivery после первого успешного recognition навсегда закрепляться за
конкретными `(contract-id, version, fingerprint)` на всех retry/recovery, либо
каждая попытка должна интерпретировать файл по текущей конфигурации?

#### Варианты

1. **Pinned immutable contract.** Retry использует ровно распознанную version и
   fingerprint; если после restart она недоступна, delivery не
   переинтерпретируется и требует operator recovery.
2. **Always current config.** Retry заново выполняет recognition/mapping;
   проще rollout, но одна delivery меняет business meaning во времени.
3. **Persist full config snapshot.** Ledger хранит полное executable описание;
   улучшает автономный resume, но усложняет schema/security и всё равно не
   сохраняет удалённые provider/transform implementations после code upgrade.

#### Текущая рекомендация

Вариант 1:

- source contracts имеют стабильный `contract-id`, явную version и content
  fingerprint всех recognition/mapping/policy настроек;
- после exact-one recognition ledger фиксирует contract coordinates до первого
  business write;
- retry/recovery той же delivery обязан найти exact fingerprint в текущем
  validated contract catalog;
- отсутствие exact version/fingerprint даёт durable
  `CONTRACT_VERSION_UNAVAILABLE`: никакого автоматического запуска по v2;
- operator либо возвращает совместимый v1 contract и продолжает delivery, либо
  явно завершает старую occurrence и повторно подаёт файл как новую delivery по
  v2;
- новые deliveries используют active catalog нового application start;
- версии с различимыми signatures могут coexist; версии с одинаковым
  recognition signature не могут одновременно быть recognition-enabled в
  одном import source;
- config deployment должен учитывать незавершённые pinned deliveries. Полный
  executable config snapshot в DB в первой версии механизма не хранится.

Это решение не требует hot reload: граница применения нового catalog и правила
restart/config activation будут уточнены отдельно.

#### Ответ и решение

После упрощения сценария заказчик согласился с рекомендацией. Business contract
I-20 формулируется без требования оператору управлять внутренними fingerprints:

1. Retry/recovery продолжает ту же delivery по тем правилам, по которым она
   была первоначально принята.
2. Изменение active configuration не переинтерпретирует незавершённую delivery.
3. Чтобы применить новые правила к тем же bytes, оператор явно создаёт новую
   delivery после terminal completion старой occurrence.
4. Технические `contract-id`, version и fingerprint являются механизмом
   обеспечения этого результата; их точная persistence/config UX определяется
   на architecture stage.
5. Если старые правила невозможно восстановить после restart, система не
   угадывает новый meaning: occurrence получает явный recoverable operator
   outcome, а не silently запускается по новому contract.

### I-21 — CSV representation of `ABSENT`, `NULL` и empty value

#### Сценарий

I-03 различает три incoming states:

- `ABSENT` — поле не предоставлено и existing value не меняется;
- `NULL` — поле предоставлено как пустое; `authoritative` очищает existing
  value;
- `VALUE` — передано конкретное значение.

Но CSV не имеет встроенного `NULL`. В текущей export projection null
сериализуется configured literal `NULL`, тогда как non-null values
кавычатся (`QuoteMode.ALL_NON_NULL`). Поэтому round-trip текущего dataframe
может различить unquoted null literal и обычное строковое значение. Пустая
ячейка `;;` при этом не обязана означать то же самое, что `NULL`.

Пример для existing `description=old` в authoritative mode:

```text
description отсутствует в source schema -> оставить old
description=NULL                    -> очистить
description=                        -> пустая строка либо ошибка, но не скрытая очистка
```

Если считать blank cell `NULL`, случайно оставленная пустая ячейка очистит DB.
Если считать её `ABSENT`, невозможно импортировать business empty string и
round-trip grammar станет зависеть от неявного соглашения.

#### Вопрос заказчику

Согласны ли разделить состояния явно: отсутствующее/unmapped поле означает
`ABSENT`, configured null literal означает `NULL`, а пустая CSV-ячейка не
считается неявным `NULL`?

#### Текущая рекомендация

Да, с export-compatible safe defaults:

1. Отсутствующий optional header либо отсутствие mapping для field даёт
   `ABSENT` для всех rows.
2. Configured null literal, по умолчанию `NULL`, даёт explicit `NULL`.
   Quoting/case/whitespace rules должны быть точными и входить в source-contract
   fingerprint, чтобы строка `"NULL"` могла остаться literal VALUE при
   export-compatible dialect.
3. Empty parsed cell даёт `VALUE("")`, после чего field validator либо
   разрешает empty business value, либо отклоняет row. Она не очищает DB и не
   превращается в `ABSENT` молча.
4. Short/long row с количеством cells, не равным выбранному header, является
   isolatable row structure error, а не способом передать `ABSENT`.
5. При необходимости selective patch внутри mapped column source contract
   может объявить отдельный explicit absent literal. Он не должен совпадать с
   null literal или допустимым business value; default absent literal
   отсутствует.
6. Delimiter, quote, escape, charset, BOM, null literal и trim policy являются
   частью versioned CSV dialect; auto-detection этих параметров не меняет
   semantic state cells.

Названия configuration fields пока не утверждены. Вопрос I-21 определяет
business grammar, а конкретный Apache Commons CSV contract будет проверен
round-trip tests на architecture/implementation stage.

#### Уточнение заказчика и итоговое решение

Заказчик поставил под сомнение отличие blank cell от `NULL`. После повторной
оценки первоначальная рекомендация признана избыточно строгой: explicit
`authoritative` merge policy уже является защитной границей для очистки
existing value, а в default `fill-missing` incoming `NULL` не удаляет
существующие данные.

I-21 закрыт следующим образом:

1. Отсутствующий optional header либо отсутствие mapping означает `ABSENT`.
2. Empty parsed cell по умолчанию означает `NULL`.
3. Configured null literal, default `NULL` для совместимости с текущим export,
   также означает `NULL`.
4. При `authoritative` оба представления `NULL` очищают existing field; при
   остальных merge policies effect определяется уже принятым I-03 contract.
5. Если конкретному source нужен настоящий `VALUE("")` либо per-row `ABSENT`,
   он обязан объявить их отдельной однозначной grammar. Эти расширения не
   меняют default blank-as-null.
6. Short/long row остаётся structural row error, а не способом выразить
   `ABSENT`.

### I-22 — CSV dialect/charset declaration versus auto-detection

#### Сценарий

Независимость от порядка/aliases columns не означает, что CSV можно безопасно
прочитать без dialect. Один и тот же byte stream по-разному разбирается при
`,` или `;`, разных quote/escape rules и UTF-8 против Windows-1251. Ошибочная
эвристика может не упасть, а получить другой header или испортить Cyrillic и
business values.

Текущая export configuration использует `;`, quote `"`, null literal `NULL` и
UTF-8. Это естественный default для round-trip. При этом внешние feeds могут
легитимно использовать другой delimiter или charset, что требует declarative
настройки, но не обязательно runtime guessing.

#### Вопрос заказчику

Должен ли каждый source contract иметь детерминированный declared CSV dialect
и charset, либо сервис должен автоматически угадывать delimiter/encoding для
каждого файла?

#### Варианты

1. **Declared dialect.** Resolved settings входят в fingerprint contract;
   неверные bytes/dialect дают явную file error.
2. **Auto-detection.** Сервис угадывает charset и delimiter; operator config
   проще, но один файл может получить другое толкование после изменения
   detector/library или на неоднозначной выборке.
3. **Bounded candidates.** Source объявляет несколько полных dialect variants,
   каждая участвует в exact-one recognition как отдельная contract version.

#### Текущая рекомендация

Варианты 1 и при необходимости 3, без unrestricted auto-detection:

- omitted source settings resolve к export-compatible defaults:
  delimiter `;`, quote `"`, null literal `NULL`, charset UTF-8;
- source может явно задать Windows-1251, comma/tab delimiter и другие
  поддерживаемые параметры; resolved values входят в version/fingerprint;
- несколько допустимых dialects описываются конечным набором declarative
  variants и должны дать exact-one result по I-19;
- UTF-8 BOM можно терпимо удалить как явно разрешённый nonsemantic marker;
- malformed/unmappable byte sequence отклоняет delivery, а не silently
  заменяется символом replacement character;
- unclosed quote или невозможность построить header является critical file
  error; row-width mismatch после корректного parse остаётся isolatable row
  error по I-08/I-21;
- detector/library update не должен менять meaning уже pinned delivery по
  I-20.

Так оператор получает вариативность через configuration, но каждая конкретная
delivery всё равно имеет воспроизводимый parse contract.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-22 закрыт:

1. Каждая source-contract version имеет resolved declared CSV dialect и
   charset; unrestricted runtime auto-detection не используется.
2. Defaults совместимы с текущим export: UTF-8, delimiter `;`, quote `"`, null
   literal `NULL`; UTF-8 BOM допускается как nonsemantic marker.
3. Иные charset/delimiter/quote/escape policies задаются декларативно.
4. Несколько допустимых formats выражаются bounded contract variants и
   подчиняются exact-one recognition из I-19.
5. Invalid byte sequence, unclosed quote и unreadable header являются critical
   file errors без silent replacement; isolatable row-shape errors продолжают
   подчиняться I-08.

### I-23 — ordering одновременно готовых import deliveries

#### Сценарий

Две независимые CSV delivery стабилизируются почти одновременно и содержат
один canonical `row_key`, но разные authoritative values:

```text
delivery A: score=20
delivery B: score=90
```

Local watch и SMB `CHANGE_NOTIFY` являются только hints и не задают business
order. Modification time удалённого файла, порядок directory listing и момент
завершения worker thread также ненадёжны. При parallel apply итоговое значение
будет зависеть от timing: какая transaction commit-нулась последней.

Даже при serial executor нужен durable ordering key. In-memory queue после
restart может построиться иначе. С другой стороны, строгий порядок означает
head-of-line blocking: если A находится на retry/backoff, B не может обогнать A,
пока A не достигнет terminal outcome.

#### Вопрос заказчику

Должны ли все import deliveries применяться последовательно в durable claim
order, включая ожидание terminal outcome более ранней delivery, либо допустим
timing-dependent порядок commits ради большей пропускной способности?

#### Варианты

1. **Strict durable order.** Один глобальный import apply lane; claim получает
   monotonic sequence, retry сохраняет место, более поздние deliveries ждут.
2. **Ready-first serial.** Одновременно исполняется одна delivery, но pending
   retry можно обогнать; меньше blocking, итог уже не равен claim order.
3. **Parallel apply.** Несколько imports исполняются одновременно; SQLite
   сериализует физические writes, но observed business order определяется
   runtime timing.
4. **Per-source order.** Внутри source строго, между local/SMB sources
   параллельно; конфликт между sources остаётся timing-dependent.

#### Текущая рекомендация

Вариант 1 как safe default первой версии:

- claim transaction присваивает immutable monotonic `delivery-sequence`;
- detection/stabilization разных files могут идти независимо, но business
  apply/commit следует sequence;
- retry/recovery не создаёт новый sequence и не позволяет следующей delivery
  обогнать текущую;
- bounded retry заканчивается terminal success/rejection/quarantine, после
  чего lane продолжает работу, поэтому blocking не бесконечен;
- result/audit всегда показывает sequence и позволяет объяснить final merge;
- concurrency knob для parallel preparation можно рассмотреть позднее, но он
  не должен нарушать ordered commit;
- interactions между dataframe import и обычным document ingestion требуют
  отдельного precedence решения: I-23 пока упорядочивает только import
  deliveries.

Это соответствует текущему conservative daemon shape с ingestion concurrency
`1` и ограничению SQLite на writer concurrency, но превращает порядок в durable
business contract, а не побочный эффект одного потока.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-23 закрыт:

1. Все dataframe import deliveries одного service instance получают общий
   durable monotonic claim sequence.
2. Business apply и commit выполняются строго последовательно по sequence.
3. Retry/recovery сохраняет sequence и блокирует обгон до terminal outcome.
4. Detection, stability checks и transport notifications не становятся
   authority порядка и могут выполняться независимо.
5. Bounded retry/terminal quarantine ограничивают head-of-line blocking.
6. Ordered sequence и outcomes входят в audit/report contract.

### I-24 — authority import против обычного document ingestion

#### Сценарий

Обычный document ingestion и новый dataframe import могут наблюдать один
canonical `row_key` почти одновременно. Например, document pipeline подготовил
`score=20`, а import содержит `score=90`.

I-23 упорядочивает import deliveries между собой, но не определяет приоритет
между разными ingress paths. Сам факт, что observation пришла из CSV import,
можно трактовать как высший authority — либо оставить transport-neutral model,
где authority задаёт merge policy source contract.

При declarative `authoritative` результат не зависит от порядка относительно
обычного keep-first ingestion: import заменит ранее записанное значение, а
последующее обычное observation не отменит его. При default `fill-missing` два
разных non-null values сохраняют первый accepted value; тогда фактический
canonical commit order остаётся наблюдаемой частью результата.

#### Вопрос заказчику

Должен ли любой dataframe import автоматически иметь приоритет над обычным
document ingestion, либо он получает право overwrite/clear только когда его
source contract явно объявляет соответствующую merge authority?

#### Варианты

1. **Policy-owned authority.** Import path не имеет встроенного приоритета;
   `fill-missing`, `authoritative`, `reject-conflict` и другие policies дают
   требуемый outcome.
2. **Import always wins.** Любой CSV считается более доверенным, независимо от
   configured merge policy.
3. **Global temporal order.** Все import/document observations входят в одну
   durable очередь, а последний/первый sequence определяет values; transport
   timing становится business authority.

#### Текущая рекомендация

Вариант 1:

- adapters local/SMB/document не определяют trust или overwrite rights;
- import default `fill-missing` остаётся безопасным и не заменяет existing
  non-null value;
- explicit `authoritative` source получает заявленное право replace/clear
  независимо от того, была ли ordinary observation до или после него;
- ordinary ingestion сохраняет действующий keep-first/confirmation behavior;
- если два configurable sources оба могут быть authoritative для одного field,
  contract должен потребовать explicit source precedence либо
  `reject-conflict`, а не решать спор thread timing;
- canonical transaction/audit фиксирует фактическое observation и applied
  policy; отдельная глобальная очередь между всеми ingress paths не вводится
  без подтверждённого business requirement.

Таким образом import использует тот же canonical aggregate boundary, но не
получает скрытых привилегий только из-за формата доставки.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-24 закрыт:

1. Dataframe import не получает implicit priority над document ingestion.
2. Overwrite/clear authority принадлежит явно выбранной source merge policy.
3. Default `fill-missing` и ordinary keep-first сохраняют свои contracts;
   transport не отменяет их.
4. Несколько authoritative sources требуют explicit precedence либо
   reject-conflict policy; thread timing не считается trust signal.
5. Между всеми ingress paths не вводится общий total-order coordinator без
   отдельного подтверждённого requirement.

### I-25 — manual replay terminal delivery

#### Сценарий

Delivery завершилась terminal outcome, например `COMPLETED_WITH_ERRORS`: valid
rows уже committed, invalid rows отражены в отчёте, source перемещён в archive
или quarantine. Оператор исправил configuration либо хочет повторить файл.

Есть три разных действия, которые легко смешать:

- duplicate file event/recovery той же незавершённой occurrence — должен быть
  idempotent и не создавать второй import;
- автоматический retry non-terminal occurrence — продолжает ту же delivery по
  I-20;
- сознательный replay после terminal outcome — новое business наблюдение по
  уже принятому I-06 contract.

Если переоткрыть terminal row ledger, audit потеряет факт прежнего результата,
sequence/outcomes изменятся задним числом, а crash recovery не сможет отличить
повтор event от operator command. Если повторять только rejected rows, нужно
хранить executable row snapshot и соединять новый effect со старой частично
успешной transaction.

#### Вопрос заказчику

Должен ли ручной replay terminal delivery всегда создавать новую delivery и
повторно обрабатывать весь файл, сохраняя ссылку на исходную occurrence, либо
нужно переоткрывать старую delivery/повторять только rejected rows?

#### Варианты

1. **New full delivery.** Terminal outcome immutable; replay получает новый
   sequence и `replay-of`, весь файл проходит current accepted contract.
2. **Reopen terminal delivery.** Старый ledger row возвращается в processing;
   проще ID, но история и idempotency semantics становятся mutable.
3. **Rejected rows only.** Replay создаёт child attempt только для прежних
   failures; экономит работу, но требует durable typed row snapshots и сложной
   связи с новым config/merge state.

#### Текущая рекомендация

Вариант 1 для v1:

- terminal delivery никогда не переоткрывается и её report immutable;
- explicit replay создаёт child delivery с новым ID, новым durable sequence и
  causation link `replay-of=<old-delivery-id>`;
- replay повторно читает весь source file и использует contract catalog,
  действующий для новой delivery; это сознательное применение новых правил, а
  не retry по I-20;
- уже accepted rows проходят обычные merge/no-op/TTL rules; при default
  `renew-ttl-on-unchanged=true` новый replay действительно является новой
  confirmation, что должно быть видно operator-у до запуска;
- автоматические duplicate notifications и crash recovery остаются
  idempotent внутри старой non-terminal occurrence и не создают replay;
- исправленный файл с другими bytes также является новой delivery; link на
  старую occurrence полезен, но не становится identity/dedup key;
- selective rejected-row replay можно добавить позднее только при отдельной
  доказанной operational потребности.

Это сохраняет append-only audit и не требует превращать import ledger в event
sourcing system.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-25 закрыт:

1. Terminal delivery и её outcome/report immutable.
2. Manual replay создаёт новую полную delivery с новым ID/sequence и causal
   link на исходную occurrence.
3. Replay читает весь файл и использует current contract как новое business
   observation; accepted/no-op rows снова проходят merge/TTL rules.
4. Non-terminal retry/recovery остаётся той же occurrence и не создаёт replay.
5. Selective rejected-row replay исключён из v1 scope.

### I-26 — terminal file fate и operator-facing delivery report

#### Сценарий

При default `accept-valid` одна delivery может commit-нуть 9 900 rows и
отклонить 100. Это не success без оговорок и не failure для automatic retry:
повтор файла уже является новой delivery и может подтвердить TTL ранее
принятых rows по I-25.

Оператору нужно понять:

- какой файл/occurrence обработан и каким contract;
- какие counts вставлены, обновлены, оставлены no-op, подтвердили TTL или
  отклонены;
- какие input row numbers требуют исправления;
- какие requested slots были сохранены либо получили fallback;
- куда перемещён исходный файл и можно ли его безопасно replay.

Если записывать raw IOC/cell values в INFO/WARN/health, report создаст новый
канал утечки. Если оставить только одну summary log line, оператору придётся
угадывать ошибочные строки. Исходный CSV уже содержит values и может быть
сохранён в access-controlled terminal storage; sidecar может ссылаться на row
numbers без дублирования payload.

#### Вопрос заказчику

Нужны ли три различимых terminal outcome/location (`success`,
`completed-with-errors`, `rejected`) и machine-readable sidecar report с
per-row outcomes, но без raw IOC values в обычных logs/report по умолчанию?

#### Варианты

1. **Three outcomes + protected sidecar.** Original source сохраняется в
   соответствующем terminal bucket; report содержит metadata/counts/row
   numbers/codes, но не копирует raw values.
2. **Success/failed only.** Partial success складывается в один из двух
   каталогов; проще layout, но operator и automation не отличают committed
   partial от retriable failure.
3. **Rejected-row data extract.** Sidecar содержит исходные rejected rows;
   удобнее исправление, но дублирует sensitive IOC data и усложняет retention,
   ACL и CSV injection controls.

#### Текущая рекомендация

Вариант 1:

- terminal buckets/statuses: `SUCCEEDED`, `COMPLETED_WITH_ERRORS`, `REJECTED`;
- structural/data rejection не запускает automatic retry; transient technical
  failure остаётся non-terminal retry state до exhaustion;
- original file сохраняется в consumer-managed archive/quarantine с теми же
  либо более строгими permissions;
- рядом атомарно публикуется versioned machine-readable report после terminal
  ledger transition;
- report summary содержит delivery ID/sequence, `replay-of`, source/contract
  coordinates и fingerprint, content hash/size, timestamps/duration, overall
  status, counts по row/branch/action/artifact, diagnostics budget/overflow и
  requested-to-effective slot remaps;
- per-row section содержит original record number, stable outcome/code,
  affected artifact/branch и slot result, но не raw indicator/URL/hash или
  произвольные cell values;
- INFO/WARN/health публикуют только safe summary/correlation fields; подробный
  report доступен через terminal storage, а не через logs;
- отдельный rejected-row CSV с payload не создаётся по умолчанию. Оператор
  использует row number и сохранённый original source;
- retention report и original source должны быть согласованы, чтобы report не
  ссылался на уже удалённый файл; exact retention period будет отдельным
  решением.

Конкретный report format (например JSON manifest плюс optional tabular view) и
названия каталогов утверждаются на architecture/operator-guide stage, но три
business outcomes и redaction boundary должны быть стабильны раньше.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-26 закрыт:

1. `SUCCEEDED`, `COMPLETED_WITH_ERRORS` и `REJECTED` являются отдельными
   terminal business outcomes и различимыми terminal locations.
2. Original source сохраняется вместе с versioned machine-readable report.
3. Report содержит safe delivery/contract/count/per-row/slot metadata, но по
   умолчанию не дублирует raw IOC/cell payload.
4. INFO/WARN/health остаются summary-only и redacted.
5. Partial/rejected data outcomes не запускают automatic technical retry;
   исправление выполняется новой delivery/replay по I-25.

### I-27 — retention original CSV, report и compact audit

#### Сценарий

Terminal original CSV нужен для расследования, исправления rows и replay, но
содержит IOC/source data и занимает disk/SMB capacity. Бессрочное хранение
увеличивает exposure и рано или поздно заполняет storage. Слишком короткая
retention удалит source раньше, чем оператор разберёт partial/rejected outcome.

Текущий daemon housekeeping уже использует разные defaults: `done` files —
30 days, `failed` files — 90 days. Lifecycle receipt/history имеют отдельный
30-day contract и не должны автоматически владеть import terminal payload.

Source CSV и sidecar report образуют одну retention unit: оставшийся report без
исходного файла не позволяет найти row by number, а оставшийся source без
report теряет outcomes. Non-terminal processing/retry occurrence удалять
нельзя независимо от age/cap.

#### Вопрос заказчику

Согласны ли автоматически хранить successful import sources/reports 30 days,
а partial/rejected — 90 days, с declarative override по source/status?

#### Текущая рекомендация

Да, переиспользуя существующую operational модель:

- defaults: `SUCCEEDED=30d`, `COMPLETED_WITH_ERRORS=90d`, `REJECTED=90d`;
- original source и report удаляются/архивируются одной logical retention
  unit; нельзя оставить dangling report reference;
- period, optional max-count/max-bytes и action `delete|archive` настраиваются
  per import source/status и проходят strict semantic preflight;
- max-count/max-bytes eviction удаляет только oldest terminal units и создаёт
  видимый diagnostic/metric; non-terminal/pinned recovery data не eviction
  candidate;
- compact delivery ledger/audit coordinates хранятся как минимум не меньше
  максимальной terminal payload retention, чтобы causal/replay links оставались
  объяснимы;
- после удаления payload manual replay возможен только через повторную загрузку
  файла оператором; metadata не обещает восстановить bytes;
- cleanup bounded, restart-safe и идемпотентен; failure ухудшает health и даёт
  ERROR, но не блокирует canonical reads и не удаляет business DB rows;
- local и consumer-managed SMB storage следуют одной policy semantics, хотя
  transport archive/delete mechanics различаются.

Точные count/byte defaults требуют capacity evidence и не должны выдумываться
на discovery stage; age defaults можно согласовать сейчас.

#### Ответ и решение

Заказчик уточнил, что 30/90 days являются defaults, а в остальном import должен
следовать уже существующей retention model. I-27 закрыт:

1. Defaults: `SUCCEEDED=30d`, `COMPLETED_WITH_ERRORS=90d`, `REJECTED=90d`.
2. Operator contract переиспользует существующие `max-age`, `max-count` и
   `action: delete|archive`; отдельный import-specific retention language не
   вводится.
3. Import добавляет terminal targets/statuses к общей housekeeping capability,
   сохраняя bounded, restart-safe, idempotent cleanup и health/diagnostic
   behavior.
4. Source CSV и report остаются одной logical retention unit. Точная storage
   layout должна позволить общей housekeeping implementation не оставлять
   dangling half-pair.
5. Non-terminal/recovery-pinned occurrence не является retention candidate.
6. Compact audit хранится не меньше terminal unit; после payload expiry replay
   требует повторной загрузки bytes.
7. `max-bytes` не добавляется только ради import в v1: если такой общий quota
   contract понадобится, он проектируется как отдельное расширение общей
   maintenance capability.

### I-28 — resource limits и oversized delivery behavior

#### Сценарий

Import lane по I-23 последовательна, а accepted write set по I-10 атомарен.
Один CSV с миллионами rows, огромной cell, чрезмерным числом columns либо
fan-out expansion может исчерпать heap/temp disk/transaction budget и надолго
заблокировать все следующие deliveries. Внешний systemd memory/CPU limit
защитит host, но завершение процесса не является корректным import outcome.

Текущий security registry уже фиксирует общий parser-level resource budget как
известный gap для document parsing. Новый CSV boundary не должен молча
унаследовать unlimited behavior. При этом произвольные numeric defaults без
реальных размеров production dataframe могут отклонить легитимный export.

#### Вопрос заказчику

Согласны ли ввести обязательные configurable hard limits, превышение которых
отклоняет всю delivery до canonical commit, а конкретные числовые defaults
выбрать по текущим export sizes и load qualification?

#### Текущая рекомендация

Да:

- source/global config ограничивает как минимум file bytes, row count, column
  count, decoded cell length, total decoded characters, fan-out branches и
  diagnostic/report volume;
- byte size проверяется по transport metadata до expensive download/parse, где
  возможно, и повторно по фактически claimed immutable content;
- лимиты rows/cells/fan-out проверяются streaming и не требуют сначала
  materialize весь файл в heap;
- любое превышение является critical `RESOURCE_LIMIT_EXCEEDED`, отклоняет всю
  delivery и не оставляет partial canonical effect независимо от
  `accept-valid`;
- report содержит имя limit и observed/cap values без raw cell data;
- недостаток локального free space для claim/staging/report останавливает intake
  и ухудшает health, а не удаляет non-terminal файлы и не начинает partial
  commit;
- limits входят в pinned contract fingerprint I-20;
- numeric defaults утверждаются после measurement текущих largest exports и
  qualification corpus с разумным headroom, а не придумываются в интервью;
- operator может повысить limits декларативно, но unlimited production default
  не рекомендуется.

Если у заказчика уже есть ожидаемый максимальный размер/row count production
CSV, его нужно учесть как обязательный capacity input; иначе это будет
измерено на design/qualification stage.

#### Уточнение заказчика и итоговое решение

Заказчик запросил более технологичное решение, чем обычное отклонение больших
CSV, и согласился с пересмотренной рекомендацией. Hard caps сохраняются как
последняя safety boundary, но не являются основным large-file mechanism.

I-28 закрыт следующим образом:

1. CSV parse/mapping/validation выполняются streaming с bounded in-memory
   buffers; размер корректной delivery не приводит к materialization всего
   файла в heap.
2. Нормализованные candidates, row outcomes и planning facts пишутся в
   disk-backed per-delivery staging, изолированный от active canonical reads.
3. Duplicate grouping/coalescing, fan-out validation и slot planning могут
   выполняться set-based/out-of-core над staging.
4. После полной проверки accepted plan переносится в canonical tables одной
   ACID promotion transaction по I-10. До promotion staging не является
   business truth и не виден export/projection.
5. Crash recovery либо продолжает pinned delivery, либо идемпотентно очищает и
   пересоздаёт её staging по durable ledger; partial active effect невозможен.
6. Временная нехватка staging disk/DB capacity создаёт backpressure: intake
   pauses/degrades health, delivery остаётся non-terminal и не считается data
   rejection.
7. Hard limits остаются для патологических shapes и operator-declared safety
   budgets: cell/column/fan-out/diagnostic bounds и при необходимости explicit
   file/row cap. Их превышение отклоняет delivery до promotion.
8. Numeric capacity defaults выводятся из largest-export measurement и load
   qualification с headroom.
9. Первая implementation candidate — streaming Apache Commons CSV плюс
   adapter-owned SQLite staging. DuckDB/Arrow либо новая external family
   рассматриваются только при измеренном bottleneck.
10. Это не возвращает удалённый cross-file partition/aggregation pipeline:
    каждый CSV остаётся самостоятельной delivery, staging не объединяет files.

Точное физическое размещение staging, schema ownership и set-based promotion
будут выбраны на architecture stage с проверкой SQLite WAL/write-lock behavior.

### I-29 — source-contract configuration activation boundary

#### Сценарий

Import contracts содержат recognition, CSV dialect, mapping, processing,
merge, routing, duplicate, TTL и resource policies. Hot reload такого catalog
в работающем daemon создаёт одновременно несколько generations. По I-20 уже
claimed delivery должна закончиться со старой generation, а новые files — с
новой. Ошибка reload не должна оставить половину registries на старой версии,
а половину на новой.

У проекта уже есть strict startup boundary и поддерживаемый staged workflow
`bin/ioc-config apply`: candidate проходит syntax/typed/semantic validation,
атомарно заменяет YAML, запускает приложение и откатывается, если health не
достигнут. Добавление отдельного file watcher для import contracts создаст
второй configuration lifecycle.

#### Вопрос заказчику

Должны ли изменения import source contracts вступать в силу только после
validated application restart, как остальная `ioc.*` configuration, либо нужен
hot reload без остановки daemon?

#### Текущая рекомендация

Restart-only для v1:

- import contracts являются частью strict typed `ioc.*` configuration либо
  startup-loaded external rule catalog с тем же preflight;
- unknown keys, invalid mappings, overlapping signatures, missing profiles,
  registry refs и unsafe dialect/policy combinations отклоняются collect-all до
  intake;
- supported operator path переиспользует `bin/ioc-config apply`, health gate и
  rollback; прямое редактирование остаётся unsupported workflow;
- catalog generation immutable на время process lifetime; runtime watcher/hot
  swap не вводится;
- activation preflight проверяет durable non-terminal deliveries: contract
  version/fingerprint, нужная для I-20 recovery, нельзя удалить до terminal
  outcome;
- новые versions можно добавить рядом, если exact-one signatures различимы;
  одинаковый signature переключается только после drain старой generation;
- после successful restart новые claims используют новый catalog, recovery —
  pinned version;
- hot reload можно проектировать позднее как atomic catalog generation swap с
  retention старых generations, но только при доказанной operational
  необходимости.

Это сохраняет один configuration authority и делает deploy/recovery behavior
предсказуемым для оператора.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-29 закрыт:

1. Import contract catalog является startup configuration и immutable на время
   process lifetime.
2. Изменения применяются только validated restart через существующий
   `ioc-config apply`/health/rollback workflow.
3. Runtime hot reload и отдельный config watcher не входят в v1.
4. Activation не может удалить version/fingerprint, pinned незавершённой
   delivery; сначала требуется drain либо сохранение recoverable version.
5. New claims после restart используют новый catalog, recovery продолжает
   pinned contract I-20.

### I-30 — cardinality semantic observation в `processed` row

#### Сценарий

Export-shaped schemas иногда имеют несколько identity candidate columns:

- `address_blacklist`: `forbidden_url` и `forbidden_ip`;
- `hashes`: `hash_md5`, `hash_sha1`, `hash_sha256`.

Обычная корректная row заполняет только один подходящий field. Но внешний CSV
может заполнить два hash columns либо одновременно URL и unrelated IP. В
`processed` mode pipeline должен получить domain observation до
classification/routing.

Если одна physical row порождает два независимых IOC, возникают неоднозначности:
один imported `id` не может быть exact slot для двух lifecycles, metadata и TTL
outcome становятся общими случайно, а I-09 row atomicity связывает несвязанные
business observations. Это отличается от `related-artifacts`: один IOC там
детерминированно fan-out-ится в несколько projections.

#### Вопрос заказчику

Должна ли каждая CSV row в `processed` mode представлять ровно один semantic
IOC observation, отклоняя rows с нулём или несколькими identity values, либо
нужно разрешить извлечение нескольких независимых IOC из одной row?

#### Варианты

1. **Exactly one IOC per row.** Contract объявляет identity selector(s), после
   mapping ровно один имеет VALUE; pipeline получает одну observation.
2. **Explode row.** Каждый non-null identity field либо элемент списка создаёт
   отдельную observation; требуется отдельная semantics для ID, metadata,
   errors, duplicates и TTL.
3. **First non-null wins.** Порядок selectors выбирает IOC; проще, но молча
   игнорирует source data и делает config order business command.

#### Текущая рекомендация

Вариант 1 для v1:

- processed source contract декларативно перечисляет допустимые identity input
  fields/aliases и их type hints;
- после null grammar/mapping ровно один identity candidate обязан быть
  `VALUE`; zero/multiple дают isolatable row errors `MISSING_INDICATOR` или
  `AMBIGUOUS_INDICATOR`;
- один cell также не split-ится в список IOC неявно; one-to-many transform
  потребует отдельного будущего contract;
- один semantic observation проходит refang/normalize/classify/routing и может
  создать несколько related-artifact branches по I-02/I-09;
- imported metadata относится к этой одной observation; imported `id` — только
  к её primary branch по I-16;
- original row number остаётся correlation для всех derived branches;
- `as-is` не реконструирует domain observation и следует final artifact-row
  identity/schema contract I-11, но также не принимает неоднозначную canonical
  identity;
- file с несколькими IOC должен представить их несколькими rows. Это улучшает
  audit, duplicate policy и исправление errors.

Так anti-corruption mapping переводит внешний dataframe в один понятный domain
command на row, не перенося artifact column layout внутрь pipeline.

#### Возражение заказчика

Заказчик не принял универсальное требование exactly-one. Несколько identity
values в одной структурированной row не доказывают, что они независимы. Сама
row может быть source assertion о связи, например URL с соответствующим IP
либо несколько hashes одного объекта. Текущий document ingestion не выполняет
такой matching, потому что получает неструктурированный текст без надёжной
row-level структуры, но это ограничение входного формата, а не domain invariant.

Возражение обоснованно. Первоначальная рекомендация «multiple значит
`AMBIGUOUS_INDICATOR`» отозвана.

#### Пересмотр модели

Связь нельзя ни отрицать, ни выводить статистически только по заполненным
cells. Её семантику должен объявлять versioned source contract. Предварительно
нужны как минимум две declarative row shapes:

1. **`single-indicator`.** Несколько mapped columns являются альтернативными
   selectors одного IOC; после null grammar ровно один selector имеет VALUE.
   Zero/multiple остаются `MISSING_INDICATOR`/`AMBIGUOUS_INDICATOR`.
2. **`correlated-set`.** Contract объявляет members, их IOC type/role и смысл
   отношения. Несколько заполненных members составляют одну source assertion,
   например `url --resolves-to--> ip` или hashes одного файла. Membership и
   relation не угадываются importer-ом из данных.

Tokens предварительные. Эта развилка ортогональна `related-artifacts`:

- `related-artifacts` материализует один IOC в несколько списков;
- `correlated-set` сначала представляет несколько связанных IOC, каждый из
  которых затем может иметь собственный routing/fan-out.

Для `correlated-set` I-09 естественно распространяется на всю assertion:
частичный commit members разрушил бы заявленную связь. При `accept-valid`
ошибка одного member поэтому отклоняет всю logical row/group, но не остальные
rows delivery.

Текущий storage contract эту связь сам по себе не сохраняет:

- `<artifact>_sources` доказывает provenance одной canonical row, но не edge
  между двумя rows;
- typed receipt rows перечисляют materialized artifact rows, но не задают
  business relation между ними и имеют ограниченную retention;
- `canonical_observation` сейчас является identity/lifecycle одной ingestion
  occurrence, а не row-level correlation aggregate; переиспользовать этот term
  для другого смысла нельзя.

Следовательно, если связь является business fact, одной atomic раскладки по
обычным artifacts недостаточно. Нужен отдельный durable correlation/provenance
fact в dataframe DB: stable group identity для retry, delivery/row/contract
origin, relation kind, member roles и ссылки на materialized canonical
lifecycles/rows. SQLite достаточно; graph database для такого access pattern
не требуется.

Есть и отдельное следствие для identity. Действующий `first-non-empty`
`row_key` у `address_blacklist`/`hashes` рассчитан на alternative value columns:
если relation хранится одной combined artifact row, второй member не входит в
identity и разные пары могут ошибочно совпасть. Architecture stage должна явно
выбрать одно из двух:

- correlation хранится отдельно, а IOC materialize-ятся самостоятельными
  canonical rows с обычной identity;
- combined artifact row остаётся business record, но тогда её declared
  composite identity и merge semantics должны учитывать relation members.

Аналогично один row-level imported `id` нельзя автоматически считать export
slot каждого exploded member. Slot intent должен принадлежать конкретной
materialized primary row либо задаваться per-member; correlation/source ID —
это отдельное понятие.

#### Уточнённый вопрос заказчику

Если source row явно связывает IP и URL либо несколько hashes, должна ли система
сохранять эту связь как отдельный durable provenance/business fact, чтобы после
импорта её можно было доказать и найти? Или достаточно атомарно обработать
members и разложить их по обычным спискам, после чего сама связь может быть
утрачена?

Текущая рекомендация — сохранять durable correlation отдельно от плоских
artifacts. Иначе importer принимает более богатую структурированную информацию,
но необратимо отбрасывает именно ту семантику, ради которой multiple values в
row были признаны связанными.

#### Уточнение термина «плоский CSV»

Формулировка «оставить экспортные CSV плоскими» оказалась неоднозначной и
отозвана. Она не означала разорвать пару, разнести её members по несвязанным
rows или потерять отношение.

Под flat format имелась в виду только обычная двумерная таблица без JSON,
вложенных массивов и объектов внутри cells. Семантическая связь и табличная
форма не противоречат друг другу. Например:

```text
forbidden_url;forbidden_ip
https://example.test/a;192.0.2.10
```

Это flat CSV, но одна row явно сохраняет пару URL/IP. Совсем другой случай —
если URL materialize-ится в один artifact, IP в другой, а существующие export
schemas не несут общего correlation key. Тогда связь может быть durable в DB,
но потребитель текущих CSV её не увидит.

Поэтому здесь существуют два независимых product contract:

1. **Durable storage:** должна ли система сохранить source relation после
   импорта и уметь доказать её внутри canonical/provenance boundary?
2. **Export surface:** должны ли внешние потребители получить эту relation, а
   не только отдельные reputation-list members?

Если relation нужна снаружи, варианты не ограничиваются вложенным форматом:
можно сохранить совместимые пары в одной row существующего artifact либо
ввести отдельный optional correlation artifact/profile с group ID, relation
type, member role/type/value. Текущие list schemas при этом не следует молча
расширять техническими columns: это изменит контракт их потребителей.

Предварительная рекомендация:

- не терять связь в durable storage;
- сохранять members в одной existing artifact row там, где её утверждённая
  schema действительно описывает одну связанную business record, после
  исправления identity/merge semantics;
- для relations между разными artifacts не деформировать существующие list
  exports, а при необходимости предоставить отдельную configured correlation
  projection;
- окончательно выбрать export surface только после ответа, требуется ли эта
  связь внешним CSV consumers.

#### Следующий вопрос заказчику

Должна ли импортированная связь быть доступна только самой системе для
provenance/audit и будущей обработки, или внешние потребители также должны
получать её в экспортируемых CSV?

#### Ответ и итоговое решение

Заказчик уточнил исходную domain semantics: cross-list relations не нужны.
Несколько IOC-bearing columns внутри одной row конкретного list являются не
набором самостоятельных records и не graph edge, а полями одной составной
записи о вредоносном ресурсе. Например:

- `forbidden_url + forbidden_ip` вместе описывают одну address-blacklist row;
- `hash_md5 + hash_sha1 + hash_sha256` вместе описывают один вредоносный файл.

I-30 закрыт с исправлением предыдущей модели:

1. Единица import для такого contract — одна **compound artifact record**, а
   не несколько IOC observations, связанных отдельным correlation aggregate.
2. Source contract декларативно отличает compound columns одной list record от
   alternative selectors. Exactly-one validation применима только там, где
   schema действительно объявляет alternatives.
3. Все заполненные compound fields сохраняются в одной canonical row целевого
   artifact и экспортируются вместе в одной CSV row. Их row membership и есть
   требуемая связь.
4. Row atomicity I-09 относится ко всей compound record: нельзя принять URL и
   отбросить невалидный IP либо принять один hash из невалидной hash tuple.
5. В `processed` mode каждый заполненный typed field проходит применимые
   validation/normalization steps, но grouping исходной row сохраняется до
   подготовки одной artifact record. Модель не должна необратимо explode-ить
   её в независимые rows.
6. `related-artifacts` может по configured policy создать обычные secondary
   records, но связь primary compound row в эти lists не переносится и между
   artifacts не хранится.
7. Отдельные correlation tables, graph relations, correlation export/profile
   и cross-list correlation IDs не входят в scope.
8. Существующие per-row provenance/receipt mechanics могут сопровождать такую
   canonical row; новый durable relation aggregate только ради нескольких
   columns не требуется.
9. Термин `correlated-set` для этого основного сценария заменяется
   `compound-record` (предварительное configuration name), чтобы не создавать
   ложное ожидание cross-record/cross-artifact relation.

Остаётся отдельная identity-проблема: current `first-non-empty` выбирает только
одно field для `row_key`, тогда как теперь несколько fields принадлежат одной
business record. Её нельзя решить самим фактом совместного хранения columns.

### I-31 — matching частично заполненной compound record

#### Сценарий

Одна и та же составная запись может прийти с разной полнотой:

```text
active:   MD5=A; SHA256=B; SHA1=NULL
incoming: MD5=NULL; SHA256=B; SHA1=C
```

Для hashes общий `SHA256=B` с большой вероятностью означает один файл, поэтому
разумно дополнить active row значением `SHA1=C` по выбранной merge policy.

Но такое правило нельзя механически применить ко всем columns:

```text
active:   URL=https://a.test/x; IP=192.0.2.10
incoming: URL=https://b.test/y; IP=192.0.2.10
```

Один IP может обслуживать несколько разных вредоносных URL. Merge «по любому
совпавшему field» ошибочно склеит самостоятельные resources. Обратная ситуация
тоже неоднозначна: тот же URL с новым IP может означать обновление compound
record либо ещё одну допустимую пару.

Действующий `key-mode: first-non-empty` стабилен для alternative columns, но не
является полноценным entity-resolution contract для неполных compound rows.
Если первая delivery содержит только SHA256, а следующая добавляет более ранний
в порядке MD5, raw `row_key` изменится, хотя business resource тот же.

#### Вопрос заказчику

Согласны ли вы, что source contract должен отдельно для каждого list объявлять,
по каким field или комбинациям мы узнаём уже существующую compound record, а
совпадение по остальным columns само по себе не разрешает merge?

#### Текущая рекомендация

Ввести declarative record-matching policy отдельно от field merge I-03:

- list contract задаёт допустимые match keys/aliases, а не использует
  универсальный any-field match;
- hashes могут разрешать match по любому нормализованному непустому hash;
- для address blacklist URL и IP получают разные matching roles; общий IP не
  обязан объединять rows с разными URL;
- zero active matches означает new record;
- exact-one active match применяет configured field merge к той record;
- если разные populated keys указывают на две active records, вся incoming row
  отклоняется как `IDENTITY_CONFLICT`; importer не объединяет существующие
  canonical records автоматически;
- matching выполняется только по active rows, как уже решено в I-03;
- physical schema, stable internal identity/alias index и взаимодействие с
  immutable `row_key` выбираются на architecture stage после утверждения этой
  business policy.

Это позволяет сохранять составную запись и обогащать её частичными поставками,
не превращая общий IP или другое неуникальное поле в скрытую команду merge.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-31 закрыт:

1. Record matching конфигурируется отдельно для каждого list/source contract и
   не выводится универсально из любого совпавшего field.
2. Match keys/aliases определяют, какая active compound row является candidate;
   остальные columns не получают неявную identity authority.
3. Zero matches создаёт новую record, exact-one match применяет field merge
   I-03 к найденной record.
4. Если populated match keys указывают на разные active records, вся input row
   получает `IDENTITY_CONFLICT`; существующие records автоматически не
   склеиваются.
5. Historical rows не участвуют в matching.
6. Конкретная physical identity/alias-index model остаётся architecture
   решением; текущий `first-non-empty row_key` не объявляется достаточным для
   partial compound matching.

### I-32 — conflict policy для identifying fields compound record

#### Сценарий

После I-31 incoming row может однозначно найти active record по одному key, но
принести другое non-null значение в другом identifying field:

```text
active:   MD5=A; SHA256=B
incoming: MD5=A; SHA256=C
```

Общий MD5 указывает на одну candidate record, но два разных SHA256 не могут
обычно описывать один и тот же файл. Без отдельного правила обычная
`authoritative` merge policy I-03 могла бы молча заменить `B` на `C`.

У address data природа иная:

```text
active:   URL=https://a.test/x; IP=192.0.2.10
incoming: URL=https://a.test/x; IP=192.0.2.20
```

URL остаётся тем же ресурсом, а связанный IP мог законно измениться. Поэтому
все compound fields нельзя объявить ни неизменяемыми, ни свободно заменяемыми
одним глобальным правилом. Это уточняет, а не отменяет I-05: без совпавшего
declared match key другое identifying value по-прежнему означает новую record.

#### Вопрос заказчику

Должен ли source contract различать стабильные identifying fields и изменяемые
resource attributes/associations, чтобы конфликт стабильного значения по
умолчанию отклонял row, а изменяемое поле обновлялось по configured merge policy?

#### Текущая рекомендация

Да, разделить две policy dimensions:

- `record-match` I-31 находит candidate record;
- per-field identity/update role определяет, допустимо ли менять конкретное
  populated field после match.

Предлагаемый contract:

1. Stable identity alias по умолчанию использует `reject-conflict`: разные
   non-null values не заменяются даже при общем `authoritative` режиме.
2. Mutable association/attribute использует обычную tri-state field policy
   I-03, включая explicit clear/replace, если contract даёт такую authority.
3. Для hashes default — stable/consistent; `MD5=A + SHA256=B` нельзя молча
   превратить в `MD5=A + SHA256=C`.
4. Для address record URL может быть match anchor, а resolved/associated IP —
   mutable field, если конкретный contract так объявляет.
5. Explicit override стабильного identity field, если он действительно нужен,
   должен быть отдельной заметной policy, а не побочным эффектом
   `authoritative`; safe default остаётся reject.
6. Rejected conflict не изменяет record, slot, provenance или TTL.
7. Accepted mutable update сохраняет internal record/lifecycle и её export slot;
   меняется public content по обычному revision/projection contract.

Так source-of-truth authority остаётся доступной для динамических данных, но
не превращает ошибку в одном hash/identifier в тихую подмену business identity.

#### Ответ и решение

Заказчик подтвердил: несовместимые identifying values являются конфликтом, а
механизмом разрешения таких конфликтов пока не занимаемся. I-32 закрыт:

1. После exact-one record match разные non-null stable identifying values дают
   isolatable row error `IDENTITY_VALUE_CONFLICT`.
2. Import v1 не выбирает автоматически одну сторону, не заменяет stable value,
   не склеивает records и не создаёт privileged override даже для
   `authoritative` source.
3. Manual conflict-resolution workflow, operator approval queue и selective
   replay конфликтной части не входят в scope; исправление выполняется новой
   корректной delivery по I-25.
4. При default `accept-valid` отклоняется только конфликтная logical row; strict
   `reject-delivery` повышает её до отказа delivery по I-08.
5. Conflict не меняет canonical row, export slot, provenance, revision или TTL.
6. Fields, заранее объявленные mutable attributes/associations, не считаются
   identity conflict и продолжают использовать tri-state merge I-03. Это не
   является механизмом разрешения конфликта стабильных identifiers.
7. Report фиксирует row number, artifact/field и безопасный conflict code без
   raw IOC values по I-26.

### I-33 — несколько значений одной роли в compound record

#### Сценарий

Текущая artifact schema хранит одно scalar value в каждой column. Но у одного
ресурса может одновременно быть несколько значений одной роли. Например URL
может разрешаться в два IP:

```text
forbidden_url;forbidden_ip
https://a.test/x;192.0.2.10
https://a.test/x;192.0.2.20
```

Здесь обе rows могут быть двумя допустимыми tuples одного list. Если же URL
является единственным match key, I-31 увидит вторую row как update/conflict
первой и потеряет multiplicity. Запись `192.0.2.10,192.0.2.20` в одной cell
также неприемлема без отдельного wire contract: delimiter может встречаться в
данных, downstream ожидает scalar, а normalization/identity становятся
неоднозначными.

Для hashes обычная cardinality иная: одна compound file record имеет не более
одного MD5, SHA1 и SHA256. Два значения одного hash algorithm при совпавшем
другом stable hash являются I-32 conflict, а не коллекцией.

#### Вопрос заказчику

Если конкретный list допускает несколько одновременных значений одной роли,
должны ли они представляться несколькими CSV rows с declarative composite
record identity, тогда как каждая отдельная cell остаётся scalar?

#### Текущая рекомендация

Да, cardinality задаётся per list/source contract:

- scalar cells не получают неявные comma/pipe/JSON collections;
- list, где одна связь является business tuple, может разрешить repeated rows
  и объявить composite match identity, например `(forbidden_url,
  forbidden_ip)`;
- same URL с разными IP тогда образует две canonical/export rows одного
  artifact, а не overwrite/conflict;
- partial row, которая совпадает сразу с несколькими tuples и не содержит
  полного composite key, отклоняется как ambiguous, а не обновляет все rows;
- list вроде hashes сохраняет `one value per algorithm per record`; второе
  conflicting value остаётся I-32 conflict;
- если source cell реально содержит список, его split/explode допустим только
  как explicit versioned mapping transform, который создаёт проверяемые rows
  до matching; auto-split не применяется.

Так existing CSV consumers продолжают получать scalar schema, а допустимая
one-to-many semantics выражается обычными rows и точной identity, а не скрытым
мини-форматом внутри cell.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-33 закрыт:

1. Одна CSV cell остаётся scalar value; неявных delimiter/JSON collections нет.
2. Допустимая one-to-many semantics представляется несколькими rows одного
   artifact.
3. List/source contract объявляет подходящую composite record identity; разные
   tuples, например один URL с разными IP, не являются overwrite друг друга.
4. Неполный key, совпадающий с несколькими active tuples, отклоняется как
   ambiguous и не применяется ко всем найденным rows.
5. Per-algorithm hash cardinality остаётся one; несовместимое второе значение
   является I-32 conflict.
6. Explicit split/explode transform может быть частью versioned mapping, но
   auto-split содержимого cell запрещён.

### I-34 — trust/authority binding import source и source contract

#### Сценарий и риск

Exact-one structural recognition I-19 отвечает на вопрос «какой это dataframe»,
но не доказывает, что producer имеет право применить найденную policy. Некоторые
contracts обладают существенно большей authority:

- `authoritative` может заменить или очистить active fields;
- `related-artifacts` расширяет write set;
- requested slot влияет на export namespace;
- explicit split/processed transforms меняют interpretation входа.

Если любой CSV в общем inbox может только своими headers/shape выбрать любой
глобально configured contract, недоверенный или ошибочный producer способен
подготовить structurally valid файл с более привилегированной policy. Это
tampering/elevation-of-privilege через data-driven policy selection.

Текущая security doctrine считает remote names/metadata/content недоверенными,
а operator configuration — привилегированной, но ошибкоопасной. Отдельные files
не имеют sender signature. Следовательно, authority нельзя хранить в content
CSV либо выводить только из успешного schema match.

#### Вопрос заказчику

Можем ли мы считать право записи в конкретный configured import inbox грубой
границей авторизации: producer может использовать только allowlisted для этого
source contracts и не может повысить их authority содержимым файла? Если
разным producers нужны разные права, для них создаются разные sources/inboxes
и SMB credentials, без per-file signatures в v1.

#### Текущая рекомендация

Да, применить source-bound least-authority contract:

1. Каждый local/SMB import source имеет stable source ID и explicit allowlist
   contract IDs/versions; recognition выполняется только внутри этого множества.
2. Source задаёт ceiling опасных capabilities, минимум допустимые merge modes,
   `related-artifacts` и requested-slot authority. Contract не может превысить
   source ceiling; несогласованность является startup config error.
3. CSV content, filename, sidecar columns и remote metadata не могут выбирать
   policy, повышать authority или ссылаться на иной contract ID.
4. Write access к inbox означает доверие producer в пределах этого ceiling.
   Разные trust levels разделяются directories/shares и credentials, а не
   различаются после попадания files в общий inbox.
5. Неallowlisted structural match получает critical admission outcome и
   quarantine/report; importer не ищет более привилегированный global fallback.
6. Source/contract IDs и effective non-secret policy fingerprint фиксируются в
   delivery audit, чтобы действие нельзя было отрицать либо объяснить новой
   конфигурацией задним числом.
7. Per-file signing/authentication не входит в v1 при этом trust assumption.
   Если producer нельзя считать доверенным даже при write access, это новый
   security requirement и отдельный protocol/ключевой lifecycle.
8. Filesystem ownership/permissions, least-privilege SMB account и SMB3
   encryption остаются independent defense-in-depth controls; allowlist их не
   заменяет.

Это сохраняет автоматическое распознавание нескольких schemas в одном inbox,
но отделяет распознавание data shape от выдачи business authority.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-34 закрыт:

1. Право записи в configured import source/inbox является coarse-grained
   authorization producer-а только внутри source authority ceiling.
2. Каждый source явно allowlist-ит contract IDs/versions; recognition не
   рассматривает глобальные неразрешённые contracts.
3. Content, filename и remote metadata не могут выбрать либо повысить merge,
   routing, transform или requested-slot authority.
4. Разные trust levels разделяются sources/directories/shares и credentials.
5. Per-file signature/sender authentication не входят в v1 при принятой trust
   assumption.
6. Effective source/contract/policy fingerprint сохраняется в audit, а
   filesystem/SMB least privilege и transport encryption остаются отдельными
   controls.

### I-35 — spreadsheet formula safety импортируемых free-text fields

#### Сценарий и риск

В `as-is` mode импорт может перенести произвольные `source`, `description` и
другие configured free-text fields в canonical storage, а затем в CSV. Значение
с spreadsheet-dangerous prefix может быть интерпретировано Excel/LibreOffice
как формула при ручном открытии файла. CSV quoting защищает структуру файла, но
не гарантирует безопасность spreadsheet interpretation.

У текущего shipped profile риск `SEC-OUT-1` считается not-applicable только
из-за ограниченного default provider/marker contract. Dataframe import создаёт
ровно тот trigger, который зарегистрирован в `OUT-2`: новый путь произвольного
free text к output.

Безусловно добавлять apostrophe либо иным образом «экранировать» value тоже
неправильно: CSV прежде всего machine-consumed, а mutation меняет business
bytes и нарушает смысл `as-is`. Доверие к writer inbox из I-34 не отменяет
input/output validation — доверенный оператор также может импортировать
ошибочные данные.

#### Вопрос заказчику

Согласны ли вы по умолчанию отклонять logical row с spreadsheet-dangerous
free-text value, не изменяя его молча, а точное сохранение такого текста
разрешать только explicit policy для доверенного machine-only contract?

#### Текущая рекомендация

Да, ввести output-context validation до canonical commit:

1. Typed IOC/hash/numeric columns сначала ограничиваются собственными schema
   validators; spreadsheet rule относится прежде всего к string/free-text
   columns.
2. Safe default для импортируемого free text — `reject-dangerous`; finding
   является isolatable row error при `accept-valid` и delivery error при strict
   policy I-08.
3. Importer не prefix-ит apostrophe, не удаляет leading characters и не хранит
   silently rewritten value. Accepted `as-is` value остаётся точным.
4. Explicit `machine-exact`/`allow` допустим только в source-bound contract с
   осознанным consumer/risk disposition; это не global default.
5. Если позднее понадобится spreadsheet-oriented output, encoding принадлежит
   отдельной projection/profile policy, а не canonical import mutation.
6. Проверка выполняется над final prepared cell после declared transforms, но
   до failure-policy checkpoint/commit; только raw input check можно обойти
   transform-ом.
7. Diagnostic/report содержит artifact, column, row number и code без raw value
   по I-26.
8. Точный consumer-aware detector и regression corpus выбираются на security
   design stage; simplistic check только четырёх ASCII prefixes не объявляется
   полным контрактом.

Так default не создаёт опасный downstream CSV и одновременно не портит
machine semantics скрытым escaping.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-35 закрыт:

1. Spreadsheet-dangerous final free-text value по умолчанию отклоняет logical
   row и не изменяется автоматически.
2. Silent apostrophe-prefix/escaping в canonical value запрещён.
3. Explicit exact preservation допускается только source-bound machine-only
   policy с осознанным consumer/risk disposition.
4. Validation выполняется после configured transforms до checkpoint/commit.
5. Будущая spreadsheet-oriented encoding, если понадобится, принадлежит
   отдельной projection policy, а не import mutation.
6. Report не содержит raw rejected value.

### I-36 — export/revision significance public-field update

#### Сценарий

Текущий canonical writer реализует keep-first: duplicate observation не меняет
public row, а `artifact_revision` фактически insert-driven. Dataframe import
впервые вводит accepted update/clear уже существующей active record:

```text
before: MD5=A; SHA256=B; SHA1=NULL; description=old
after:  MD5=A; SHA256=B; SHA1=C;    description=new
```

Если такой commit обновит только DB/mutable projection, но не продвинет export
coverage revision, immutable export scheduler может решить, что новых business
данных нет. Downstream останется со старым SHA1/description до появления
несвязанной новой row либо другого trigger.

Обратный случай — accepted delivery после merge даёт тот же final public
content и только подтверждает TTL. Создавать новый immutable slice для такого
no-op противоречило бы уже принятому no-export renewal behavior.

#### Вопрос заказчику

Должно ли любое принятое изменение public fields active record быть
export-significant наравне со вставкой новой record: обновить mutable projection
и инициировать новый immutable slice/publish, тогда как content no-op/TTL-only
confirmation export не запускает?

#### Текущая рекомендация

Да, расширить revision semantics с «insert-driven» до «public-content
mutation-driven» для import-capable path:

1. Accepted insert, replace, clear либо enrichment, изменившие final public row,
   атомарно продвигают artifact coverage revision и required projection
   generation в той же canonical transaction.
2. Update сохраняет ту же active lifecycle, internal canonical identity и
   survivor export slot; это изменение record, а не исчезновение/появление IOC.
3. Несколько mutations одной delivery могут coalesce-иться в одно monotonic
   revision advancement per artifact; retry той же occurrence не продвигает её
   повторно. Точная counter granularity — architecture detail.
4. После commit durable revision является correctness truth; event/nudge только
   сокращает latency, periodic export check остаётся backstop.
5. Новый immutable slice покрывает новую revision. По действующему ADR-0022
   равные bytes недостаточны для `SKIPPED`, если covered revision новее: даже
   mutation `A -> B -> A`, coalesced до следующего export, остаётся новой
   принятой public occurrence.
6. No-op после normalization/merge не продвигает public revision. Если I-06
   разрешает TTL renewal, меняется только lifecycle freshness/projection state
   по действующему lifecycle contract, без immutable slice.
7. Rejected row/delivery и rolled-back transaction не меняют revision,
   projection generation или export/publish ledgers.
8. Multi-artifact import I-10 фиксирует affected revisions вместе с accepted
   write set; export profile затем читает согласованный snapshot обычным
   механизмом.

Это потребует superseding decision/documentation: формулировка текущих
ADR/dev docs об insert-driven revision корректна для shipped keep-first writer,
но недостаточна для нового update-capable import contract. Принятые ADR не
редактируются задним числом.

#### Ответ и решение

Заказчик в целом согласился и уточнил cadence: slice должен создаваться
своевременно, но не после записи каждой отдельной row; применяется тот же
механизм, что при стандартном чтении одного source file. I-36 закрыт:

1. Public-content mutation является export-significant и продвигает durable
   coverage revision.
2. Revision/generation учитываются batch-scoped: одна successful import
   delivery продвигает состояние каждого affected artifact как единый
   committed write set, а не публикует per-row activity/slice.
3. Export signal возникает только после commit всей delivery и завершения её
   canonical run boundary. Partial/uncommitted state downstream не видит.
4. Используется существующий `CanonicalArtifactsChanged`-style post-commit
   fast path и `DaemonExportScheduler` cadence: quiet-period coalesces близкие
   mutations, а max-cap ограничивает задержку при непрерывном intake.
5. Несколько завершённых deliveries внутри quiet window могут попасть в один
   согласованный slice; это ожидаемая своевременная агрегация, не потеря
   delivery facts/revisions.
6. Event/nudge не является correctness authority: потерю сигнала покрывает
   periodic check по durable revision/progress.
7. Content no-op и TTL-only confirmation остаются без immutable slice.
8. Import не вводит per-row export event, отдельный scheduler либо особую
   cadence policy; точные default quiet/max-cap значения остаются у текущей
   export configuration.

### I-37 — extra, duplicate и renamed source columns

#### Сценарий

BR-02 требует не зависеть от порядка и конкретных имён columns: source contract
маппит внешний dataframe на canonical fields. Но schema evolution имеет разные
случаи, которые нельзя считать одинаково безопасными:

- producer переставил columns;
- известная column переименована, но alias уже объявлен;
- появилась новая служебная column, которую import не использует;
- header продублирован либо два aliases одного canonical field присутствуют
  одновременно;
- обязательная column исчезла или опечатана.

Если игнорировать всё неизвестное, опечатка `hash_sha256` как `hash_sha265`
молча потеряет данные. Если отклонять любое дополнение, harmless producer
metadata потребует немедленного изменения contract. Duplicate header ещё хуже:
CSV parser может вернуть first/last value в зависимости от API, превращая
порядок columns в скрытую merge policy.

#### Вопрос заказчику

Согласны ли вы с safe default: порядок columns незначим, declared aliases и
явно allowlisted ignored columns допустимы, а неожиданные либо дублирующиеся
headers отклоняют весь файл как structural error?

#### Текущая рекомендация

1. Matching выполняется по normalized header names, не position; порядок
   columns не входит в business semantics.
2. Каждый canonical input field имеет explicit primary name/aliases. Rename
   поддерживается добавлением alias в новую version/fingerprint contract, а не
   fuzzy matching.
3. Missing required column — critical structural error; missing optional column
   даёт `ABSENT` по I-21.
4. Unknown column по умолчанию — critical structural error. Harmless producer
   fields допускаются explicit `ignored-columns` allowlist; их names входят в
   contract fingerprint.
5. Unrestricted `ignore all unknown` не является default: он скрывает schema
   drift. Если такой escape hatch вообще понадобится, он должен быть explicit
   source-bound policy с warning/audit count.
6. Duplicate normalized header и одновременное присутствие двух aliases,
   mapped на одно canonical field, всегда являются ambiguous file-level error;
   first/last-column wins не поддерживается.
7. Header normalization (`trim`, case folding и т.п.) задаётся declaratively и
   применяется одинаково при recognition и parse; auto/fuzzy correction нет.
8. Report фиксирует safe column names/codes и contract version без row payload.
9. Source recognition I-19 выполняется уже с этими правилами: ignored columns
   не создают ложный второй match, а structural ambiguity не лечится fallback.

Это даёт контролируемую schema evolution без зависимости от column order и без
молчаливой потери новых либо ошибочно переименованных данных.

#### Ответ и решение

Заказчик согласился с рекомендацией. I-37 закрыт:

1. Column order не имеет business semantics; matching идёт по declared names.
2. Renames принимаются только через versioned aliases, без fuzzy correction.
3. Missing required column и unexpected/duplicate/ambiguous headers являются
   critical file-level structural errors.
4. Missing optional column означает `ABSENT`.
5. Дополнительные producer fields игнорируются только по explicit
   `ignored-columns` allowlist; unrestricted ignore не является default.
6. Recognition и parse используют одну declared header normalization.

### I-38 — read-only preview/validate UX

#### Сценарий

Автоматический inbox по BR-01 импортирует stabilized file без ручного шага.
Однако `authoritative`, clear, compound matching и requested slots способны
изменить много active records. Terminal report I-26 объясняет уже выполненный
import, но не помогает оператору заранее проверить рискованную delivery.

Обязательное approval перед каждым file противоречило бы low-latency automatic
intake и добавило бы durable pending-approval lifecycle. Отдельный read-only
preview может быть полезен, но его нельзя выдавать за reservation: между
preview и настоящим import active DB, slots и configuration могут измениться.

В проекте уже есть привычная `--dry-run` semantics для extract/sync: операция
может читать/вычислять prospective work, но не мутирует durable/external state.

#### Вопрос заказчику

Нужен ли в v1 отдельный read-only validate/plan command для выбранного CSV,
который показывает предполагаемые inserts/updates/no-ops/conflicts до помещения
файла в automatic inbox, но не создаёт обязательного approval workflow?

#### Текущая рекомендация

Да, предоставить advisory preview поверх того же import engine:

1. Preview использует тот же pinned source contract resolution, dialect,
   mapping, transforms, validators, duplicate/matching/merge и slot planner,
   что real import; отдельной упрощённой логики нет.
2. Structural-only validation можно выполнить без write intent. DB-aware plan
   читает один consistent active snapshot и показывает aggregate counts per
   artifact: prospective new/update/clear/no-op/reject/conflict и slot fallback.
3. Команда не claim-ит inbox file, не пишет delivery/run/receipt ledgers, не
   резервирует canonical IDs/slots, не подтверждает TTL, не меняет DB/
   projections и не публикует events/slices.
4. Результат содержит input digest, source/contract/policy fingerprint,
   snapshot `asOf`/covered revisions и bounded diagnostics без raw IOC values.
5. Preview является advisory snapshot, не approval token и не гарантией
   результата. Real import повторно выполняет recognition/validation/planning
   в своей write transaction; race может законно изменить counts/outcome.
6. Automatic inbox не ждёт preview. Оператор проверяет файл вне watched source
   и затем помещает bytes в inbox обычным atomic/stability workflow.
7. В v1 не вводятся pending approval, «approve preview», reusable reservations
   либо обещание импортировать ровно показанный plan.
8. Для больших inputs preview использует тот же bounded streaming/disk-backed
   staging I-28 и очищает temporary state; отсутствие durable mutations не
   означает unbounded memory processing.

Так оператор получает безопасную проверку перед рискованной поставкой, а
автоматический production path и transactional truth остаются едиными.

#### Ответ и решение

Заказчик согласился. I-38 закрыт:

1. V1 включает отдельный advisory read-only validate/plan path поверх того же
   import engine.
2. Preview показывает bounded aggregate prospective outcomes, fingerprints и
   snapshot context, но не выполняет durable/external mutations.
3. Preview не создаёт approval token/reservation и не гарантирует будущий
   outcome; real import полностью revalidate/replan-ит current state.
4. Automatic inbox не ждёт preview и сохраняет low-latency behavior.

### I-39 — import health, backlog и manual recovery UX

#### Сценарий

По I-23 все import deliveries имеют один global durable apply order. Head
delivery в retry/backoff удерживает sequence и временно блокирует более поздние
imports. Это принято ради детерминированного результата, но без явного health
оператор увидит только растущий inbox и не поймёт, является ли состояние
ожидаемым backoff, resource backpressure I-28 либо повреждением ledger/storage.

С другой стороны, один terminal rejected/quarantined file уже не блокирует lane
и не должен навсегда делать весь daemon `DOWN`. Недоступность optional SMB
source также отличается от отказа canonical DB или startup recovery barrier.

Manual command, который позволяет произвольно skip/reorder/force-complete
non-terminal delivery, разрушил бы I-23 ordering, I-20 pinned recovery и audit.
После bounded exhaustion file уже получает terminal outcome, а повторная
попытка по I-25 является новой delivery.

#### Вопрос заказчику

Согласны ли вы, что v1 должен давать подробный read-only status и aggregate
health, но не разрешать оператору вручную менять ledger, пропускать head
delivery либо переставлять очередь; recovery выполняется автоматически, а
terminal file возвращается только новой replay delivery?

#### Текущая рекомендация

1. В daemon появляется отдельный aggregate import health component и подробный
   read-only CLI/status view; публичный HTTP API не вводится.
2. `UP`: startup recovery завершён, intake/lane работают, head progress и
   oldest backlog age находятся в configured operational bounds.
3. `DEGRADED`: service остаётся data-safe, но head ждёт retry/capacity дольше
   threshold, backlog age/count растёт, optional source недоступен либо есть
   recoverable projection/archive/report lag. DEGRADED не означает data loss.
4. `DOWN`: startup recovery failed/not completed, canonical/ledger/staging
   integrity недоступна, нарушен state-transition invariant либо correctness
   apply lane не может безопасно продолжать.
5. Одиночный terminal `REJECTED`/`COMPLETED_WITH_ERRORS` не меняет health
   навсегда; видимыми остаются recent outcome counters/last-safe error code.
6. Health публикует только aggregates: pending count, oldest age, head sequence
   и state, retry/backoff timing, staging/capacity state, last success/failure,
   counts per source/outcome. IOC values, filenames, paths и raw messages не
   публикуются.
7. Detailed CLI может по delivery ID/sequence показать contract fingerprint,
   state transitions, row-count summary и protected report location согласно
   local operator permissions, но без raw IOC in default output.
8. V1 не предоставляет `skip`, `reorder`, `force-complete`, ledger delete/edit
   или in-place requeue. Non-terminal retry/recovery принадлежит daemon-у;
   capacity resume автоматический после снятия backpressure.
9. После terminal outcome оператор исправляет причину и создаёт новую replay
   delivery с causal link I-25. Прямое удаление ledger/file state остаётся
   unsupported recovery.
10. Thresholds/retention принадлежат typed config, effective non-secret values
    отражаются в status; defaults подтверждаются operational/load evidence.

Так head-of-line blocking становится наблюдаемым и управляемым через исправление
причины, но operator UX не получает обходов durable ordering и idempotency.

#### Ответ и решение

Заказчик согласился. I-39 закрыт:

1. Import subsystem предоставляет aggregate health и подробный read-only status
   без raw IOC/path disclosure.
2. `UP`/`DEGRADED`/`DOWN` различают normal progress, data-safe backlog/retry и
   невозможность безопасно продолжать соответственно.
3. Head sequence/backoff/capacity и oldest backlog видимы оператору.
4. V1 не разрешает skip/reorder/force-complete/requeue либо ledger mutation.
5. Recovery автоматический; terminal исправление возвращается новой causally
   linked replay delivery.

### I-40 — immutable-byte claim boundary local/SMB delivery

#### Сценарий и риск

Quiet period I-01 проверяет, что наблюдаемые `size/mtime` некоторое время не
менялись, но не делает path immutable. Между последним stat и open producer
может заменить local file. На SMB object может измениться между listing,
`CHANGE_NOTIFY`, rename и download. Чтение live inbox path на протяжении parse
оставляет TOCTOU: одна delivery теоретически может быть построена из bytes,
которые менялись во время обработки.

I-07 уже требует consumer-managed claim, но нужно определить, что именно после
claim является source of truth. Одного path/mtime недостаточно; retry должен
читать те же bytes, а audit/report/archive — ссылаться на тот snapshot, который
действительно был commit-нут.

Published ordinary-ingest contract рекомендует producer protocol
`*.part -> atomic rename`, quiet period, claim в private `processing` и
whole-file SHA-256. Однако live `FileSystemSourceLifecycle` сначала пробует
`ATOMIC_MOVE`, а при `AtomicMoveNotSupportedException` выполняет обычный move.
Поэтому простое переиспользование класса ещё не доказывает immutable claim:
import должен либо усилить общий boundary, либо использовать отдельный explicit
copy-claim protocol. Watch/notify остаются latency hints. Managed SMB import
должен достичь эквивалентного outcome transport-specific средствами, а не
парсить mutable remote path напрямую.

#### Вопрос заказчику

Согласны ли вы с fail-closed contract: import начинается только после получения
ownership и создания private immutable local snapshot точных bytes; если
source adapter не может доказать atomic claim/consistent snapshot, delivery не
применяется и остаётся retryable/degraded?

#### Текущая рекомендация

1. Producer guidance для local source — писать temporary/non-matching name,
   fsync при необходимости и публиковать atomic rename; quiet period остаётся
   defensive fallback, а не доказательством immutable bytes.
2. Local adapter принимает только contained regular files, не следует symlink
   и atomically moves source в unique service-owned processing location на том
   же filesystem. Каталоги/ownership проверяются при startup.
3. Нельзя молча fallback-ить к parse исходного path при невозможном atomic
   ownership. Если понадобится copy-claim capability, это отдельный explicit
   adapter mode с private temp, complete copy, fsync/digest и change detection
   до публикации snapshot.
4. Managed SMB adapter выполняет server-side unique claim/rename в
   service-owned processing namespace, затем полностью materialize-ит file в
   private local staging. Parser работает только с завершённой local copy.
5. SMB source credentials/ACL должны исключать producer write в claimed
   namespace. Если transport/share не может дать требуемый ownership outcome,
   source остаётся non-terminal retry/degraded; partially read bytes не
   импортируются.
6. При materialization потоково вычисляется SHA-256 и size. Observation ID,
   delivery sequence, content digest, contract fingerprint и exact snapshot
   path durable pin-ятся до business apply; retry не перечитывает intake/remote
   object.
7. После pin повторная проверка digest перед apply/recovery обнаруживает local
   tampering/corruption. Mismatch является integrity failure без canonical
   write, а не новой delivery внутри прежней occurrence.
8. Canonical transaction, report и archive/quarantine относятся к одним pinned
   bytes. Terminal retention не может удалить snapshot, пока он нужен recovery.
9. Изменение original intake path после успешного claim не меняет принятую
   occurrence. Новый published file становится новой delivery по I-06/I-07.
10. Polling/reconcile обнаруживает пропущенные files и восстанавливает state;
    WatchService/`CHANGE_NOTIFY` не участвуют в доказательстве integrity.

Так stabilization отвечает только «когда попытаться claim», а private hashed
snapshot отвечает «какие именно bytes составляют delivery».

#### Ответ и решение

Заказчик согласился с рекомендацией. I-40 закрыт fail-closed contract:

1. Import применяет только delivery, для которой adapter получил exclusive
   ownership и создал private immutable local snapshot точных bytes.
2. Quiet period, polling, WatchService и SMB `CHANGE_NOTIFY` определяют момент
   попытки claim, но не доказывают immutable content.
3. Local source требует atomic claim в service-owned processing namespace;
   невозможность доказать ownership не разрешает fallback к parse live inbox
   path. Explicit verified copy-claim может быть отдельной capability.
4. Managed SMB source сначала выполняет unique server-side claim, затем
   полностью materialize-ит private local copy; parser не читает live remote
   object.
5. SHA-256, size, delivery sequence и contract fingerprint durable pin-ятся до
   business apply. Retry/recovery используют тот же snapshot.
6. Невозможность получить или повторно подтвердить ownership/consistent
   snapshot оставляет delivery non-terminal и переводит source в
   retry/degraded без canonical write.
7. Terminal report, archive/quarantine и canonical transaction относятся к
   одним pinned bytes; snapshot удерживается, пока нужен recovery.
8. Существующий fallback `ATOMIC_MOVE -> ordinary move` нельзя молча
   унаследовать как доказательство import claim. Architecture должна либо
   усилить общую lifecycle boundary, либо выделить import-specific adapter.

### Итоговая round-trip сверка I-01..I-40

После закрытия I-40 решения повторно сверены по цепочке
`claim -> recognize -> parse -> match -> merge -> lifecycle -> slots -> commit
-> export -> retry` и с действующими lifecycle/export invariants.

| Стык | Результат сверки |
|---|---|
| Delivery identity и retry | I-01, I-06, I-07, I-20, I-23, I-25 и I-40 согласованы: occurrence неизменна после claim, retry читает pinned bytes, terminal replay создаёт новую delivery. |
| Partial success и ACID | I-08 сначала исключает invalid rows, I-09 сохраняет атомарность fan-out одной row, I-10 одним commit применяет весь accepted write set. |
| Active matching и merge | I-03..I-05 и I-31..I-33 согласованы: history не участвует, identity change создаёт новые данные, stable identifying conflict не маскируется field merge policy. |
| CSV shape и compound records | I-11..I-15, I-21, I-22, I-30 и I-37 дают однозначную tri-state/mapping semantics без зависимости от порядка columns/rows. |
| Lifecycle и export trigger | I-04, I-06, I-14 и I-36 разделяют public mutation, TTL confirmation и batch-scoped slice trigger; отсутствующие rows ничего не подтверждают. |
| Operations и trust | I-19, I-26..I-29, I-34, I-35, I-38..I-40 задают fail-closed source boundary, bounded staging, read-only preview/status и recovery без обхода ledger. |

Сверка выявила одну product-развилку. I-16/I-17 определяют requested slot для
импортируемой row, но не говорят, можно ли этим intent перенумеровать уже
совпавшую active record. Действующий ADR-0021 требует, чтобы active survivor
сохранял assignment и никогда не перенумеровывался. Все остальные найденные
стыки являются architecture/implementation obligations, а не дополнительными
business choices.

### I-41 — requested slot против stable slot совпавшей active record

#### Сценарий и конфликт требований

CSV просит `id=7`. По declarative match keys I-31 row совпала с уже active
record, которой в том же `(profile, artifact)` назначен slot `12`:

```text
incoming: same active record, requested slot = 7
local:    same active record, current slot   = 12
```

Если slot `7` свободен, I-17 для новой record назначил бы его точно. Но эта
record не новая: ADR-0021 гарантирует active survivor стабильный slot между
slices и запрещает renumber/compaction. Перемещение `12 -> 7` может изменить
внешний ID у действующего потребителя; отклонение всей row из-за slot mismatch,
наоборот, потеряет полезное обновление её business fields.

#### Вопрос заказчику

Согласны ли вы разделить slot intent и business-field merge: по умолчанию
сохранять уже назначенный survivor slot `12`, применить допустимые обновления
record и явно отразить `requested=7, preserved=12` в delivery report, а для
источников, которым нужна строгая round-trip проверка ID, разрешить policy
`reject-mismatch`?

#### Варианты

1. **`preserve-existing` (рекомендация/default).** Existing assignment имеет
   приоритет, business fields продолжают merge, mismatch не скрывается в
   report. Requested slot применяется только к lifecycle без assignment.
2. **`reject-mismatch`.** Logical row отклоняется целиком, если её requested
   slot отличается от current survivor slot. Подходит для строгой сверки, но
   блокирует полезное обновление данных.
3. **`reassign-existing`.** Переместить survivor в requested slot, разрешая
   возможные swaps/cascades. Не рекомендуется: нарушает действующую стабильность
   external ID и потребует supersede не только import behavior, но и ADR-0021.

#### Текущая рекомендация

Ввести source-contract policy минимум
`existing-slot-policy: preserve-existing | reject-mismatch`, default —
`preserve-existing`:

1. если requested slot равен current assignment, slot operation является
   no-op;
2. если assignment отсутствует, действуют exact/fallback правила I-16/I-17;
3. если assignment существует и отличается, он не меняется и не участвует в
   swap/compaction;
4. при `preserve-existing` field merge и TTL semantics выполняются независимо,
   а mismatch отражается structured result/report;
5. при `reject-mismatch` отклоняется вся logical row и её fan-out по I-09;
6. policy version/fingerprint входит в pinned source contract I-20/I-29.

Это сохраняет внешний ID уже наблюдаемой active record, но оставляет оператору
строгий режим для поставок, где несовпадение slot должно означать отказ.

#### Ответ и решение

Заказчик согласился с рекомендованной policy. I-41 закрыт:

1. Default `existing-slot-policy` — `preserve-existing`.
2. Совпавшая active record сохраняет уже назначенный survivor slot; import не
   выполняет её renumber, swap или compaction.
3. Допустимые business-field updates продолжают применяться по выбранной merge
   policy, а различие requested/current slot явно отражается в structured
   delivery result/report.
4. Source contract может выбрать `reject-mismatch`; тогда logical row и весь её
   fan-out отклоняются атомарно.
5. Для record без assignment по-прежнему действуют exact/fallback rules
   I-16/I-17. Совпадающий requested/current slot является no-op.
6. Policy является частью versioned/fingerprinted source contract и pinned
   delivery snapshot.

Таким образом, импортируемый `id` задаёт предпочтительное размещение новой
active lifecycle, но не переопределяет уже наблюдаемую внешнюю идентичность
active survivor без явного отказа через strict policy.

## 7. Итог discovery interview

Дата закрытия интервью: **2026-08-23**.

Все 41 business-развилки I-01..I-41 имеют статус `DECIDED`. Финальная
round-trip сверка не оставила известных незакрытых product choices. Это
завершает discovery interview, но не является architecture approval или
разрешением начинать реализацию.

Следующий отдельный этап должен:

1. собрать решения в formal release/scope contract;
2. спроектировать ports, use cases, staging/ledger schema, transaction boundary,
   source adapters, declarative contract model и operator interfaces;
3. оформить superseding ADR относительно ADR-0015 и точечные изменения к
   lifecycle/export decisions, включая batch-scoped revision и requested-slot
   semantics;
4. провести threat/operations review immutable claim, CSV safety, SMB trust,
   capacity/backpressure и retention;
5. только после design approval разложить реализацию на slices с verification
   gates.
