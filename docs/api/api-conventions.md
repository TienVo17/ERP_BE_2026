# API v1 conventions

The authoritative HTTP surface is [OpenAPI v1](erp-v1-openapi.yaml). This document supplies shared rules without repeating resource schemas.

## Transport and versioning

All production endpoints are HTTPS `/api/v1`. JSON uses `application/json; charset=utf-8`; UTF-8 request/response bodies are required. UUIDs are canonical strings. Timestamps are UTC RFC 3339 instants; business dates are `YYYY-MM-DD`; exchange-rate months are `YYYY-MM` and normalize to the first calendar day in persistence.

Business APIs accept `Authorization: Bearer <access JWT>` only. They do not authenticate from cookies. Cookie-backed Auth operations declare both the `ERP_REFRESH` cookie and the CSRF header in the same security requirement; see [authentication and authorization](../security/authentication-and-authorization.md).

## Requests, versions, and decimals

Every create/update/command schema is closed with `additionalProperties: false`. Unknown properties fail with `400`/`INVALID_REQUEST`; server-owned IDs, version, status, timestamps, audit fields, password hashes, tokens, Data URLs, media fields, and derived usage flags are rejected rather than ignored.

Use `If-Match: "<version>"` for updates, archive transitions, and destructive admin commands, including password reset. A stale version returns `409 VERSION_CONFLICT`.

PUT is **full replacement**, never a partial merge. An omitted optional member clears that value, so a client must send the complete intended state. The one exception is a flag whose invariant spans siblings: an omitted contact `isDefault` leaves the existing default untouched, because clearing it silently would leave an owner with no default and no constraint violation. Clearing a default is done by promoting another contact.

Decimal values are JSON **strings**, never IEEE-754 numbers:

| Schema | Scale | Sign |
|---|---|---|
| `DecimalQuantity` | 4 | non-negative |
| `DecimalPrice` | 6 | non-negative |
| `DecimalRate` | 6 | strictly positive |

Reference prices use the six-decimal price scale because persistence stores `numeric(18,6)`. Monetary document totals are out of Phase 1 scope.

## Lists, filters, and sorting

Every scoped list — including sessions, users, roles, permissions, login events, allowlist entries, currencies, contacts, and all masters — accepts `page` (zero-based; default `0`) and `size` (default `25`, maximum `100`). Invalid page/size, non-allowlisted filter keys, and non-allowlisted sort fields return `400`. Responses use:

```json
{
  "items": [],
  "page": { "number": 0, "size": 25, "totalElements": 0, "totalPages": 0 },
  "filters": { "status": "ACTIVE" },
  "sort": ["code,asc"]
}
```

`filters` echoes accepted filters only; `sort` echoes normalized, allowlisted sort terms and is repeatable. Every allowlisted filter is a declared OpenAPI query parameter, and each list operation documents its permitted sort fields. Lists stay stable through an allowlisted secondary `id` sort.

## Errors and traceability

All non-success responses use RFC 9457 Problem Details (`application/problem+json`):

```json
{
  "type": "https://erp.example.invalid/problems/validation",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "instance": "/api/v1/master-data/uoms",
  "code": "VALIDATION_FAILED",
  "traceId": "trace-placeholder",
  "fieldErrors": [{ "field": "code", "code": "REQUIRED", "message": "must not be blank" }]
}
```

`code` and `traceId` are always present; `fieldErrors` appears only for field validation. Values above are placeholders, not credentials or production identifiers.

Stable error-code registry:

| Code | Typical status | Meaning |
|---|---|---|
| `INVALID_REQUEST` | 400 | Malformed or unknown request member |
| `VALIDATION_FAILED` | 400 | Field-level validation failure |
| `UNAUTHENTICATED` | 401 | Missing/invalid credential |
| `CSRF_INVALID` | 401 | Missing or stale CSRF token |
| `REFRESH_REAUTH_REQUIRED` | 401 | Refresh replay/expiry; family revoked |
| `PASSWORD_CHANGE_REQUIRED` | 403 | Authenticated restricted challenge may not call this operation |
| `FORBIDDEN` | 403 | Effective permissions deny the operation |
| `NOT_FOUND` | 404 | Resource or ownership path not found |
| `VERSION_CONFLICT` | 409 | Optimistic version mismatch |
| `DUPLICATE_BUSINESS_KEY` | 409 | Canonical unique key violation |
| `MASTER_IN_USE` | 409 | Business relationships prevent the transition |
| `RECOVERY_ADMIN_REQUIRED` | 409 | Post-state would drop below two recovery-capable admins |
| `EXCHANGE_RATE_MISSING` | 409 | Required monthly rate is absent |
| `RATE_LIMITED` | 429 | Trusted-IP throttle without account disclosure |
| `INTERNAL_ERROR` | 500 | Unexpected server error; message never echoed to client |

Every protected operation documents at least `401` and `403`, plus `400`, `404`, `409`, and `429` where applicable. Constraint violations map to these codes without exposing SQL detail.

## Status and archive

Mutable master responses include `id`, integer `version`, `status` (`ACTIVE` or `ARCHIVED`), and `createdAt`/`updatedAt`. Archive is an explicit POST transition with `If-Match`; it is not a delete. It preserves historical references and returns `MASTER_IN_USE` when business rules prevent archive. Currency is read-only. IP allowlist entries carry no history obligation, so they use real deletion guarded by `If-Match` plus an `active` flag that never enables enforcement.

Immutable business keys are absent from their update schemas: customer `shortName`, process `qrValue`, user `loginId`, and role `code`. Create and update therefore use separate schemas wherever persistence differs.

IP allowlist `network` values are parsed and returned in canonical PostgreSQL `inet` form and are unique. Allowlist list responses always set `enforced` to `false`; there is no global enforcement toggle and entries do not block requests in Phase 1. `active` records configuration intent only.

## Security-sensitive data

Responses and examples never contain a password, password hash, temporary password, raw refresh token, cookie contents, avatar/picture/image field, Data URL, or image name.

The access JWT is different: login, refresh, and change-password return it in the JSON body because the SPA holds it in memory. Those responses are sent with `Cache-Control: no-store`, and the token is never written to `localStorage`, `sessionStorage`, `IndexedDB`, or a cookie. Refresh material exists only in the `Secure`, `HttpOnly` cookie, and only its hash is persisted server-side. A temporary password is accepted only by the TLS-protected create/reset requests and is never returned.
