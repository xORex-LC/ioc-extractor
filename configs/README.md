# configs

## Назначение

Внешние пользовательские переопределения конфигурации. Файлы здесь имеют
приоритет над `src/main/resources/application.yml`, но ниже флагов CLI и
переменных окружения.

**Правило слоя:** только данные конфигурации, без кода. Подключается через
`spring.config.import: optional:file:./configs/application.yml`.

## Структура

| Файл | Назначение |
|---|---|
| `application.yml` | (опционально) локальные переопределения дерева `ioc.*` |

## Заметки

Порядок переопределения: `classpath:application.yml` < `./configs/application.yml`
< CLI/env. Полная схема настроек — в `src/main/resources/application.yml`.
