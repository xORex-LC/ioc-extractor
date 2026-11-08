---
title: "R030-DOC — Documentation"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-DOC — Актуализация, полнота и переводы

## Результат и состояние

`in-progress`: основной комплект root/dev/guides пересмотрен по live sources и
согласован EN/RU, но общий goal ещё не закрыт. Остаток ведётся в единственной
[очереди](../documentation/remaining-work.tsv), результаты выполненных пакетов — в
[release evidence](../../../../.github/release-evidence/0.3.0.md).
Политика языка, ownership, Javadoc, README и generated docs принадлежит
[CONVENTIONS](../../../CONVENTIONS.md); release policy —
[RELEASE-PROCESS](../../../RELEASE-PROCESS.md).

## Оставшаяся работа

| Scope | Объём и обязательный результат |
|---|---|
| Исторические ADR | 49 оригиналов: 20 RU требуют EN canonical translation, 29 EN — RU companion; очередь сохраняет исходный язык, original bytes SHA-256 на input revision `1ad9f8b483575c136c2eb5529869fb01fa53ad00` и semantic parity obligation |
| README | 126 root/module/directory/package references: сверить ответственность, структуру, entry points, dependencies/ports и links; English-only на месте |
| Security docs | `SECURITY-ENGINEERING.md` и `THREAT-MODEL.md`: полный аудит/перевод отдельно от уже выполненных пакетов; принятое отложение не означает закрытие |
| Исторические release paths | 15 paths: сохранить identity/history, проверить navigation, language и archival dispositions; не переписывать опубликованные результаты |
| Redirects | 6 redirect paths: проверить target, anchors и отсутствие новой самостоятельной нормативной копии |

Числа относятся к сохранённому inventory очереди. Новый/изменённый документ
добавляется туда явно; объём не сокращается только из-за cleanup worknotes.
Ссылочный README не считается проверенным при аудите ссылающегося overview.

## Ограничения миграции

- Корневые maps сохраняют костяк и связи; локальные детали принадлежат owning
  README, межмодульная механика — dev/, использование — guides/, причины — ADR.
- Для исходно Russian ADR original переносится побайтово в `docs/ADR/ru/`,
  canonical English явно помечается переводом исторической записи. Для исходно
  English ADR canonical original остаётся на месте, в `docs/ADR/ru/` добавляется
  помеченный перевод. Номер, смысл, статус, alternatives/consequences и source
  provenance сохраняются в обоих случаях. Hash/source snapshot и паритет
  проверяются до закрытия; reconsideration оформляется отдельно по
  append-only/supersede protocol.
- Original hashes очереди совпадают с исходным ADR baseline. Для `ADR-0025`
  отдельно одобрен editorial URL repair: ссылка указывает на immutable Git blob,
  prose решения не менялась; текущий hash отличается от original. Остальные ADR
  в этом cleanup не редактируются. Byte-exact move в `ru/` меняет базу relative
  links и сам по себе не доказывает их работоспособность. Возможные citation-only
  rebases требуют отдельного явного review со списком исправленных href,
  original/current hash и подтверждением неизменности решения.
- README переводятся на месте без Russian copies. EN/RU пары остальных docs
  проверяются на полноту и совпадение supported behavior.
- `DIAGNOSTICS-CATALOG.md` и `LOGGING-CATALOG.md` остаются generated references:
  ручное редактирование/перевод и Russian copies исключены.
- Worknotes остаются русскими release records и не становятся authoritative
  published docs или link targets из docs/README.

## Критерии закрытия

Очередь имеет result или явное принятое disposition по каждому path; обязательные
review/translation выполнены, ADR originals проверены по hash и revision,
English canonical/Russian paths корректны, README English-only, generated outputs
проверены генератором. Документы достаточно полно описывают свои контракты,
ограничения и сценарии по live sources; navigation, semantic parity и convention/
link checks проходят. `TESTING.md` и остальные уже принятые policies остаются
согласованы с кодом. Final completion блокирует `R030-REL`; same-change обновления
от остальных goals продолжаются до релиза.
