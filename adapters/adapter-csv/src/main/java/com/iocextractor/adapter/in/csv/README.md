# com.iocextractor.adapter.in.csv

## Purpose

Strictly decode and stream configured CSV deliveries behind the application
`DelimitedRecordReader` port.

## Rules

- charset decoding reports malformed and unmappable input;
- record separators and the exact configured header signature are validated;
- empty physical lines are ignored by Commons CSV, including trailing lines;
  delimiter-only, whitespace-only and structurally short records remain input
  records and are not silently discarded;
- aliases are resolved before duplicate detection;
- header-only probes support exact-one recognition without parsing payload rows;
- row and column limits fail closed, while decoded field and logical-record
  limits are enforced by a streaming reader before Commons CSV tokenization;
- rows are delivered synchronously and are never collected by the adapter;
- failures carry a stable value-free reason and report structure/counts without
  echoing source cell values.

`CommonsCsvImportValueTransformRegistry` exposes the existing validated export
transform family through the framework-free import port; it does not duplicate
transform implementations.
