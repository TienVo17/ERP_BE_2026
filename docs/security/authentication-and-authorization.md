# Authentication and authorization

## Auth endpoint matrix

| Endpoint | Authentication and CSRF | Result |
|---|---|---|
| `GET /api/v1/auth/csrf` | same-site anonymous/session request | issues or reacquires the SPA CSRF token/cookie |
| `POST /auth/login` | CSRF; login ID/password | normal login returns an access JWT plus the refresh cookie; a pending forced change returns only the restricted challenge |
| `POST /auth/refresh` | refresh cookie **and** CSRF | access JWT plus session view; rotated cookie only |
| `POST /auth/logout` | refresh cookie + CSRF, or restricted challenge bearer | revokes the family and clears the cookie |
| `GET /auth/me`, `GET /auth/sessions`, `POST /auth/sessions/{id}/revoke` | bearer JWT | current identity/session management; no cookie authentication |
| `POST /auth/change-password` | bearer normal JWT or restricted challenge | one-time change, then a normal token pair |

CSRF state is cleared or rotated at login and logout, so the SPA calls `GET /auth/csrf` before its next unsafe cookie-backed request; a stale or missing token returns `CSRF_INVALID`. The reverse proxy is same-site at `/api`. The production refresh cookie is `Secure`, `HttpOnly`, `SameSite=Strict`, and path-limited to `/api/v1/auth`. Cookie material is never readable by JavaScript and never appears in a response body or schema.

## Token and session controls

- Access JWT: RS256 signed by an RSA-3072 key, carries `kid`, `sub`, `sid`, `jti`, issuer/audience/time claims, and expires in 15 minutes. It is returned in the login/refresh/change-password JSON body, held only in browser memory, and served with `Cache-Control: no-store`.
- Key ring: exactly one active private signer plus current and previous public verification keys. Retain the previous public key at least the access TTL plus accepted clock skew.
- Refresh: opaque rotating token carried only in the `ERP_REFRESH` cookie. PostgreSQL stores its hash, family/session state, rotation/reuse/revocation metadata, absolute expiry (8 hours), and idle expiry (60 minutes).
- Idle expiry advances only after a successful refresh; ordinary API activity and browser heartbeats do not write session state.
- Multiple simultaneous browser/device sessions are allowed, listable with redacted client IP and user agent, and individually revocable. Disable and reset revoke every session.
- If rotation commits and its response is lost, reuse of the old token revokes the family and returns `REFRESH_REAUTH_REQUIRED`; no grace window or recovery secret exists.

A restricted challenge is authenticated but not authorized for business APIs, so calling anything other than change-password or logout returns `403 PASSWORD_CHANGE_REQUIRED`, never `401`. The SPA must not treat that code as a session expiry and must not attempt a refresh, because a challenge has no refresh cookie and a terminal logout would destroy the one-time challenge.

A forced credential change verifies credentials but returns only a short-lived signed `purpose=password_change` challenge with an explicit `expiresAt`, the minimum identity needed to render the change screen, and no refresh cookie. The challenge is bound to the persisted reset/password generation, can invoke only change-password and logout, and becomes invalid after one successful change. A successful change writes the new hash, advances the generation, revokes old sessions, and creates a normal token pair.

## Credential and recovery controls

Passwords use Argon2id where runtime compatible through a delegating password format. Passwords, hashes, raw refresh tokens, temporary passwords, and secret values are prohibited from logs, audit JSON, URLs, response bodies, examples, and CLI arguments. User creation and admin reset accept a temporary password over TLS only, never echo it, and set `mustChangePassword`.

Production bootstrap reads a mounted secret file or secret-manager value, forces a change, and permanently disables bootstrap mode after use. It never reads stdin, command-line arguments, URLs, or a precomputed hash. Before go-live and after every destructive admin operation, at least two independent ACTIVE accounts must each hold effective `ADMIN:MANAGE_USERS` and `ADMIN:MANAGE_ROLES` with a verified usable credential path; a command whose post-state breaks this returns `RECOVERY_ADMIN_REQUIRED`.

No account lockout is permitted. A single backend instance applies trusted-client-IP in-memory limits of 10 login attempts/minute and 120 refresh attempts/minute. Responses are generic `429` and reveal neither account nor token existence. Horizontal deployment is prohibited until the limiter state is shared (for example, gateway or Redis) and the trusted-proxy boundary is validated.

## Authorization

Each bearer request validates issuer, audience, signature, `kid`, expiry, and subject/session identifiers. The server then loads the active user, the active auth session, and current effective permissions from PostgreSQL; long-lived JWT permission claims are not authoritative. This makes disable, logout, reset, role edits, and overrides effective at the next API boundary.

Permission resolution is deterministic:

```text
explicit user DENY > explicit user ALLOW > role permission > default DENY
```

The V009 catalog defines master `VIEW`, `CREATE`, `UPDATE`, `ARCHIVE`; transaction permissions; and the four ADMIN permissions. `ADMIN` holds every seeded permission. SALE receives only `VIEW`, `CREATE`, and `UPDATE` for `RAW_MATERIAL`, `FINISHED_GOODS`, `CUSTOMER`, `SUPPLIER`, `PROCESS`, and `ETC`; it has neither `ARCHIVE` nor any ADMIN permission. Direct API calls are enforced identically to UI visibility.

Administrative authority is split so that user management cannot silently grant authority:

| Command | Required permission |
|---|---|
| Create user, update profile, change status, reset password | `ADMIN:MANAGE_USERS` |
| Replace user roles, replace user permission overrides, manage roles/grants | `ADMIN:MANAGE_ROLES` |
| Allowlist entry CRUD | `ADMIN:MANAGE_ALLOWLIST` |
| Login event query | `ADMIN:VIEW_AUDIT` |

Each operation declares its requirement as `x-required-permission` in the OpenAPI contract, and every declared value must exist in the seeded catalog. Permission overrides reference seeded permission IDs, so a client cannot invent a module or action such as a fabricated `ADMIN:SUPERUSER`.

The migration-seeded `SYSTEM` principal is not an administrable account. It owns reference rows through audit foreign keys, cannot authenticate, and is excluded from user listing, profile and status commands, role and override assignment, password reset, and the recovery-admin count. Password reset is treated as destructive because it removes the target from the recovery-capable set until the forced change completes; it therefore requires `If-Match` and returns `RECOVERY_ADMIN_REQUIRED` when its post-state would leave fewer than two recovery-capable admins.

IP allowlist management requires `ADMIN:MANAGE_ALLOWLIST`, but Phase 1 exposes configuration only: the list response always reports `enforced=false`, there is no global toggle, and no request is blocked. An entry's `active` flag records intent, not enforcement. Enforcement awaits a trusted-proxy design.

## Threat boundaries

- Bearer business endpoints reject cookie-based authentication and require authorization at the API, not merely hidden UI controls.
- Requests reject unknown or overposted properties; server-owned and security-sensitive data cannot be silently ignored.
- Media, avatars, Finished Good images, and Data URLs are excluded from Phase 1.
- Refresh replay, disabled users, revoked sessions, stale CSRF, stale versions, recovery-admin violations, and explicit DENY fail with stable Problem Detail codes.
- Login events and session views are redacted: they carry client IP, IP name snapshot, and user agent for administration, but never credentials, tokens, or SQL detail.
- TLS, least-privilege database roles, encrypted backups, and secret-manager/mounted-secret handling are production prerequisites.
