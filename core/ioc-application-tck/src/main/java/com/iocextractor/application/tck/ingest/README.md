# com.iocextractor.application.tck.ingest

## Назначение

Переиспользуемый behavioral contract `IngestionLedger`: lifecycle source
`CLAIMED -> SOURCE_ARCHIVED|FAILED`, recovery listing и семантика missing records.

**Правило слоя:** контракт видит только application-порт и модель.
Файловые/JDBC-детали задаёт fixture конкретного adapter-теста.
