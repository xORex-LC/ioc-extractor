# Гайды (guides)

Практические руководства для администраторов и разработчиков: «как настроить и
эксплуатировать», простым языком, без привязки к внутренней реализации.
Авторитетные описания архитектуры и поведения остаются в `docs/*.md`
(например, [../sync.md](../dev/sync.md)); гайд не должен противоречить им — при
изменении поведения обновляются оба.

Основная версия operator/integration-гайдов — английская, в корне этого
каталога; русские версии находятся в [ru/](ru/) и обновляются парой.
Новые руководства по маршрутизации IOC, diagnostics API и публикации библиотек пока существуют
только на английском; русские ссылки на них оставлены в смежных гайдах.

| Гайд | Русская версия | О чём |
|---|---|---|
| [configuration.md](configuration.md) | [ru/configuration.md](ru/configuration.md) | Полный операторский справочник всех поддерживаемых параметров, значений и рекомендаций; источник для настройки production template |
| [deployment.md](deployment.md) | [ru/deployment.md](ru/deployment.md) | Установка, upgrade, reconciliation конфигурации, backup, rollback, restore и uninstall |
| [bulk-resource-sizing.md](bulk-resource-sizing.md) | — | Ресурсный профиль массовой обработки, границы concurrency, измерение памяти/диска и завершение квалификации |
| [daemon-operations.md](daemon-operations.md) | [ru/daemon-operations.md](ru/daemon-operations.md) | Повседневная эксплуатация daemon: подача файлов, health/logs, failed sources, backlog, retention и recovery |
| [canonical-record-lifecycle.md](canonical-record-lifecycle.md) | [ru/canonical-record-lifecycle.md](ru/canonical-record-lifecycle.md) | Fresh-install TTL, двухэтапная activation для upgrade, health, retention и rollback boundary |
| [ioc-aggregate.md](ioc-aggregate.md) | [ru/ioc-aggregate.md](ru/ioc-aggregate.md) | IOC aggregate contract, empty-start activation, ordered names, health и coordinated rollback |
| [dataframe-import.md](dataframe-import.md) | [ru/dataframe-import.md](ru/dataframe-import.md) | Настройка и эксплуатация contract-driven local/SMB CSV import, preview, status, replay и recovery |
| [ioc-processing-routes.md](ioc-processing-routes.md) | — | Включение именованных IOC-планов для документа и processed import, проверка результата и восстановление |
| [remote-storage-sync.md](remote-storage-sync.md) | [ru/remote-storage-sync.md](ru/remote-storage-sync.md) | Работа с удалённым хранилищем: как устроены fetch/publish и push-уведомления, справочник конфигурации с подбором значений, права/сбои/восстановление/мониторинг, настройка SMB-шары на Linux (Samba) и Windows Server, чек-листы |
| [library-publication.md](library-publication.md) | — | Публикация и проверка independently consumable Maven libraries |
| [diagnostics-library.md](diagnostics-library.md) | — | Подключение общей diagnostics-библиотеки: коды сервиса, результаты, policy, bounded counts и обработка delivery failures |
| [logging-library.md](logging-library.md) | — | Общий SLF4J API: каталог сервиса, typed fields, MDC, ECS helpers и явное маскирование |
| [logging-library-boot.md](logging-library-boot.md) | — | Проверенный синхронный Boot ECS recipe: Java/YAML/XML, dataset/error ownership и ограничения backend |
| [configuration-library.md](configuration-library.md) | — | Подключение проверки имён конфигурации: выбор источников, покрытие, наблюдение старых имён и решения сервиса |
| [configuration-library-check.md](configuration-library-check.md) | — | Исполняемый пример ограниченного Spring-контекста с явными YAML/override-входами и запретом импортов |
