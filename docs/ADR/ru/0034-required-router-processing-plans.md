> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0034-required-router-processing-plans.md).

<a id="adr-0034-required-router-processing-plans"></a>
# ADR 0034: Обязательные планы обработки Router

Статус: принято, 2026-10-04. Заменяет положения о необязательном потоке и
сохранении совместимого preparer из [ADR 0031](../0031-bounded-camel-preparation-runtime.md).

<a id="context"></a>
## Контекст

Первоначальное внедрение Router допускало явный выбор document/import processing,
сохраняя исполняемый document pipeline и определяемый по providers preparer
processed import для конфигураций без выбора. Это была граница внедрения,
а не итоговая модель продукта. Оператор согласовал полный вывод этих путей
до развёртывания на стенде, с production defaults на исходных значениях.

Одни лишь совпадающие публичные CSV-байты не доказывают сохранения семантики
наблюдений. Дубли источника и разные входы, отображающиеся в один ключ, влияют
на резервирование ID, source counts и проверку canonical confirmation.

<a id="decision"></a>
## Решение

Каждый документ исполняет обязательный именованный `ioc.processing.document-plan`
через embedded Router. Каждый import contract с `mode: processed` требует явного
`processed-route` с source inputs и разрешёнными output fields. Отсутствие выбора
отклоняется при configuration admission; неявного route или альтернативного
processing engine нет. `as-is` остаётся независимым режимом import.

Classpath и production defaults для документов направляют исходное IOC view
во все поставляемые артефакты. Существующие artifact filters, column transforms,
identity и write policies сохраняют своих владельцев. URL-to-host/IP cleanup
требует явного изменения plan и не включается этой миграцией.

Document plans объявляют `observation-selection`:

- `retained-observations`: KEEP_FIRST artifacts готовят исходные наблюдения,
  допущенные `ioc.pipeline.deduplicate`, сохраняя mapped collisions и кратность
  резервирования ID. LAST_NONEMPTY artifacts по-прежнему рассматривают каждое
  occurrence и выбирают целую row по final artifact key. Это поставляемый
  default с исходными значениями.
- `final-key`: каждое occurrence участвует в routing и diagnostics; каждый
  artifact выбирает целые строки-победители по final key. Это default для новых
  объявленных plans, поддерживающий схлопывание производных views.

Политика исходных наблюдений входит в тот же Router path и policy fingerprint.
Она не является compatibility dispatcher. Application продолжает владеть
разрешением identity, выбором победителей, failure-policy checkpoints,
резервированием ID, canonical commit и receipts. Camel владеет только
ограниченной подготовкой.

<a id="consequences"></a>
## Последствия

- Собственные artifact catalogs должны предоставлять полные именованные plans.
  Отключённые artifacts не могут быть destinations; каждый включённый document
  artifact маршрутизируется или явно исключается. Processed contracts не могут
  опускать route даже при отключённом intake.
- Старую конфигурацию нужно мигрировать и проверить до активации. Завершать работу
  под закреплённой policy; unsealed работа не может молча принять другой plan.
  Канонически закоммиченные imports по-прежнему восстанавливаются по receipts
  без повторной обработки.
- Существующие canonical rows не переписываются. Существующее отклонение
  дублирующихся final keys при lifecycle confirmation сохраняется для plans
  с retained observations.
- Исторические comparison evidence P6/O7/O8 описывают прежнее необязательное
  внедрение, а не квалифицируют полный cutover. Новая приёмка должна проверить
  defaults, настроенные routes, учёт ID/provenance, negative admission и recovery.

<a id="references"></a>
## Ссылки

- [Обработка](../../dev/processing.md)
- [Managed import](../../dev/dataframe-import.md)
- [Маршруты оператора](../../guides/ioc-processing-routes.md)
- [Порядок наблюдений](../0030-registered-observation-order-for-artifact-fields.md)
