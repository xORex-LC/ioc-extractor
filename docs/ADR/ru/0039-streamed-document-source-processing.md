> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0039-streamed-document-source-processing.md).

<a id="adr-0039--workspace-owned-streamed-document-source-processing"></a>
# ADR 0039 — Потоковая обработка источника документа под владением workspace

Статус: принято, 2026-10-08.

<a id="context"></a>
## Контекст

Запечатанный workspace подготовки из ADR 0036 ограничил Router candidates и
winners, но сохранял полные исходную/refanged строки, extraction decisions и
атрибутированные occurrences до routing. HTML parser Tika дополнительно
удерживал полный DOM. HTML с миллионом occurrences исчерпывал квалифицированный
JVM heap ещё до preparation. Fatal worker error мог оставить самый ранний
durable admission скрытым за незавершённым in-process job до restart.

<a id="decision"></a>
## Решение

Допущенный document workspace владеет декодированными и refanged UTF-16 files.
Фиксированный двухстраничный cache предоставляет полный текст как `CharSequence`.
Поэтому regex anchors и matches видят один документ, включая переходящие через
страницы tokens; offsets остаются абсолютными UTF-16 позициями в полностью
refanged тексте. Каждое literal refang rule делает упорядоченный неперекрывающийся
дисковый проход без повторного поиска в своей замене. В production pipeline
нет materializing fallback.

Ленивые match cursors обходят IOC types в настроенном глобальном порядке
приоритета. Индексируемые private accepted spans разрешают пересечения без
bitmap размером с документ. Scratch store сохраняет порядок обнаружения decisions
и читает accepted occurrences по position/стабильному порядку обнаружения.
Marker cursors объединяют исходное и NBSP-normalized views по position,
наиболее длинному совпадению, настроенному порядку patterns и приоритету
исходного view. Attribution и Router потребляют закрываемые cursors;
canonical identity, observation selection, участие в diagnostics и receipts
сохраняют существующие контракты.

Tika adapter использует progressive jsoup parsing с удалением завершённых
поддеревьев для HTML и SAX extraction для DOCX. Регионы, в которых HTML repair
может перемещать текст, сохраняются целиком до завершения. Вложенность более
256, чрезмерные region text, attributes или node count и embedded
`srcdoc`/data attributes отклоняют preparation до canonical writes. Это явные
input admission restrictions. Остальные Tika formats по-прежнему поддерживаются
своими parsers; решение не утверждает, что каждый third-party parser имеет
ограниченную рабочую память.

`maximum-row-bytes` также ограничивает HTML parser region в characters;
`maximum-field-bytes` ограничивает материализованные IOC и marker values.
Text store и source-decision store используют каждый не более четверти
per-workspace disk limit; общий factory disk guard остаётся авторитетным.
Длина текста должна помещаться в диапазон UTF-16 offsets Java regex.
Native caches разделены между source scratch и reducer. Admission резервирует
`cache-kib * 1024 + maximum-row-bytes * 64 + 65536` bytes на invocation,
включая консервативное parser/codec space. Это не универсальная RSS-формула:
фиксированные издержки JVM и parser требуют измерения процесса. Глобальные
настроенные budgets памяти и диска не меняются.

Ordered dispatch ограничивает active window меньшим из настроенного window
и допущенной workspace capacity. Готовые более поздние документы не могут
исчерпать leases, пока более ранняя dispatched preparation ожидает захвата.
Это также допускает deployment с одной lease без увеличения memory budget.

Source scratch — удаляемое состояние; оно убирается до sealing, после неуспешной
preparation и при close. Replay sealed workspace заново строит source scratch;
оно никогда не становится canonical или recovery authority. Parser semantics
и input limits участвуют в processing policy fingerprint. Canonical receipts
и startup recovery barrier остаются авторитетными.

Fatal worker errors видимо останавливают intake и promotion scheduling,
освобождают готовые owned preparations и оставляют durable admissions pending
для restart recovery. Уже исполняемая promotion владеет своей transaction
boundary и cleanup. Failed dispatcher object не может перезапустить себя.
Обычные recoverable failures сохраняют retry/rejection policy.

<a id="consequences"></a>
## Последствия

Source processing обменивает удержание документа в heap на ограниченные buffers
и private disk passes. Finite batch APIs остаются полезными для независимых
test oracles и явных callers; production использует streamed contracts.
Точные сравнения text/offset/priority/attribution, настоящие DOCX fixtures,
quota failures и fatal-worker recovery tests квалифицируют эту границу.
Whole-service memory и latency measurements остаются отдельно от mechanism
tests. Большие canonical transactions и private reducer capacity — независимые
последующие решения.

Parser references: [jsoup StreamParser](https://jsoup.org/apidocs/org/jsoup/parser/StreamParser)
и [выбор Tika OOXML extractor](https://github.com/apache/tika/blob/3.3.2/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ooxml/OOXMLExtractorFactory.java).
