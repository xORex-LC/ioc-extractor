# Гайды (guides)

Практические руководства для администраторов и разработчиков: «как настроить и
эксплуатировать», простым языком, без привязки к внутренней реализации.
Авторитетные описания архитектуры и поведения остаются в `docs/*.md`
(например, [../sync.md](../dev/sync.md)); гайд не должен противоречить им — при
изменении поведения обновляются оба.

Основная версия operator/integration-гайдов — английская; русские версии
находятся в [ru/](ru/) и обновляются парой. Библиотеки постепенно собираются
в [libraries/](libraries/README.md) и [ru/libraries/](ru/libraries/README.md).
Руководство по маршрутам IOC имеет EN/RU-пару. Руководство по публикации библиотек
пока существует только на английском; ссылки на него сохранены в смежных гайдах.

| Гайд | Русская версия | О чём |
|---|---|---|
| [libraries/](libraries/README.md) | [ru/libraries/](ru/libraries/README.md) | Выбор из шести опубликованных артефактов с парными руководствами EN/RU |
| [libraries/development.md](libraries/development.md) | [ru/libraries/development.md](ru/libraries/development.md) | Переиспользование и развитие API, независимые версии как целевая схема и текущий общий Maven/CI version |
| [configuration.md](configuration.md) | [ru/configuration.md](ru/configuration.md) | Полный операторский справочник всех поддерживаемых параметров, значений и рекомендаций; источник для настройки production template |
| [deployment.md](deployment.md) | [ru/deployment.md](ru/deployment.md) | Установка, upgrade, reconciliation конфигурации, backup, rollback, restore и uninstall |
| [bulk-resource-sizing.md](bulk-resource-sizing.md) | — | Ресурсный профиль массовой обработки, границы concurrency, измерение памяти/диска и завершение квалификации |
| [daemon-operations.md](daemon-operations.md) | [ru/daemon-operations.md](ru/daemon-operations.md) | Эксплуатация daemon: подача документов, health, blocked/failed work, остановка и согласованный backup |
| [canonical-record-lifecycle.md](canonical-record-lifecycle.md) | [ru/canonical-record-lifecycle.md](ru/canonical-record-lifecycle.md) | Fresh-install TTL, двухэтапная activation для upgrade, health, retention и rollback boundary |
| [ioc-aggregate.md](ioc-aggregate.md) | [ru/ioc-aggregate.md](ru/ioc-aggregate.md) | IOC aggregate contract, empty-start activation, ordered names, health и coordinated rollback |
| [dataframe-import.md](dataframe-import.md) | [ru/dataframe-import.md](ru/dataframe-import.md) | Настройка и эксплуатация contract-driven local/SMB CSV import, preview, status, replay и recovery |
| [ioc-processing-routes.md](ioc-processing-routes.md) | [ru/ioc-processing-routes.md](ru/ioc-processing-routes.md) | Именованные планы документа и processed import, выбор ветвей, проверка результата и восстановление |
| [remote-storage-sync.md](remote-storage-sync.md) | [ru/remote-storage-sync.md](ru/remote-storage-sync.md) | SMB fetch/publish: handoff, identity/landing limits, конфигурация, CLI, recovery и серверные права |
| [library-publication.md](library-publication.md) | — | Публикация и проверка independently consumable Maven libraries |
| [libraries/diagnostics.md](libraries/diagnostics.md) | [ru/libraries/diagnostics.md](ru/libraries/diagnostics.md) | Подключение общей diagnostics-библиотеки: коды сервиса, результаты, policy, bounded counts и обработка delivery failures |
| [libraries/logging-slf4j.md](libraries/logging-slf4j.md) | [ru/libraries/logging-slf4j.md](ru/libraries/logging-slf4j.md) | Общий SLF4J API: каталог сервиса, typed fields, MDC, ECS helpers и явное маскирование |
| [libraries/logging-slf4j-boot.md](libraries/logging-slf4j-boot.md) | [ru/libraries/logging-slf4j-boot.md](ru/libraries/logging-slf4j-boot.md) | Проверенный синхронный Boot ECS recipe: Java/YAML/XML, dataset/error ownership и ограничения backend |
| [libraries/events.md](libraries/events.md) | [ru/libraries/events.md](ru/libraries/events.md) | JDK-контракты локальных событий: метаданные, публикация, наблюдение и recorder |
| [libraries/events-spring.md](libraries/events-spring.md) | [ru/libraries/events-spring.md](ru/libraries/events-spring.md) | Явный Spring publisher: частичная доставка, границы ошибок и настройки сервиса |
| [libraries/config-spring-boot.md](libraries/config-spring-boot.md) | [ru/libraries/config-spring-boot.md](ru/libraries/config-spring-boot.md) | Проверка имён и миграций, покрытие, binding и решения сервиса |
| [libraries/config-spring-boot-check.md](libraries/config-spring-boot-check.md) | [ru/libraries/config-spring-boot-check.md](ru/libraries/config-spring-boot-check.md) | Исполняемый ограниченный Spring-контекст с явными YAML/override-входами и запретом импортов |
