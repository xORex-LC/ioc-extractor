# com.iocextractor.adapter.out.regex

## Назначение

Выходные адаптеры порта `PatternEngine` — конкретные движки regex.

**Правило слоя:** реализуют доменный порт; домен о библиотеках движков не знает.
Выбор движка — через `bootstrap` по `ioc.engine`.

## Структура

| Файл | Назначение |
|---|---|
| `Re2jPatternEngine.java` | По умолчанию: RE2/J — линейное время, без ReDoS |
| `JdkRegexPatternEngine.java` | Альтернатива: `java.util.regex` (полный синтаксис) |

## Заметки

RE2 не поддерживает lookaround/backref — паттерны держим в `\b`-форме, совместимой
с обоими движками.
