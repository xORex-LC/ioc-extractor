> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0031-bounded-camel-preparation-runtime.md).

<a id="adr-0031-bounded-camel-preparation-runtime"></a>
# ADR 0031: Ограниченный runtime подготовки на Camel

Статус: принято для поэтапной реализации, 2026-09-27. Только admission R0;
production integration и release qualification ещё не завершены.

<a id="context"></a>
## Контекст

Обработка для отдельных артефактов и полей требует исходных и производных
значений, упорядоченных операций и явной маршрутизации. Извлечение документов
и processed import должны разделять эти операции, сохраняя разные контракты
admission и persistence. Текущие дедупликация/классификация предшествуют
подготовке артефактов, а processed import сейчас получает строки с уже
построенными ключами. Ни одну из этих границ нельзя исправить простым
оборачиванием существующего mapper в routing framework.

Существующий platform pipeline владеет наблюдением стадий и failure checkpoints.
У canonical identity, зарегистрированного приоритета наблюдений, lifecycle и
восстановления по receipt уже есть владельцы в application/storage. Routing
не должен их дублировать.

<a id="decision"></a>
## Решение

Использовать embedded Apache Camel для ограниченного внутреннего исполнения
подготовки за framework-free application ports. Первоначально квалифицировать
Camel 4.22.1 с Java 21 и существующим dependency management Boot 4.0.8.
Использовать явное lifecycle wiring вместо Camel Boot starter. Изолированный
admission probe из двух тестов подтвердил синхронное direct execution, startup,
распространение исключений операций без redelivery, отклонение неверного route
и shutdown владельцем Spring bean. Это не evidence полной совместимости
приложения или starter.

Создать `adapter-processing-camel` в следующем module-admission срезе.
Отделить нейтральные пакеты `contract`, `compile`, `runtime` от обращённого
к application кода `bridge`; нейтральные пакеты не должны зависеть от
IOC/domain/application types. Закрепить это architecture tests. Адаптер целиком
реализует application ports и не объявляется общей опубликованной библиотекой.
Не создавать второй интерпретатор `platform-routing`. Если потребуется
независимо потребляемый нейтральный runtime, выделить ту же реализацию с её
малыми контрактами, а не создавать ещё один executor.

Чистые IOC-операции могут перейти в `ioc-processing` только с соблюдением
замыкания зависимостей: допустимы domain/platform contracts, но никогда
application, CSV или Camel. Сначала перенести минимальные view/mapping values;
существующие application payloads нельзя протащить внутрь копированием их
пакетных зависимостей.

Оператор настраивает ограниченный каталог operations/predicates и упорядоченные
AND/OR/NOT conditions. Bootstrap владеет configuration binding и semantic
admission; адаптер компилирует допущенные descriptors в execution nodes.
Не предоставляются неограниченный Camel DSL, scripts, reflection или адреса
endpoint, производные от входных данных.

Conditions возвращают MATCH, NO_MATCH или BLOCKED. FIRST останавливается на
первом MATCH или BLOCKED; EXCLUSIVE выполняет dispatch только после
доказательства ровно одного совпадения. ALL может готовить независимые ветви
несмотря на ожидаемые ошибки. Существующая failure policy вызывающего кода
управляет durable admission. Ошибка выбранной ветви не приводит к выбору
более поздней FIRST-ветви. Defaults применяются только при окончательном no-match.

Recovery создаёт отдельно названное view и обрабатывает только зарегистрированные
ожидаемые причины отказа. Итоговые diagnostics разрешаются до публикации:
восстановленная ошибка становится WARN только при восстановлении каждого
запросившего consumer; невосстановленный consumer сохраняет ERROR. Неожиданные
исключения распространяются. Никакая операция напрямую не публикует diagnostics
и не логирует payload; существующие observers/sinks сохраняют ownership.

Входные порты документов и import остаются разными. Первый получает
атрибутированные occurrences до разрушающей редукции, второй — допущенные
структурированные cells до итоговых output keys. Canonical finalization остаётся
вне Camel, как и source authority, проверка импортируемых slots, ledgers и
commits. Сохранить document per-artifact commit semantics и import
cross-artifact transaction semantics.

Новые cleanup policies применяются только к новым наблюдениям. Backfill нет;
старые строки истекают только при активном настроенном lifecycle. При recovery
закреплённые доставки не должны молча переходить на другую policy.

<a id="consequences-and-alternatives"></a>
## Последствия и альтернативы

Camel владеет исполнением operations/branches; узкий selector может обеспечивать
точную preselection semantics, но не должен дублировать вычисление conditions.
Внешний platform pipeline сохраняется. Явная интеграция Spring lifecycle
добавляет ownership code и tests, но исключает широкую starter auto-configuration.

JDK selector вместе с существующим execution был меньшей альтернативой, но
оставлял бы composition операций собственной оркестрации проекта. Он остаётся
вариантом пересмотренного решения, если последующая квалификация отклонит
Camel, а не параллельным движком. Перенос intake, sync, scheduling или durable
recovery на Camel вне scope.

Каждый новый production module должен войти в существующие Maven,
architectural, test-lifecycle, JaCoCo, SpotBugs, CPD и PMD gates в том же
изменении. Не допускаются снижение floor, скрытое исключение source или
пропуск report scope. До production activation должны пройти полная
совместимость приложения, concurrent lifecycle behavior, resource budgets и
recovery parity.

<a id="references"></a>
## Ссылки

- [Архитектура](../../ARCHITECTURE.md)
- [Качество сборки](../../dev/build-quality.md)
- [Политика тестирования](../../TESTING.md)
- [Релиз Apache Camel 4.22.1](https://camel.apache.org/releases/release-4.22.1/)
- [ADR 0030: порядок наблюдений](../0030-registered-observation-order-for-artifact-fields.md)

<a id="implementation-note-2026-09-29"></a>
## Примечание о реализации, 2026-09-29

R1–R5 допустили ограниченные compiler/runtime и активировали явный выбор
обработки документов и processed import. P6 квалифицирует выбранный plan по
canonical outputs и публикует operator activation procedure. Невыбранные
processed contracts по-прежнему требуют совместимый CSV preparer; его удаление
изменило бы принятое legacy behavior, поэтому это решение его не выводит.

<a id="superseded-scope-2026-10-04"></a>
## Заменённый scope, 2026-10-04

[ADR 0034](../0034-required-router-processing-plans.md) заменяет необязательный
выбор document processing и сохранённый совместимый processed-import dispatch.
Router теперь требует явные bindings; original-value defaults сохраняют
учёт исходных наблюдений. Решения об ограниченном Camel ownership и canonical
authority остаются неизменными.
