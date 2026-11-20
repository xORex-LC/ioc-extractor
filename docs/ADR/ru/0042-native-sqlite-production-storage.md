> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0042-native-sqlite-production-storage.md).

<a id="adr-0042-retain-native-sqlite-production-storage"></a>
# ADR 0042: Сохранение Native SQLite как production storage

- Статус: принято
- Дата: 2026-10-13
- Scope: выбор production storage и удаление изолированного candidate из
  [ADR 0041](../0041-sqlite-staged-publication-experiment.md).

<a id="context"></a>
## Контекст

Конфигурируемые IOC routing, URL/IP cleanup, identity selection и ограниченная
source preparation работают независимо от versioned canonical backend.
Эксперименты с versioned SQLite улучшили доступность writer, но добавили
физическое копирование, visibility resolution, recovery и cleanup costs.
Полный Native strict document batch с миллионом входов достиг durable SMB
completion за 909.48 s с независимыми canonical/receipt/output checks.
Большой versioned candidate потребовал 1,934.65 s и затем провалил full-receipt
diagnostic. Эти наблюдения конкретного workload не являются универсальным
performance budget; [native capacity](../../dev/native-capacity.md) сохраняет
их identities и ограничения.

<a id="decision"></a>
## Решение

Сохранить улучшенную Native SQLite implementation единственным поддерживаемым
canonical backend. Удалить изолированные versioned publication, typed
transfer/journals, alternate receipt layouts, candidate migration/operation
scopes и experimental tools/tests. В этом релизе нет adaptive backend dispatcher
или дополнительного database family. Общие matching/mutation sessions,
writer admission, ограниченные source и prepared-row workspaces, CSV-import
staging, receipt streaming и Native fault contracts сохраняются.

Сохранить Native authority и transaction scope: document commits по artifact,
managed-import commits через несколько artifacts, dataframe-owned
receipts/lifecycle/slots, service-owned delivery coordination и один effective
UTC, взятый после получения write ownership. Предложенный в ADR 0041 протокол
fresh-publication-time не применяется к Native как побочное изменение cleanup.

Опубликовать сохранённые implementation и measurement facts в capability docs.
Удалить временный release experiment bundle без переноса в другой repository
archive. Сохранить исторические ADR как decision records; отметить, что
эксперимент ADR 0041 завершён без активации.

<a id="consequences"></a>
## Последствия

Сервис избегает сопровождения нескольких моделей canonical visibility/recovery.
SQLite всё ещё сериализует writers и не может прервать большую атомарную
транзакцию; размеры workload, admission и гранулярность документов остаются
операционными вопросами. Неуспешные capacity screens и оставшиеся ограничения
сохраняются явно. Изменения UTC semantics, storage family или publication
atomicity требуют собственного решения.

Cleanup требует Native contract/integration checks, architecture boundaries,
полного verify, review изменений scope SpotBugs/CPD/PMD и проверок документации.
Удаление устаревших suites/findings обновляет точные inventories, а не quality
floors. Это решение не меняет установленную DB, сервис или transport namespace.
