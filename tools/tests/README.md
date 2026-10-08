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

`python3 tools/tests/processing-stage-capacity-test.py` проверяет чередование
CAP-3 JVM pairs, отказ при потере samples или semantic mismatch, разделение
статистики по размеру и удаление временных классов при ошибке без публикации
неполного отчёта.

Контракты retention проверяют очистку тяжёлого состояния после успешного и
ошибочного fork, сохранность входов/логов/frozen fixtures, явный opt-in для
баз и отказ от очистки чужих каталогов и symlink targets. При нехватке места
новая JVM не запускается.

`python3 tools/tests/document-workspace-capacity-test.py` проверяет отказ
неполного evidence и удаление приватного runtime/SQLite state при oracle failure
и timeout JVM.

`python3 tools/tests/service-capacity-test.py` проверяет равенство HTML/DOCX
fixtures, выбор победителей в дисковом oracle, отказ при повреждении CSV и
слотов, приватизацию абсолютных путей, границы writer-окна, поколения heap,
порядок остановки при upgrade failure и целостность остановленного backup,
слотов и схемы, единицы PSI, регрессию счётчиков и отказ сборщика метрик,
обнаружение OOM живой JVM и очистку после ошибки сохранения evidence. Полный
запуск под user-systemd выполняется отдельно через `make service-capacity`.
