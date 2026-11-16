> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0026-fail-closed-smb-encryption-policy.md).

<a id="0026--fail-closed-smb-encryption-policy"></a>
# 0026 — Политика обязательного SMB-шифрования с закрытым отказом

<a id="status"></a>
## Статус

**Принято 2026-08-28. Реализация входит в candidate 0.3.0;
live qualification зашифрованных endpoints ещё не выполнена.**

Этот ADR узко заменяет конфигурацию `encrypt: boolean` и подразумеваемую
гарантию шифрования из ADR-0011. Он не меняет remote object ownership,
fetch/publish ledgers, managed-import semantics или доктрину events-as-hints.

<a id="context"></a>
## Контекст

ADR-0011 выбрал SMBJ и предоставил `ioc.sync.endpoints[].smb.encrypt`.
Реализация передавала boolean в `SmbConfig.withEncryptData`, тогда как
operator guides описывали настройку как шифрование SMB3 session.

Эти контракты не были эквивалентны. Настройка SMBJ объявляет предпочтение
клиента: шифрование используется при согласованных SMB3 dialect и encryption
capability, но default dialect set также допускает SMB 2.0.2/2.1 и незашифрованный
fallback. Adapter не проверял authenticated session до открытия share.
Поэтому `encrypt: true` доказывало намерение, а не документированную гарантию.

Security control обязан отличать optional preference от обязательной transport
policy. Обязательная policy должна отказывать до remote share operations,
не превращать deterministic mismatch в retry storm и одинаково действовать
для ordinary sync, managed import и выделенных CHANGE_NOTIFY sessions.

<a id="decision"></a>
## Решение

<a id="1-replace-the-boolean-with-a-closed-policy"></a>
### 1. Заменить boolean закрытой policy

Endpoint property — `ioc.sync.endpoints[].smb.encryption`, три значения:

- `required` — разрешать только SMB 3.0/3.0.2/3.1.1 и отклонять соединение,
  если authenticated session не будет шифровать packets;
- `preferred` — запрашивать и предпочитать SMB3 encryption, явно допуская
  незашифрованный SMB2/3 fallback;
- `disabled` — не объявлять client encryption preference.

`required` — встроенный default и default production template. Отсутствие
настройки безопасно; `preferred` или `disabled` — явное принятие оператором
network trust boundary.

Удалённый `encrypt` остаётся неизвестным strict configuration shape.
Configuration failure analyzer выдаёт value-free migration hint: оператор
выбирает `required`, `preferred` или `disabled`, а не получает молчаливый
compatibility mapping.

<a id="2-enforce-required-after-authentication-and-before-share-io"></a>
### 2. Обеспечить `required` после authentication и до share I/O

Для `required` SMB adapter настраивает SMB3-only dialect allowlist и объявляет
поддержку encryption. После authentication он спрашивает established SMBJ
session, эффективно ли data encryption. Только затем допустим
 tree-connect/share request.

Обе проверки обязательны. Dialect allowlist исключает SMB2 downgrade; session
check не даёт SMB3 server без успешно согласованных encryption capability/key
удовлетворить policy. Один connection helper используется pooled file
operations и dedicated CHANGE_NOTIFY sessions, поэтому вторичного
непроверенного пути нет.

<a id="3-report-policy-mismatch-through-a-reusable-transport-outcome"></a>
### 3. Сообщать policy mismatch через reusable transport outcome

Неуспешное обязательное security negotiation — ни неверные credentials,
ни transient network failure. Поэтому application transport taxonomy владеет
`SECURITY_POLICY_UNMET` с terminal disposition `FAIL`. Diagnostics отображают
его в `SYNC.SECURITY_POLICY_UNMET`; SMB-specific reason остаётся в adapter.

Outcome переиспользуем будущими transports с обязательными TLS, host-key или
mutual-authentication policies и не вводит SMB types в application layer.

<a id="4-qualify-both-configuration-and-effective-negotiation"></a>
### 4. Квалифицировать configuration и effective negotiation

Automated tests покрывают selector parsing, secure defaulting, legacy-key
failure analysis, построение SMB3-only dialects, allowed fallback modes и
terminal rejection ineffective required session.

Opt-in live SMB contract использует `required`; поэтому успешный первый share
request доказывает прохождение adapter effective-encryption gate. Release
evidence должны фиксировать server family и encryption policy. Незашифрованный
или SMB2-only negative stand остаётся желательным qualification evidence,
но при недоступности не имитируется как external pass.

<a id="consequences"></a>
## Последствия

- Существующие `encrypt` configurations должны мигрировать до startup.
- Production default больше не подключается к SMB2-only servers. Оператор
  может выбрать `preferred` или `disabled`, но слабая граница явна.
- Server, заявленный поддерживаемым с `required`, обязан пройти live encrypted
  negotiation; одного успеха обычных file operations недостаточно.
- Endpoint retry logic не повторяет deterministic security-policy mismatch.
- SMBJ остаётся в `adapter-transport-smb`; configuration parsing — в bootstrap;
  retry/diagnostic semantics остаются transport-neutral.

<a id="rejected-alternatives"></a>
## Отклонённые альтернативы

<a id="keep-encrypt-boolean-and-reinterpret-true"></a>
### Сохранить `encrypt: boolean` и переопределить `true`

Это могло бы обеспечивать шифрование, но сохраняло бы неоднозначное публичное
имя, не выражающее намеренно допущенный fallback. Операторы не могли бы
отличить совместимость от security requirement.

<a id="trust-server-side-encryption-required"></a>
### Доверять server-side `encryption required`

Server enforcement полезен как defense in depth, но вне application authority
и различается в Samba, Windows Server и NAS deployments. Клиент обязан
обеспечивать собственную настроенную policy.

<a id="treat-negotiation-failure-as-authentication-or-transient-io"></a>
### Считать negotiation failure authentication или transient I/O

Обе классификации вводят в заблуждение. Authentication могла пройти,
а retry того же несовместимого endpoint не может удовлетворить policy.

<a id="open-qualification-work"></a>
## Открытая квалификационная работа

- Выполнить `SmbEncryptionContractTest` на одобренном Samba stand с required
  server encryption и сохранить точные command/result.
- Выполнить тот же positive contract для каждой дополнительно заявленной
  Windows Server/NAS family.
- Когда появится одобренный negative stand, доказать отказ `required` для
  SMB2-only и encryption-disabled endpoints, а также документированный
  fallback contract `preferred`.
