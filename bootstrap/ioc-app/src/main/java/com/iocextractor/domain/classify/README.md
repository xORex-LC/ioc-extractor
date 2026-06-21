# com.iocextractor.domain.classify

## Назначение

Классификация сетевой маски: определение кодов `url_match` / `host_match` по
**декларативным правилам** над признаками индикатора (`domain.feature`).

**Правило слоя:** чистое доменное правило. Логика «какой вариант» — не зашита:
тонкий вычислитель применяет правила из конфига; коды и предикаты декларативны.

## Структура

| Файл | Назначение |
|---|---|
| `MatchPolicy.java` | Порт: `MaskMatch classify(Indicator)` |
| `FeaturePredicate.java` | Тонкий именованный предикат над `IndicatorFeatures` |
| `FeaturePredicates.java` | Реестр предикатов по ключам (`has-query`, `is-subdomain`, …) |
| `MatchRule.java` | Правило: `when` (предикаты, AND) → коды `MaskMatch` |
| `RuleBasedMatchPolicy.java` | Вычислитель: признаки → первое подходящее правило (first-match-wins) |

## Заметки

4-вариантная схема и правила — `application.yml` (`ioc.classify.rules`) и
`docs/output-mapping.md` (декларативная классификация). Вид хоста (PSL) приходит
через признаки `domain.feature` (порт `HostClassifier`).
