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

`CsvImportValueValidatorRegistry` checks whole-cell IOC types through shared
refang/extraction/classification collaborators without rewriting imported cells.
Network cells additionally pass the shared address parser: recognizing a network
category does not by itself prove valid IP octets, host syntax or port range.
It supports general network addresses, clean domains, bare IPv4, detailed
addresses, generic or algorithm-specific hashes, and canonical signed 64-bit
integers. Its public rule-key catalog is also the composition root's preflight
authority. Integer validation prevents textual keys from diverging from INTEGER
storage values; these checks are opt-in per contract column.
