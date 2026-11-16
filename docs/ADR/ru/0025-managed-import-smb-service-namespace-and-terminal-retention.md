> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0025-managed-import-smb-service-namespace-and-terminal-retention.md).

<a id="0025--managed-import-smb-service-namespace-and-terminal-retention"></a>
# 0025 — Служебное SMB-пространство managed import и terminal retention

<a id="status"></a>
## Статус

**Принято 2026-08-26. Контракт утверждён; реализация и live qualification
ещё не выполнены.**

Этот ADR узко расширяет ADR-0024 для ownership SMB managed-import namespace,
capability admission и source-side terminal retention. Он не меняет read-only
ordinary sync-fetch contract ADR-0011 и не делает CSV вторым источником истины.

<a id="context"></a>
## Контекст

Managed dataframe import — не обычный remote fetch. Он принимает ownership
source occurrence через server-side rename, сохраняет восстанавливаемый
remote processing object, затем переносит его в terminal или quarantine
namespace. Это требует mutation rights на частично доверенной SMB boundary.

Candidate 0.3.0 сейчас создаёт по требованию
`<source>/.ioc-managed-import/{processing,terminal,quarantine}`. Успешный или
отклонённый occurrence перемещается туда, а существующий import retention
удаляет только protected local terminal unit, workspace, local immutable
snapshot, dataframe receipt и service-ledger row. Поэтому у remote terminal
или quarantine object нет bounded cleanup authority. У replays также нет
remote processing object, хотя общий finalization path всё ещё может
запрашивать transport disposition.

Это два связанных дефекта границ:

- создание directories не доказывает ни least privilege, ни producer isolation;
- удаление последнего durable ledger authority до source-side cleanup сделало
  бы failed remote purge необнаружимым после restart.

Application не может безопасно настраивать или аудировать произвольные share,
Samba, NTFS или NAS ACLs. Но она может требовать operator-provisioned namespace
и доказать private-namespace operations, не требующие потребления producer
 data, до допуска source files.

<a id="decision"></a>
## Решение

<a id="1-make-the-private-smb-namespace-an-operator-provisioned-boundary"></a>
### 1. Private SMB namespace — граница, подготовленная оператором

Каждый SMB managed-import source использует фиксированный namespace под своим
настроенным source directory:

```text
<source>/.ioc-managed-import/
├── processing/
├── terminal/
├── quarantine/
└── probe/
```

Все четыре directories должны существовать до допуска source к intake.
Application не должна создавать их, создавать share, менять ACLs или пытаться
чинить permissions. Отсутствующие directories — capability failure, а не
запрос runtime provisioning.

Операционное разделение authority:

- Producer может публиковать завершённые candidates в `<source>`, но не должен
  list/read/create/rename/delete внутри `.ioc-managed-import`.
- Service identity может list/read candidates и выполнять точные server-side
  moves, необходимые для claim.
- Service identity может create/read/rename/delete только собственные
  tokenized regular files внутри private namespace.
- Не связанные consumers не получают доступа к private namespace.

Share permissions и filesystem ACLs объединяются по правилам server family.
Оператор владеет обоими слоями. Source не должен разделять private namespace
с другим настроенным source или credential principal другого trust level.

<a id="2-gate-each-smb-source-on-an-executable-capability-probe"></a>
### 2. Допускать каждый SMB source через исполняемый capability probe

Configuration validation остаётся value-free и offline. До первого claim
демон выполняет endpoint-serialized probe для каждого включённого SMB import
source, пока intake этого source закрыт. Probe должен доказать:

1. Connect и authenticate.
2. List/stat source и каждого обязательного private directory.
3. Создание уникального token без содержимого в `probe`.
4. Read/stat token.
5. Server-side move из `probe` в `processing`, затем в `terminal`.
6. Отклонение destination collision без replacement.
7. Удаление точного terminal token и cleanup всех probe artifacts.

Probe не несёт IOC или producer data. Он использует reserved token grammar,
никогда не доверяет listed path как delete target и выполняет best-effort
cleanup после каждого failed step. Failed cleanup остаётся видимым typed
source-health fact. Token намеренно не создаётся в producer root: это требовало
бы лишнего create-child authority там.

Поэтому probe не доказывает share mode настоящего candidate или rename
source-to-processing. Каждый claim всё ещё сравнивает candidate evidence и
отказывает закрыто на этой границе. Live deployment qualification использует
producer identity для публикации fixture и service identity для claim.

Этот positive probe не доказывает отказ доступа producer. Оператор обязан
выполнить negative producer-identity checks из deployment runbook.
Application не должна заявлять аудит server ACLs.

Transient network/server failure оставляет закрытым только этот source
с `DEGRADED` и bounded retry. Детерминированные missing-directory,
authorization, collision-semantics или object-type mismatch оставляют source
закрытым и `DOWN` до успешного последующего probe. Процесс может продолжать
обслуживать прочие sources и recovery work; одной remote unavailability
недостаточно для остановки JVM. Invalid local configuration по-прежнему
отклоняет startup через существующую strict configuration boundary.

Capability evidence инвалидируется после authorization/capability failure
или endpoint generation change, способного изменить share semantics.
Periodic listing остаётся intake correctness path после admission;
CHANGE_NOTIFY всё ещё только latency hint.

<a id="3-generalize-at-the-semantic-boundary-not-at-the-smb-boundary"></a>
### 3. Обобщать на семантической, а не SMB-границе

Hardening реализуется через transport-neutral application contracts.
Он не должен создавать SMB-specific retention service, readiness coordinator,
delivery token algorithm или local snapshot implementation.

Целевое разделение ответственности:

- Source access обнаруживает, выполняет claim, revalidate/read и disposition
  occurrence; local filesystem и SMB adapters реализуют ownership mechanics.
- Source-capability port возвращает один application-owned readiness result;
  adapters реализуют операции, осмысленные для их transport.
- Один import snapshot store устойчиво публикует, проверяет, разрешает и
  удаляет private local immutable snapshots для любого source transport.
- Один import retention service владеет outcome eligibility и упорядоченной
  cleanup state machine.
- Terminal-source-retention port удаляет optional transport-owned terminal remnant.
- Protected local terminal store удаляет или архивирует общую source/report replay unit.

Имена выше описывают capabilities, а не обязательные Java type names.
Application services и values остаются в `ioc-application`; общая keyed
serialization — в `platform-concurrency`; filesystem persistence — в
local-filesystem adapter; SMB session, path, status и share semantics —
в `adapter-transport-smb`. Ни один adapter не зависит от другого.
Capability, claim, disposition и purge используют существующие общие
`SmbSessionPool`, share-client primitives, exception mapping и CHANGE_NOTIFY
infrastructure адаптера; hardening не должен создавать второй SMB connection
или error-classification stack.

Immutable managed-object identifier выводится один раз из delivery ID через
application-owned value/factory и переиспользуется local и SMB adapters.
Adapter-stable candidate token остаётся detection/claim evidence и не должен
использоваться как cleanup locator: он может кодировать producer-controlled
metadata и имеет другую семантику.

Выделение value побайтово сохраняет существующую формулу candidate — lowercase
SHA-256 от UTF-8 delivery ID, чтобы уже claimed local и SMB objects оставались
принимаемыми для recovery. Consolidation меняет code ownership, а не durable
identity. Аналогично, общий snapshot store обязан продолжать разрешать
`local-snapshot-v1:` и `smb-snapshot-v1:` references при recovery и retention;
он не должен переписывать или отбрасывать закреплённые evidence на месте.

Terminal-source-retention command содержит только stable application value
objects: delivery ID, source ID, managed-object ID и expected terminal outcome.
В нём нет SMBJ type и переданного caller local/remote path. SMB adapter
восстанавливает единственное разрешённое location из настроенного source,
фиксированного namespace, проверенного managed-object ID и expected outcome.

Application cleanup service не ветвится по `SMB`. Composed source adapter
сообщает, оставила ли forward disposition terminal source remnant. Local
occurrence может сообщать об отсутствии отдельного remnant: protected local
terminal unit уже является его retained evidence; SMB сообщает о tokenized
remote terminal/quarantine remnant. Одна application state machine обрабатывает оба.

Application-owned materialization use case объединяет source access и snapshot
store. Source adapter предоставляет claimed bytes и before/after source
evidence через application/JDK contracts; snapshot adapter владеет local
persistence. Ни один adapter не вызывает и не импортирует другой.

Replay создаётся из retained local evidence. Он сообщает об отсутствии
transport-owned source remnant и должен пропускать remote disposition и source
retention без SMB-specific conditional. Replay никогда не переиспользует
candidate token или remote locator исходного occurrence.

Общая age/count semantics продолжает использовать существующую application
`RetentionPolicy` как pure oracle; endpoint/source scheduling продолжает
использовать существующие keyed-concurrency primitives. Import cleanup saga
остаётся в import application package: порядок receipt и delivery ledger —
не общепроектный generic housekeeping contract.

<a id="4-bound-remote-terminal-evidence-with-the-delivery-ledger-as-last-authority"></a>
### 4. Ограничить remote terminal evidence, сохранив delivery ledger последней authority

Protected local source/report unit — audit и replay authority. Remote
terminal/quarantine object — восстанавливаемое transport evidence, а не второй
авторитетный archive. Для него действует та же outcome age eligibility,
что у local terminal unit:

- Success использует настроенное success retention window.
- Partial/rejected outcomes используют failure retention window.
- Local `archive` перемещает protected local unit в настроенный archive,
  но V1 всё равно удаляет expired remote object.
- Remote archive/cold tiering вне scope.

Для eligible delivery cleanup упорядочен:

1. Если disposition записала transport-owned remnant, удалить его точный
   terminal/quarantine regular file; иначе явно выполнить no-remnant branch
   без вызова transport adapter.
2. Удалить или архивировать protected local terminal unit.
3. Удалить sealed workspace.
4. Удалить private local immutable snapshot.
5. Удалить dataframe import receipt.
6. Compare-and-set удалить terminal service-ledger row.

При ошибке шага последующие не исполняются, ledger row остаётся durable retry
authority. Каждый завершённый шаг идемпотентен, restart безопасно повторяет
последовательность. Ожидаемое отсутствие точного remote managed-object ID —
успех. Object в `processing`, неверный outcome directory, invalid managed-object
ID, non-regular object или противоречивые evidence дают fail closed и никогда
не удаляются retention.

Adapter предоставляет только exact regular-file deletion для этого use case.
Нет recursive directory delete, wildcard delete, delete-by-listing-result
или arbitrary-path retention API. Processing-orphan recovery остаётся отдельным;
retention никогда не должна удалять processing object.

<a id="5-preserve-bounded-coordination-and-safe-observability"></a>
### 5. Сохранить ограниченную координацию и безопасную наблюдаемость

Capability, ownership, disposition и source-retention operations разделяют
существующую logical endpoint serialization. Разные endpoints могут двигаться
параллельно. Не вводятся новый broker, timer на delivery или remote cleanup
queue: terminal delivery ledger и periodic retention reconcile — correctness authorities.

Health, diagnostics и logs раскрывают source ID, stable diagnostic code,
probe phase, terminal outcome, aggregate backlog и age. Они не раскрывают
host, share, username, source path, filename, remote token, digest,
raw exception или IOC content.

Live qualification требует разных producer и service identities. Она должна
доказать positive service operations и невозможность для producer войти в
private namespace. Samba — первая reference family. Windows Server и NAS
families остаются явными qualification skips до прохождения того же corpus
на этих системах.

<a id="consequences"></a>
## Последствия

Положительные последствия:

- Intake не может молча расширить SMB authority, создав себе namespace.
- Application доказывает реальные mutation primitives до потребления producer
  data, не выдавая себя за ACL auditor.
- Рост remote terminal/quarantine становится ограниченным и восстанавливаемым
  после restart.
- Retained ledger row всегда объясняет и повторяет незавершённый cleanup.
- Replay больше не зависит от несуществующего remote occurrence.
- Application layer остаётся transport-neutral, SMB deletion — adapter-owned
  exact-object operation.

Стоимость и компромиссы:

- Включение SMB import теперь требует явных operator provisioning и
  negative-permission checklist.
- Source может оставаться закрытым при здоровых daemon и остальных sources.
- Capability probe ненадолго создаёт и перемещает reserved empty objects.
- Remote/local terminal evidence могут исчезать в немного разные моменты
  при retry, хотя durable state продолжает безопасно сходиться.
- Каждая новая заявленная SMB server family требует live mutation/retention
  qualification, а не только read/list compatibility.

<a id="rejected-alternatives"></a>
## Отклонённые альтернативы

- **Создавать directories в runtime.** Смешивает application behavior со
  storage provisioning и не устанавливает least privilege.
- **Полный ACL audit по SMB.** Effective authorization специфична для server
  family и включает identities, которые сервис не может безопасно impersonate.
  Positive capability плюс operator-run negative checks — честная граница.
- **Удалять remote object сразу после local snapshot publication.** Удаляет
  полезные forward-recovery и operator evidence до terminal outcome.
- **Бессрочно хранить remote terminal objects.** Создаёт неограниченные capacity
  и confidentiality exposure вне объявленной retention policy.
- **Сначала удалять local evidence, потом remote.** Crash может удалить
  последний replay artifact, оставив неотслеживаемую remote copy.
- **Recursive remote cleanup.** Широкое удаление не нужно и превращает
  retention bug в риск потери данных всего share.
- **Remote archive в V1.** Добавляет второй archive authority, ACLs и recovery
  saga без заявленной consumer need.
- **Расширить generic `FileTransport` managed-import rename/delete paths.**
  Ordinary sync fetch read-only; перенос mutating ownership contract в эту
  abstraction ослабляет обе границы.

<a id="implementation-and-validation-follow-up"></a>
## Последующая реализация и проверка

Последовательность реализации и исполняемая acceptance matrix находятся
в версионируемой [SMB hardening worknote](https://github.com/xORex-LC/ioc-extractor/blob/1ad9f8b483575c136c2eb5529869fb01fa53ad00/docs/worknote/0.3.0/dataframe-import/smb-hardening.md).
До прохождения этих gates ADR — спроектированный контракт, а не evidence
того, что candidate уже обеспечивает его.

Основные protocol/server references, использованные при qualification design:

- [MS-FSCC rename semantics](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-fscc/3668ae46-1df5-4656-b481-763877428bcb);
- [Рекомендации Microsoft по SMB security](https://learn.microsoft.com/en-us/windows-server/storage/file-server/smb-security);
- [Справочник Samba `smb.conf`](https://www.samba.org/samba/docs/current/man-html/smb.conf.5.html).
