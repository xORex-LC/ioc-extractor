# ioc-application-tck

Переиспользуемые **контракт-тесты (TCK)** для driven-портов `ioc-application`.
Каждый адаптер порта подключает этот модуль в `test`-scope и наследует абстрактный
контракт-тест, реализуя фабрику адаптера — так гарантируется **идентичное поведение**
всех реализаций порта (а не «зеркальные» копии тестов).

## Почему отдельный модуль, а не `test-jar`

TCK живёт в `src/main` обычного jar'а (а не в `test-jar` модуля `ioc-application`):

- нет привязки к фазе `package` (test-jar собирается только на `package` → хрупко);
- тестовый инструментарий (JUnit/AssertJ) наследуется потребителями транзитивно
  (у `test-jar` транзитивные test-зависимости не наследуются);
- экспортируется **только** контракт, а не все внутренние тесты `ioc-application`.

JUnit/AssertJ здесь — **compile-scope** (parent объявляет их глобально как `test`,
поэтому в этом модуле scope переопределён), т.к. контракт лежит в `src/main`.

## Слой

`core` / test-support. Зависит внутрь только на `ioc-application` (порты + доменные VO).
Никаких фреймворков/JDBC — реализации портов и их инфраструктура живут в адаптерах.

## Содержимое

- `com.iocextractor.application.tck.ingest.IngestionLedgerContractTest` — контракт
  `IngestionLedger` (переходы `CLAIMED -> SOURCE_ARCHIVED|FAILED`,
  `findIncomplete`, require-existing, `markFailed` create-or-preserve).

Новый порт → новый абстрактный `*ContractTest` здесь; адаптеры наследуют в своих тестах.
