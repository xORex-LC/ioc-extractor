# com.iocextractor.adapter.in.csv

## Purpose

Strictly decode and stream configured CSV deliveries behind the application
`DelimitedRecordReader` port.

## Rules

- charset decoding reports malformed and unmappable input;
- record separators and the exact configured header signature are validated;
- aliases are resolved before duplicate detection;
- rows are delivered synchronously and are never collected by the adapter;
- failures report structure and counts without echoing source cell values.
