# tools contract tests

## Назначение

Быстрые проверки safety boundary и воспроизводимости developer tools без
запуска systemd или внешних интеграций.

```bash
tools/tests/tools-contract-test.sh
```

Полные runtime smoke выполняются отдельно через `tools/dev/smoke.sh`: они
собирают/используют bootable application jar и создают repo-local `.dev/` state.
