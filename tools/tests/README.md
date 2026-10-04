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

`python3 tools/tests/library-publication-test.py` проверяет offline-контракты
неизменности bundle, отказ при конфликте артефактов, частичную публикацию и
повторное использование Central deployment. Подпись проверяется реальным GnuPG с одноразовым тестовым ключом.
Сеть и рабочие секреты не используются.

`python3 tools/tests/processing-route-comparison-test.py` проверяет независимое
задание cardinality для документа/CSV, попарную статистику и отсутствие
искусственного отношения для нулевых GC counters. Сам opt-in benchmark
запускается отдельно через `make processing-route-comparison`.

Тот же набор проверяет полную диагностическую эквивалентность selected-версий
и сохранение пар при чередовании before/after в optimization comparison.

Capacity contracts проверяют независимую очистку host/IP, сохранение URL с
путями в aggregate, KEEP_FIRST/LAST_NONEMPTY источники и отказ при лишних,
потерянных либо изменённых public CSV rows. Provisioned SMB запуск остаётся
отдельным opt-in evidence через `make data-processing-capacity`.

Контракты retention проверяют очистку тяжёлого состояния после успешного и
ошибочного fork, сохранность входов/логов/frozen fixtures, явный opt-in для
баз и отказ от очистки чужих каталогов и symlink targets. При нехватке места
новая JVM не запускается.
