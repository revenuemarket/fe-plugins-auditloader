# tools

Checks that need no build and no dependencies. They live outside `src/` on purpose —
the Docker image build runs `mvn package`, and this repo has no test harness, so
adding one would change how the plugin jar is produced.

## BatchCapCheck.java

Guards the audit batch byte budget (`AuditLoaderPlugin.loadIfNecessary`).

```bash
java tools/BatchCapCheck.java     # JDK 11+, single-file source launch
```

It replays the LoadWorker loop (`poll -> assembleAudit -> loadIfNecessary`) with the
traffic measured on the production cluster the day the FE died, and asserts the batch
stays inside the budget. It also shows what the old element-count comparison did with
the same input, so the regression cannot come back unnoticed.
