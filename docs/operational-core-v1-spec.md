# Operational Core v1

## Status and source priority

This is the production contract for Phase 1. Historical ERP BRDs, completed mock plans, mock storage, and frontend types remain read-only evidence; they are **superseded for runtime behavior**. Resolve conflicts in this order:

1. confirmed Phase 1 decisions;
2. this specification;
3. [OpenAPI v1](api/erp-v1-openapi.yaml) and [API conventions](api/api-conventions.md);
4. implementation and tests;
5. historical mock/BRD behavior.

The database design is retained at [ERP PostgreSQL architecture](database/erp-postgresql-architecture.md); this document does not duplicate its D1-D12/schema decisions.

## Release boundary

Production serves only `/api/v1` Auth, Admin, Currency, UOM, monthly Exchange Rate, Customer/PIC, Supplier/PIC, Process, Raw Material, and Finished Good. All business APIs require bearer access tokens except the listed cookie-backed Auth endpoints. The SPA retains access JWTs only in memory.

Buyer Order, Production, Delivery, Debit, and Stock are deferred: they have no v1 transaction endpoints, persistence implementation, production routes, or production renderer in Phase 1. An optional development demo may retain mock storage only behind a separate demo route/renderer/API boundary. It must not import production master data or be reachable in production merely because a navigation item is hidden.

Media is deferred. Phase 1 has no upload/object storage or binary persistence. Avatar, picture, Finished Good image, Data URL, image-name, and token fields are absent from operational responses and examples. No localStorage mock identity/master/transaction data is migrated to PostgreSQL.

## Lifecycle and master behavior

- Lists are server-side, zero-based `page`, default `size=25`, maximum `size=100`, with allowlisted filters and sort fields. See [API conventions](api/api-conventions.md).
- Mutable resources expose UUID `id`, optimistic `version`, lifecycle `status`, and UTC timestamps where the resource is audited.
- Numeric quantity, money, and exchange-rate values are JSON strings; the PostgreSQL precision/rounding policy remains authoritative.
- Create and update requests reject unknown members; clients cannot supply IDs, versions, status, timestamps, audit actors, permission decisions, or calculated values unless a request schema explicitly allows it.
- Archive is an ADMIN-only lifecycle transition for UOM, exchange rate, customer, supplier, process, raw material, and finished good. It replaces the old UI's generic Delete action. An in-use master cannot be hard-deleted; archive preserves references and history. SALE neither sees nor calls archive operations.
- Currency is a read-only reference in Phase 1. Exchange rates are one record per effective month carrying both the VND/USD and WON/USD values, matching the unique monthly row in persistence.
- Customer and Supplier own their PIC contacts through explicit sub-resource commands, so each contact keeps a stable ID and version. Contacts carry `division` and at most one active `isDefault` per owner; an owner update never recreates or silently drops them.
- IP allowlist endpoints manage entries only. The list response always reports `enforced: false`; there is no global enable/disable endpoint, persisted toggle, or access blocking in Phase 1. An entry's `active` flag records intent, not enforcement.
- Immutable business keys are absent from update requests: customer `shortName`, process `qrValue`, user `loginId`, and role `code`.
- The migration-seeded `SYSTEM` principal owns reference rows and is never administrable: it is excluded from `/admin/users`, from role and override targets, from password reset, and from the recovery-admin count.
- Phase 1 exposes no usage-lock flag. The old mock booleans `usedInPurchaseOrder`, `usedInTransactions`, `usedInProduction`, and `usedInFgRouting` are removed rather than reimplemented, because every transaction module that could create usage is deferred and no production data can be in use yet. Guards remain server-side and derived from real relationships, returning `MASTER_IN_USE`. When transaction modules land, they must add a derived read-only usage projection to this contract rather than accept a client-supplied flag.

## Authorization catalog

The catalog is exactly the permission rows seeded by `V009__seed_reference_data.sql`: `RAW_MATERIAL`, `FINISHED_GOODS`, `CUSTOMER`, `SUPPLIER`, `PROCESS`, and `ETC` each define `VIEW`, `CREATE`, `UPDATE`, `ARCHIVE`; transaction modules retain only their seeded actions; `ADMIN` defines `MANAGE_USERS`, `MANAGE_ROLES`, `MANAGE_ALLOWLIST`, and `VIEW_AUDIT`.

`ADMIN` receives every V009 permission. `SALE` baseline is specifically `VIEW`, `CREATE`, and `UPDATE` for the six business-master modules above, and no `ARCHIVE`, no ADMIN permission, and no transaction grant. This Phase 1 grant is applied by a forward migration, not by editing V009. Effective permission precedence is explicit user `DENY` > explicit user `ALLOW` > role grant > default `DENY`; a DENY therefore overrides ADMIN or SALE role membership.

Administrative authority is split across separate commands so that user administration cannot silently grant authority: profile, status, and password reset require `ADMIN:MANAGE_USERS`, while role assignment and permission overrides require `ADMIN:MANAGE_ROLES`. Overrides reference seeded permission IDs, so no client can invent a module or action. See [authentication and authorization](security/authentication-and-authorization.md).

## Authentication boundary

`GET /auth/csrf`, `POST /auth/login`, `POST /auth/refresh`, and `POST /auth/logout` are cookie-backed and CSRF-protected; refresh and logout require the `ERP_REFRESH` cookie together with the CSRF header. Login accepts only login ID and password, never a user-selected role. Login/logout clear or rotate CSRF state, so the SPA must reacquire a token before the next unsafe cookie-backed call. Refresh token material stays in an HttpOnly cookie and never appears in JSON, schema, log, audit payload, URL, or example. The access JWT is returned in the login/refresh/change-password body with `Cache-Control: no-store` because the SPA holds it only in memory.

Access JWTs use RS256/RSA-3072 with a `kid`, expire after 15 minutes, and are verified with active plus retained previous public keys. Refresh sessions allow multiple independently revocable devices, expire absolutely after 8 hours, and have a 60-minute idle window advanced only by successful refresh. Rotation/replay fails closed: a reused old token revokes its family and requires interactive login; there is no grace window.

A forced password change authenticates to a short-lived, one-time `purpose=password_change` challenge with an explicit expiry that can call only change-password/logout; no refresh cookie is created. Login therefore returns either the normal authenticated response or that restricted challenge, and the two are distinguishable by their `purpose` discriminator. Successful change invalidates that challenge, advances credential generation, revokes old sessions, and issues a normal token pair. Admin reset accepts a temporary password only over TLS and never echoes it. Production bootstrap reads a mounted secret file/secret-manager value, forces a change, and disables itself; it accepts no CLI, stdin, URL, response, log, or precomputed-hash path.

There is no account lockout. A single-instance in-memory limiter keys on trusted client IP: login is 10/minute and refresh is 120/minute, both returning generic `429` without account/token disclosure. Multi-instance deployment is blocked until this limiter is shared or moved to a gateway. Before go-live and after destructive administration, two independent ACTIVE users with usable credentials must each effectively hold `ADMIN:MANAGE_USERS` and `ADMIN:MANAGE_ROLES`.

## Frontend traceability

| Old frontend evidence | Production v1 replacement | Owner / Phase 1 status |
|---|---|---|
| `authApi.login({ username, password, role })` | `POST /auth/login`, then `GET /auth/csrf` after CSRF reset | Identity; role selector removed |
| `UserMaster`, `userMasterApi` | `/admin/users` plus `/status`, `/roles`, `/permission-overrides`, `/reset-password` | Admin; password/media/mock matrix superseded |
| `whiteListApi.load/setEnabled` | `/admin/ip-allowlist`; response `enforced=false` | Admin; toggle removed |
| `loginHistoryApi.list` (time, IP, IP name, user name, user ID) | `GET /admin/login-events` returning the same redacted columns | Admin |
| `UnitOfMeasure`, `unitApi` | `/master-data/uoms` | Reference master |
| `ExchangeRate` (`vndUsdRate` + `wonUsdRate` per month), `exchangeRateApi` | `/master-data/exchange-rates` monthly record with both rates | Reference master |
| `CustomerMaster.staff` (`division`, `isDefault`), `customerMasterApi` | `/master-data/customers/{id}/contacts` sub-resource | Party master |
| `SupplierMaster.staff` (`division`, `isDefault`), `supplierMasterApi` | `/master-data/suppliers/{id}/contacts` sub-resource | Party master |
| `ProcessMaster`, `processMasterApi` | `/master-data/processes` | Process master |
| `RawMaterial`, `rawMaterialApi` | `/master-data/raw-materials` | Product master |
| `FinishedGood`, `finishedGoodApi` | `/master-data/finished-goods` | Product master; images removed |
| Buyer Order / Production / Delivery / Debit / Stock mock APIs | no production endpoint | Deferred, demo-only and isolated |

The old transaction consumers of `UnitOfMeasure` and `ExchangeRate`—Buyer Order items, Production configuration, Delivery Note/items/rates, Debit Note, and Stock—are explicitly deferred demo consumers. They must use demo adapters, not the Phase 1 production Unit/Rate APIs.

## Historical preservation

Historical ERP documents are not edited or deleted. They remain read-only records of prior mock behavior and are marked superseded by the source-priority statement above. Future transaction work must publish a new approved contract rather than infer production behavior from these historical artifacts.
