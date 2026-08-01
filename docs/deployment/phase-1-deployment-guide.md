# Phase 1 Deployment Guide

Everything below was executed against PostgreSQL 16 and the packaged jar during the Phase 8
rehearsal. Commands that were not run are marked as such.

## What is deployed

| Artifact | Identity | Notes |
|---|---|---|
| Backend | `be-erp-0.0.1-SNAPSHOT.jar` (Spring Boot 4.1, Java 21) | Schema at Flyway `V015` |
| Frontend | Vite production build of `ERP` | Served as static files behind the same origin |
| Database | PostgreSQL 16 | Two roles: a migration owner and a runtime login |

Record the backend and frontend artifact versions **together**. The release is cross-repository but
not a distributed transaction: the backend goes first and the pair must be written down as one line
in the release record.

## Configuration

The application reads every secret from the environment or a mounted file. Nothing sensitive lives
in `application.yml`, and the repository contains no key, password or dotenv file.

| Variable | Meaning |
|---|---|
| `ERP_DB_URL` | JDBC URL |
| `ERP_DB_RUNTIME_USERNAME` / `ERP_DB_RUNTIME_PASSWORD` | The **runtime** login for the serving instance |
| `ERP_FLYWAY_ENABLED` | `true` only for the one-shot migration run |
| `ERP_ACTIVE_KID` | Key id of the active signing key |
| `ERP_PRIVATE_KEY_LOCATION` | Private RSA-3072 JWK file, RS256 |
| `ERP_PUBLIC_KEY_LOCATIONS` | Comma-separated **previous** public JWKs. Do not list the active key: the signing key's public half is added automatically and a duplicate `kid` aborts startup. |
| `ERP_BOOTSTRAP_ADMIN_*` | One-shot bootstrap only; disable immediately afterwards |
| `ERP_DEPLOYMENT_INSTANCES` | Must be `1`. Any other value refuses to start. |
| `ERP_TRUSTED_PROXY_ADDRESSES` | Proxies whose forwarded client IP may be trusted |

## Release order

### 1. Back up

```bash
pg_dump -U <owner> -d erp -Fc -f erp-<timestamp>.dump
```

Verify the restored schema version and runtime grants before restarting. A restore returns the schema
version captured by the dump, keeps rows written before it, and drops rows written after it.

### 2. Provision roles, once per environment

The migration owner and the serving login must be different, and the database must name the runtime
role before Flyway runs, because `V010`, `V014`, and `V015` read `erp.runtime_role` to build their grants.

```sql
CREATE ROLE erp_runtime LOGIN PASSWORD '<secret>';
GRANT erp_runtime TO erp_migration;
ALTER DATABASE erp SET erp.runtime_role TO 'erp_runtime';
```

If the setting is missing the migrations still apply, but they emit a notice and create no runtime
grants — the serving instance will then fail on its first write.

### 3. Migrate as the owner

Run the jar once with the owner credentials and `ERP_FLYWAY_ENABLED=true`, then stop it. Confirm:

```sql
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
```

All fifteen migrations must be present and successful. `V001`–`V015` are frozen; a hash mismatch
means someone edited an applied migration and the release must stop.

### 4. Bootstrap the first administrator

On the same one-shot run, set `ERP_BOOTSTRAP_ADMIN_ENABLED=true`, `ERP_BOOTSTRAP_ADMIN_LOGIN_ID`,
`ERP_BOOTSTRAP_ADMIN_NAME` and `ERP_BOOTSTRAP_ADMIN_SECRET_FILE` pointing at a mounted secret file.
The secret is never passed as an argument, echoed, logged or returned. Disable bootstrap for every
subsequent run.

### 5. Serve as the runtime role

Start the jar with the runtime credentials, `ERP_FLYWAY_ENABLED=false` and bootstrap disabled.
Exactly one instance. A second instance refuses to start and names the reason:

```text
Property: erp.security.singleInstanceDeployment
Reason: in-memory rate limiting supports one application instance only
```

### 6. Deploy the frontend

Serve the Vite build behind the same origin as the API, with `/api` proxied to the backend. The
refresh cookie is issued `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`, so **the origin
must be HTTPS**. Over plain HTTP the browser silently discards the cookie and every session ends at
the first refresh.

## Go-live sequence for administrators

This order is enforced by the recovery quorum, which counts only accounts that owe no password
change and is evaluated after every role, status, override and reset command.

1. Sign in as the bootstrap administrator. The response is a `password_change` challenge, not a
   session; it cannot read business data.
2. Complete the forced change. The challenge is one-time.
3. Create the second administrator account with a temporary password.
4. **Have that account sign in and complete its own forced change first.** Assigning the ADMIN role
   before this step fails with `RECOVERY_ADMIN_REQUIRED`, because a user still owing a password
   change does not count towards the quorum, and while only one administrator qualifies *every*
   role command is refused.
5. Assign the ADMIN role to the second account, then verify it can administer.
6. Create business users. The SALE role already carries its approved baseline — `V011` seeds VIEW,
   CREATE and UPDATE on Raw Material, Finished Good, Customer, Supplier, Process and ETC, and no
   ARCHIVE or ADMIN permission. Assigning the role is enough; no permission editing is required.

## What operators should expect

- Archive replaces delete for every master. A record referenced by business data refuses to archive
  with `MASTER_IN_USE`. A UOM already carried by a raw material is an exception and stays
  archivable: the guard stops an archived reference being *selected*, it does not freeze rows that
  already carry one.
- Lists page at 25 by default, accept up to 100, and refuse an unknown sort or filter field with a
  validation problem naming the parameter.
- The IP allowlist stores entries but **does not enforce them** in Phase 1. The UI says so.
- Media is deferred. There is no image or avatar upload anywhere.
- Login is limited to 10 attempts per minute per trusted client IP and refresh to 120. There is no
  account lockout: the window simply reopens, and no administrator action is needed.

## Not rehearsed

- Key rotation over a real retention period. The application accepts current and previous public
  keys by `kid`; keeping the previous key file for at least the access TTL plus clock skew is a
  secret-store obligation the process cannot enforce.
- Audit and login-event retention jobs. Expired rows remain unusable before any cleanup runs.
- A browser-driven journey. The Phase 8 journey exercised the same TLS origin and the same cookies
  through an HTTP client; the DOM-level pass is still owed.
