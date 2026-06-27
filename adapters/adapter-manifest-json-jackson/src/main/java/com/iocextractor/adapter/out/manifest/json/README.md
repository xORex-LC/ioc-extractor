# com.iocextractor.adapter.out.manifest.json

## Назначение

Пакет реализует `SliceManifestCodec` как deterministic UTF-8 JSON.
Application model остаётся Jackson-free: wire names, field order и version gate
локализованы в adapter-owned document records.

## Структура

| Файл | Назначение |
|---|---|
| `JacksonSliceManifestCodec.java` | Model↔document mapping, deterministic serialization и strict v1 decoding |
| `package-info.java` | Package boundary |

## Контракт bytes

Root order: `manifest_version`, `slice_id`, `run_id`, `profile`, `created_at`,
`output_mode`, `plan_hash`, `format`, `artifacts`. Nested documents также
имеют explicit order. Codec не считает hash: caller хэширует
именно возвращённые bytes, которые записаны в `manifest.json`.

## Ошибки

Malformed JSON, missing/invalid fields и unsupported version преобразуются
в `IocExtractorException`. Диагностику `EXPORT.MANIFEST_INVALID` эмитит
filesystem producer, который знает run/path context.
