# configs

## Назначение

Внешние пользовательские переопределения конфигурации. Файлы здесь имеют
приоритет над `src/main/resources/application.yml`, но ниже переменных
окружения, JVM system properties и флагов CLI.

**Правило слоя:** только данные конфигурации, без кода. Подключается через
`spring.config.import: optional:file:./configs/application.yml`.

## Структура

| Файл | Назначение |
|---|---|
| `application.yml` | (опционально) локальные переопределения дерева `ioc.*` |

## Заметки

Порядок переопределения: `classpath:application.yml` < `./configs/application.yml`
< environment < JVM system properties < CLI. Полная схема настроек — в
`bootstrap/ioc-app/src/main/resources/application.yml`.
