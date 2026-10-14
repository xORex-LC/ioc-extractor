# Shared-library exploration

This directory holds the ongoing interview and code-backed assessment of
existing implementation candidates for reuse outside IOC Extractor.

As of 2026-09-19, all library analysis, design and extraction work shares the
`r030-libraries` branch by owner decision. Earlier branch names in dated
assessments identify historical baselines; they are no longer working branches.

- [Discussion worknote](shared-library-exploration.md): owner answers,
  observations, proposed boundaries, risks and the next question.

These are release worknotes, not accepted architecture or publication admission.

- [Diagnostics boundary proposal](lib-2-diagnostics-design.md): type disposition,
  proposed API, migration seams and validation requirements.
- [Spring Boot configuration proposal](configuration-library-design.md): existing
  mechanics, supported-contract limits, service ownership, extraction slices and
  publication-tooling prerequisites.
- [C0 qualification results](configuration-c0-analysis.md): 54 synthetic scenarios,
  actual Boot binding, extraction blockers and revised v1 support requirements.
- [LIB-3 logging proposal](lib-3-logging-design.md): SLF4J comparison, generic
  field/scope contracts, explicit masking, IOC migration and publication plan.

- [LIB-4 ETL assessment](lib-4-etl-analysis.md): existing runner capabilities,
  consumer evidence, contract limits and reasons to defer extraction.

- [LIB-5 local events assessment](lib-5-events-analysis.md): metadata and publisher
  reuse, Spring delivery semantics and the boundary with inter-service messaging.
