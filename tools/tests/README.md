# tools contract tests

## Назначение

Быстрые проверки safety boundary и воспроизводимости developer tools без
запуска systemd или внешних интеграций. Включают color/output contract,
детерминированные fixtures, workspace-aware ECS queries и машинный cold-start
context.

```bash
tools/tests/tools-contract-test.sh
```

Полные runtime smoke выполняются отдельно через `tools/dev/smoke.sh`: они
собирают/используют bootable application jar и создают repo-local `.dev/` state.
Lifecycle correctness/load smoke выполняется через
`tools/dev/lifecycle-smoke.sh`; contract suite проверяет только его syntax/help
boundary, а не запускает долгий daemon scenario.
