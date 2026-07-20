# Гайды (guides)

Практические руководства для администраторов и разработчиков: «как настроить и
эксплуатировать», простым языком, без привязки к внутренней реализации.
Авторитетные описания архитектуры и поведения остаются в `docs/*.md`
(например, [../sync.md](../dev/sync.md)); гайд не должен противоречить им — при
изменении поведения обновляются оба.

Основная версия гайдов — английская, в корне этого каталога; русские версии —
в [ru/](ru/). Оба варианта — точные переводы друг друга и обновляются парой.

| Гайд | Русская версия | О чём |
|---|---|---|
| [configuration.md](configuration.md) | [ru/configuration.md](ru/configuration.md) | Полный операторский справочник всех поддерживаемых параметров, значений и рекомендаций; источник для настройки production template |
| [deployment.md](deployment.md) | [ru/deployment.md](ru/deployment.md) | Установка, upgrade, reconciliation конфигурации, backup, rollback, restore и uninstall |
| [daemon-operations.md](daemon-operations.md) | [ru/daemon-operations.md](ru/daemon-operations.md) | Повседневная эксплуатация daemon: подача файлов, health/logs, failed sources, backlog, retention и recovery |
| [remote-storage-sync.md](remote-storage-sync.md) | [ru/remote-storage-sync.md](ru/remote-storage-sync.md) | Работа с удалённым хранилищем: как устроены fetch/publish и push-уведомления, справочник конфигурации с подбором значений, права/сбои/восстановление/мониторинг, настройка SMB-шары на Linux (Samba) и Windows Server, чек-листы |
