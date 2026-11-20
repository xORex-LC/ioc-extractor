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

`verified` в принятом baseline на 2026-11-16: 196 применимых задач выполнены,
2 security docs явно исключены пользователем (N/A). Результаты и dispositions
ведутся в единственной
[очереди](../documentation/remaining-work.tsv), результаты выполненных пакетов — в
[release evidence](../../../../.github/release-evidence/0.3.0.md).
Политика языка, ownership, Javadoc, README и generated docs принадлежит
[CONVENTIONS](../../../CONVENTIONS.md); release policy —
[RELEASE-PROCESS](../../../RELEASE-PROCESS.md).

## Покрытие принятого baseline

| Scope | Объём и обязательный результат |
|---|---|
| Исторические ADR | 49/49 verified: 20 RU получили EN canonical translation, 29 EN — RU companion; очередь сохраняет исходный язык, original bytes SHA-256 на input revision `1ad9f8b483575c136c2eb5529869fb01fa53ad00` и semantic parity obligation |
| README | 126/126 root/module/directory/package references verified: проверены ответственность, структура, entry points, dependencies/ports и links; English-only на месте |
| Security docs — вне baseline 0.3.0 | По решению пользователя 2026-11-16 полный аудит, переработка и перевод `SECURITY-ENGINEERING.md`/`THREAT-MODEL.md` исключены (N/A); SDLC и модель угроз — одна из следующих версий; не блокируют закрытие R030-DOC |
| Исторические release paths | 15/15 paths verified: identity/history сохранены, navigation/language/archival dispositions проверены; draft 0.3.0 и RU companion актуализированы, 14 historical records побайтово сохранены |
| Redirects | 6/6 redirect paths verified: проверены targets и anchors, восстановлены исторические section entry points; нормативный текст остаётся у maintained guide |

В очереди сохраняются 198 исходных строк: 196 в baseline 0.3.0 и 2 N/A по явному
исключению выше. N/A здесь означает принятое исключение из релизного scope,
а не успешный аудит. Применимые security gates и отдельные findings относятся
к R030-SEC и этим решением не закрываются.

Числа относятся к сохранённому inventory очереди. Новый/изменённый документ
добавляется туда явно; объём не сокращается только из-за cleanup worknotes.
Ссылочный README не считается проверенным при аудите ссылающегося overview.

## Ограничения миграции

- Корневые maps сохраняют костяк и связи; локальные детали принадлежат owning
  README, межмодульная механика — dev/, использование — guides/, причины — ADR.
- Для исходно Russian ADR original переносится побайтово в `docs/ADR/ru/`,
  canonical English явно помечается переводом исторической записи.
  Пользователь 2026-11-16 согласовал только relative-href rebase к тем же
  English destinations:114 occurrences/18 files; точные правки и hash provenance
  опубликованы в docs/ADR/translation-link-rebases.tsv и translation-provenance.tsv. Для исходно
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


## Проверенное завершение baseline — 2026-11-16

Все 198 исходных inventory identities сохранены:196 verified + 2 N/A.
Матрица фиксирует пакеты, источники и проверки. Завершение DOC не является
fresh release qualification: дальнейшие code/config/candidate изменения требуют
same-change обновлений иповторной курации draft. Публикация, применимые security
и остальные release obligations оцениваются своими goals.
