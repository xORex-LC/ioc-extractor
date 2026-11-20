> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0027-rollback-safe-configuration-evolution.md).

<a id="0027--rollback-safe-configuration-evolution"></a>
# 0027 — Эволюция конфигурации с безопасным rollback

<a id="status"></a>
## Статус

**Принято 2026-08-29. Первое окно совместимости реализовано для SMB encryption
selector в candidate 0.3.0.**

Этот ADR узко заменяет требование ADR-0026 немедленно отклонять удалённый
`encrypt: boolean`. Он также уточняет правило удаления tombstones ADR-0016
для изменения конфигурации, пересекающего packaged binary rollback boundary.
Fail-closed SMB policy, строгое обращение с каждым неуказанным key и все
transport-layer ownership decisions остаются неизменными.

<a id="context"></a>
## Контекст

Packaged deployment сохраняет `etc/application.yml` под владением оператора,
а release activation и rollback переключают application binary. Обычно это
правильная ownership boundary, но немедленное переименование configuration
создаёт невозможную rollback sequence:

1. Старый binary понимает только старый key.
2. Новый binary понимает только новый key.
3. Изменение общего файла до activation ломает rollback к old binary,
   а изменение после неё не позволяет запустить new binary.

Database и unit rollback не исправляют такую несовместимость конфигурации.
Шаблон `.new` рядом с live file информирует оператора, но не создаёт
пересечения допустимых языков.

ADR-0026 выявил проблему заменой `smb.encrypt` на закрытый selector
`smb.encryption`. Новый selector всё ещё нужен: boolean не отличает
обязательное шифрование от допущенного fallback. Не хватало ограниченной
expand/contract phase между двумя публичными языками.

<a id="decision"></a>
## Решение

<a id="1-breaking-configuration-changes-use-an-explicit-expandcontract-window"></a>
### 1. Несовместимые изменения configuration используют явное expand/contract window

Если установленная external configuration общая для active и rollback
binaries, rename key или изменение shape сначала должны выйти в overlap
release. Compatibility alias входит в typed bootstrap schema, явно отмечается
временным и исключается из fresh templates. Все неуказанные keys остаются
unknown и отклоняют startup.

Compatibility не выводится из похожести names и не реализуется в adapter.
Bootstrap владеет binding, migration notices и semantic conflicts.
Consumer получает только resolved current value.

Alias можно удалить, только когда supported rollback window больше не
содержит binary, которому он нужен. Удаление — отдельное contract change
с release-note и operator-guide evidence.

<a id="2-resolve-the-smb-alias-deterministically"></a>
### 2. Детерминированно разрешать SMB alias

Во время compatibility window 0.3.0:

- `encrypt: true` разрешается в `encryption: required`;
- `encrypt: false` — в `encryption: disabled`;
- при отсутствии обоих применяется secure default `required`;
- `encryption` сохраняет закрытую grammar `required|preferred|disabled`.

Mapping намеренно не превращает `true` в `preferred`: legacy operator request
шифровать должен удовлетворять текущей fail-closed guarantee при active
new binary. Rollback к old binary неизбежно возвращает его исторически
более слабое поведение.

<a id="3-reject-ambiguous-dual-configuration"></a>
### 3. Отклонять неоднозначную двойную configuration

Если одновременно присутствуют `encrypt` и `encryption`, startup отклоняется
в collect-all semantic preflight. Precedence не выбирает между свойствами
с разными names: молчаливый выбор любого скрыл бы stale configuration source.

Typed configuration сохраняет raw presence до завершения preflight и
предоставляет composition отдельный resolved selector. Поэтому configuration
record constructors остаются normalization-only и не бросают исключений
на ошибках оператора.

<a id="4-make-compatibility-observable-without-exposing-values"></a>
### 4. Сделать compatibility наблюдаемой без раскрытия values

Каждый effective external compatibility alias даёт одно startup warning со
stable `CONFIG.*` code, property name и replacement. Value и credentials
никогда не логируются. Migration catalog — общая bootstrap infrastructure;
SMB-specific mapping остаётся в endpoint configuration model.

IDE metadata отмечает alias deprecated. Current classpath defaults и production
templates показывают только `encryption`; guides документируют временный alias
и отклонение двух keys.

<a id="5-separate-binary-activation-from-final-configuration-contraction"></a>
### 5. Отделить binary activation от окончательного configuration contraction

Для существующей installation, чей rollback binary требует `encrypt`,
развернуть overlap release, сохранив этот key в operator-owned file.
После приёмки new release и retirement старой rollback point применить
отдельно проверенный candidate, заменяющий его на `encryption`.

Fresh installations и вновь созданные endpoints используют только `encryption`.

<a id="consequences"></a>
## Последствия

- Новый binary 0.3.0 запускается с существующим SMB key в стиле 0.2.x,
  а deployment transaction всё ещё может вернуться к older binary без смены
  общей configuration.
- Операторы получают warning до завершения contraction step.
- Stale environment или CLI alias не может молча перекрыть current YAML
  selector; двойное присутствие — startup error.
- Strict configuration остаётся строгой: compatibility расширяется только
  allowlisted tested alias и не ослабляет unknown-key detection.
- Reusable annotation, migration catalog, value-free reporter и template
  exclusion rule могут поддерживать будущие bounded migrations без помещения
  product configuration knowledge в domain, application или transport modules.

<a id="rejected-alternatives"></a>
## Отклонённые альтернативы

<a id="migrate-the-live-file-before-deploying-the-new-binary"></a>
### Мигрировать live file до развёртывания new binary

Предыдущий binary не понимает новый selector, поэтому automatic rollback
восстановил бы binary, который не может запуститься.

<a id="let-normal-property-precedence-resolve-both-keys"></a>
### Разрешить оба keys обычным property precedence

Keys имеют разные names и могут приходить из разных channels. Выбор одного
скрывает stale state и ставит rollback behavior в зависимость от source ordering.

<a id="keep-all-historical-aliases-indefinitely"></a>
### Сохранять все исторические aliases бессрочно

Постоянные aliases раздувают public language, мешают обнаруживать ошибки и
не дают strict configuration быть надёжным контрактом. Каждому alias нужен
явный removal gate.

<a id="implement-the-alias-in-the-smb-adapter"></a>
### Реализовать alias в SMB adapter

Adapters потребляют resolved transport settings и не должны проверять Spring
configuration keys. Это дублировало бы bootstrap policy и связывало
integration library с deployment migrations.

<a id="follow-up"></a>
## Дальнейшие шаги

- Сохранить SMB alias, пока он нужен supported rollback point.
- Зафиксировать release, выводящий эту rollback point, затем удалить alias,
  metadata warning и mapping в одном contraction change.
- Применять тот же критерий к будущим breaking keys: если binary rollback и
  external configuration не переключаются атомарно, сначала выпускать overlap.
