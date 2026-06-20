# com.iocextractor.domain

## Назначение

Доменное ядро (Onion-центр): модели IOC и доменные сервисы извлечения, рефанга,
классификации и атрибуции. Чистый Java, бизнес-правила без оглядки на технологии.

**Правило слоя:** не зависит ни от чего — ни от `application`/`adapter`/
`bootstrap`, ни от фреймворков (Spring, Tika, RE2/J, commons-csv, picocli).
Определяет порты (`PatternEngine`, `Refanger`, …), реализуемые снаружи.

## Структура

| Подпапка | Назначение |
|---|---|
| `model/` | Value objects: `Indicator`, `IndicatorType`, `SourceContext`, `MaskMatch` |
| `refang/` | Деобфускация: порт `Refanger` + реализация |
| `extract/` | Извлечение: порты `PatternEngine`, `IndicatorExtractor` + реализация |
| `classify/` | Политика масок: порт `MatchPolicy` + реализация |
| `attribute/` | Атрибуция `source` по маркерам секций |

## Зависимости

**Зависит от:** только JDK. **Не импортируется** иначе как через свои порты —
внешние слои зависят на него, не наоборот.
