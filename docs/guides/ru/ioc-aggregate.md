# Артефакт IOC aggregate

Этот гайд описывает активацию, использование и rollback поставляемого артефакта
`ioc_aggregate`. Его публичный CSV-контракт:

```text
name;ip_address;url_match;host_match;hash
```

У артефакта нет публичного `id`. Отсутствующие значения записываются настроенным
CSV null literal (`NULL` в поставляемой конфигурации). Каждая текущая строка
содержит ровно одно значение IOC:

- bare IPv4 в `ip_address`;
- полный URL либо адрес без схемы, но с путём, query или port в `url_match`;
- чистый FQDN/domain без схемы, port и path в `host_match`;
- MD5, SHA-1 или SHA-256 в верхнем регистре в `hash`.

Четыре carrier-колонки образуют canonical identity. `name` берётся из
атрибуции источника документа либо из явно mapped колонки managed import. Более
поздняя durable registration может заменить `name`; пустое позднее имя сохраняет
прежнее значение. В этом релизе строки IP, domain и URL не связываются между
собой.

## Перед первым запуском обновлённой версии

Артефакт включён в поставляемые sink и export profile 0.3.0. Managed CSV import
остаётся выключенным, пока оператор не настроит и не проверит источник. Готовьте
обновление как один согласованный переход:

1. На старой версии прекратите подачу новых document и managed-import файлов.
2. Дождитесь terminal state для всех claimed document runs и import deliveries.
   Проверьте daemon health, `ioc import status`, document-каталог `processing` и
   настроенные managed-import processing/staging каталоги. Не восстанавливайте
   старый cross-path priority по timestamps.
3. Остановите сервис. Как одну recovery point сохраните effective configuration,
   полный каталог `var/db` с обеими SQLite БД и sidecars, а также все
   service-owned processing/snapshot/staging файлы.
4. Установите новый binary и согласуйте внешний YAML с поставляемым template.
   Перед применением выполните `ioc-config check`.
5. Запустите сервис и дождитесь healthy state для storage, lifecycle, ingestion
   и observation-registration компонентов, прежде чем вернуть producer access.
6. Подайте новый тестовый документ и проверьте
   `dataframe/IOC_aggregate_generated.csv` и export slice профиля
   `ioc-aggregate`.

Startup завершается fail-closed, если ordered-field policy включена, а у старой
незавершённой работы нет durable registration. Остановите intake, восстановите
соответствующую pre-upgrade recovery point либо запустите конфигурацию без
ordered policy и сначала завершите старую работу. Приложение никогда не выдаёт
такой работе новый rank только ради успешного recovery.

## Поведение empty-start

Upgrade не сканирует существующие canonical artifacts и не создаёт aggregate
rows задним числом. Aggregate table и projection начинают пустыми и наполняются
только наблюдениями, принятыми после активации. Если нужны исторические данные,
подайте их повторно осознанно: это новая delivery с новым precedence rank и
обычными lifecycle effects.

Retry и recovery после restart сохраняют исходный rank. Порядок завершения не
определяет победителя: более поздний зарегистрированный непустой `name` остаётся
приоритетным, даже если старая delivery завершилась позже. В одном документе или
группе дубликатов импортируемого CSV последнее принятое непустое имя выбирает
всё occurrence; последующие пустые имена его не очищают.

## Managed import

Production template содержит выключенные contract `ioc-aggregate-v1` и authority
profile. Перед включением импорта:

1. настройте отдельный local или SMB source и разрешите только aggregate
   contract и authority profile;
2. проверьте effective configuration;
3. выполните `ioc import validate` для репрезентативного файла;
4. включайте import только после подготовки inbox, прав и retention policy.

Input header совпадает с публичным контрактом из пяти колонок. В каждой принятой
строке должна быть непустой ровно одна carrier-колонка. `name` использует
`replace-non-null`; carrier values проходят строгую структурную validation и не
могут маршрутизироваться в другие artifacts. Дубликаты проходят
last-nonempty selection до canonical promotion.

## Health и retention

Health component `observationRegistration` публикует только aggregate counts.
Незавершённый crashed oneshot даёт `DOWN` с `pendingOneshot` и возрастом самой
старой unresolved registration. Automatic retention не удаляет такую строку:
процесс мог выполнять долгую живую invocation. Проверьте, что invocation
остановлена, и сохраните либо восстановите соответствующее dataframe state;
автоматического re-ranking fallback нет.

Terminal document/import registrations удаляются bounded batches только после
настроенного receipt/history horizon и только если их больше не использует ни
active/history field origin, ни canonical observation receipt, ни service
recovery reference.

## Граница rollback

Service schema v11 и dataframe schema v11 являются additive, но старые binaries
их не поддерживают. Не удаляйте internal tables и не уменьшайте SQLite
`user_version`. Binary-only rollback не поддерживается.

Для rollback остановите intake и сервис, восстановите старые binary и external
configuration, обе SQLite БД и service-owned files из одной pre-upgrade recovery
point, затем запустите сервис, проверьте health и только после этого откройте
producers. Принятая после backup работа будет потеряна в восстановленном state и
потребует отдельной reconciliation. Никогда не объединяйте новую dataframe DB со
старой service DB или наоборот.

## Связанные гайды

- [Справочник конфигурации](configuration.md)
- [Lifecycle canonical records](canonical-record-lifecycle.md)
- [Managed dataframe import](dataframe-import.md)
- [Развёртывание, обновление и rollback](deployment.md)
