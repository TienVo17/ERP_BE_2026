# Phase 1 Rollback Guide

## When to roll back

Roll back when any of these is true after a release:

- Sign-in fails for every account, or sessions end at the first refresh.
- A master list or command returns a server error rather than a validation or conflict problem.
- The runtime role is denied a privilege it needs — visible as `permission denied for table …` in
  the backend log and a `500` at the API.
- Fewer than two administrators can sign in and administer.

Do not roll back for a single failing operator action that returns a *named* problem. `409`,
`403` and `400` with a code are the system working, not failing.

## Order

Roll the **frontend** back first. It is the cheap, reversible half, and the backend is the one
holding data.

### 1. Frontend

Redeploy the previous frontend artifact whole. Never mix: there is no supported configuration in
which part of the UI talks to the API and part falls back to browser storage. The mock modules no
longer exist in the shipped bundle, so a partial rollback cannot restore them — it can only produce
a build that fails to load.

### 2. Backend, schema-compatible case

If the new backend added no migration, redeploy the previous jar against the same database. The
schema is forward-compatible for additive changes and nothing further is required.

### 3. Backend, schema-changed case

`V015` changes the command/idempotency schema and preserves Buyer Order revision history. Do not
assume an earlier jar is compatible with a `V015` database; use a tested forward fix or restore a
backup when rollback is necessary. Applied migrations `V001`–`V015` are frozen, and Flyway will
refuse to run if their hashes change.

A migration is only reversed by restoring a backup. That is a data-loss event and needs an explicit
decision, not a runbook step.

## Restore drill

A restore returns the schema version captured by the dump, keeps rows written before that dump,
and drops rows written after it. Verify the restored Flyway version and required runtime privileges
before serving traffic again.

```bash
# 1. stop the serving instance so it releases its connections
# 2. recreate the database and restate the runtime role setting
psql -U <owner> -d postgres \
  -c "DROP DATABASE erp WITH (FORCE)" \
  -c "CREATE DATABASE erp OWNER erp_migration" \
  -c "ALTER DATABASE erp SET erp.runtime_role TO 'erp_runtime'"
# 3. restore
pg_restore -U <owner> -d erp --no-owner --role=<owner> erp-<timestamp>.dump
# 4. verify before restarting
psql -U <owner> -d erp -c "SELECT max(version) FROM flyway_schema_history WHERE success"
psql -U <owner> -d erp -c "SELECT has_table_privilege('erp_runtime','identity.user_role','DELETE')"
```

The second check matters: `ALTER DATABASE … SET erp.runtime_role` is a database-level setting that
does **not** travel inside the dump. Restate it before restoring, or the grants will be missing and
administration will fail exactly as it did before `V014`.

## After any rollback

- Confirm two administrators can still sign in and administer. If the rollback crossed a password
  change, the quorum may have dropped and every role command will be refused until a second account
  becomes credential-ready again.
- Record what was rolled back, the artifact pair that is now live, and the trigger.
