# com.iocextractor.adapter.out.manifest.json

## Назначение

Пакет реализует `SliceManifestCodec` как deterministic pretty UTF-8 JSON.
Application model остаётся Jackson-free: wire names, field order, indentation и
version gate локализованы в adapter-owned document records.

## Структура

| Файл | Назначение |
|---|---|
| `JacksonSliceManifestCodec.java` | Model↔document mapping, deterministic serialization и strict v1/v2 decoding |
| `package-info.java` | Package boundary |

## Контракт bytes

Root order: `manifest_version`, `slice_id`, `run_id`, `profile`, `created_at`,
`output_mode`, `plan_hash`, `format`, `artifacts`. Nested documents также
имеют explicit order. Pretty-print фиксирован: `\n` и два пробела, без
platform-dependent newline. Codec не считает hash: caller хэширует именно
возвращённые bytes, которые записаны в `manifest.json`.

V1 сохраняет прежние bytes и означает CRLF с кавычками у не-null значений.
V2 дополняет `format` обязательными `record_separator`, `header_quoting` и
`value_quoting`. Новые настройки не применяются к уже сохранённым slices.

## Ошибки

Malformed JSON, missing/invalid/duplicate fields, unknown fields и unsupported
version преобразуются в `IocExtractorException`. Диагностику `EXPORT.MANIFEST_INVALID` эмитит
filesystem producer, который знает run/path context.
