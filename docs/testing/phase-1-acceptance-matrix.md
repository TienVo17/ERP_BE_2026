# Phase 1 Acceptance Matrix

Every success criterion in the Phase 0–1 plan, traced to the thing that proves it. A criterion with
no evidence column is not done, whatever the plan checkbox says.

Evidence kinds:

- **Test** — an automated check that fails the build. Named by class, and by method where the class
  covers several criteria.
- **Operator** — a human check against a running system. Belongs in the deployment guide, not in CI.
- **Deployment** — a property of the deployed configuration, verified once per environment.

Repositories: `BE-ERP` (backend, this repository) and `ERP` (frontend).

## Contract and schema

| # | Criterion | Kind | Evidence |
|---|---|---|---|
| 1.1 | OpenAPI validates and covers every scoped operation | Test | `PhaseOneOpenApiContractTest` |
| 1.2 | No client-selected role, plaintext, hash, token or Data URL in responses | Test | `AuthControllerIT`, `AdminUserControllerIT.createsUserWithCanonicalLoginAndHashedTemporaryPasswordWithoutCredentialLeak` |
| 1.3 | JWT claims, refresh cookie, CSRF, reset challenge and revocation semantics unambiguous | Test | `JwtValidationIT`, `AuthControllerIT` |
| 1.4 | Permission and archive matrix complete for ADMIN and SALE | Test | `AuthorizationMatrixIT`, `MasterDataPermissionMappingTest`, `PhaseOneMasterJourneyIT` |
| 1.5 | Production-vs-demo frontend boundary explicit | Test | ERP `src/app/production-boundary.test.ts` |
| 1.6 | Old BRD/mock conflicts marked superseded, history kept | Operator | `docs/operational-core-v1-spec.md` supersession section |
| 1.7 | No Buyer Order/Production/Delivery implementation added | Test | ERP `production-boundary.test.ts`; backend has no such controller |

## Database and privilege

| # | Criterion | Kind | Evidence |
|---|---|---|---|
| 2.1 | `pom.xml` carries no JPA, H2 or Lombok | Test | `pom.xml` review; `PostgresTestConfiguration` starts real PostgreSQL |
| 2.2 | V001–V012 byte-for-byte unchanged | Test | `MigrationImmutabilityTest.preservesV001ThroughV012ByteForByte` |
| 2.3 | Migrations apply cleanly from empty PostgreSQL 16 | Test | `DatabaseMigrationIT` |
| 2.4 | V011→V013 upgrade rehearsal on populated data | Test | `DatabaseUpgradeIT` |
| 2.5 | Runtime role can run auth and trigger paths but cannot mutate append-only records | Test | `RuntimePrivilegeIT`, `RuntimeCredentialIT` |
| 2.6 | No production secret, key or default password in the repository | Test | `application.yml` is entirely env-driven; secret scan in the release gate |
| 2.7 | Problem Detail contract | Test | `ApiExceptionHandlerTest`, `SecurityProblemDetailsIT` |
| 2.8 | `mvnw test` and `mvnw package` pass | Test | Release gate, recorded in the deployment guide |

## Identity

| # | Criterion | Kind | Evidence |
|---|---|---|---|
| 3.1 | Login derives identity and roles server-side | Test | `AuthControllerIT.loginDerivesIdentityServerSideSetsProtectedCookieAndSupportsBearerMe` |
| 3.2 | Access token 15 minutes, strict RS256 `kid`, previous key overlap | Test | `JwtValidationIT` |
| 3.3 | Retained public-key *duration* honoured by the secret store | Deployment | Key rotation runbook in the deployment guide |
| 3.4 | Multi-device sessions, 8h absolute and 60m idle expiry, individual revocation | Test | `AuthControllerIT.sessionListShowsOnlyLiveSessionsAndDeadSessionsCannotBeRevoked`, `RefreshTokenServiceTest` |
| 3.5 | Rotation and replay-family revocation are atomic | Test | `RefreshTokenServiceTest.concurrentRefreshAllowsOneRotationThenRevokesTheFamily`, `PhaseOneSecurityRegressionIT.concurrentRefreshRotatesOnceAndThenForcesReauthentication` |
| 3.6 | Logout invalidates API use immediately | Test | `AuthControllerIT.logoutRevokesFamilyClearsCookieAndRejectsExistingAccessToken` |
| 3.7 | Forced password change cannot reach normal APIs; challenge is one-time | Test | `AuthControllerIT.forcedPasswordChallengeCannotUseBusinessApisAndIsOneTime` |
| 3.8 | Login 10/min and refresh 120/min per trusted IP, no lockout, no enumeration | Test | `AuthRateLimitIT`, `TrustedIpRateLimiterTest`, `AuthControllerIT.invalidAndUnknownCredentialsHaveSameGenericResponse` |
| 3.9 | Single backend instance until the limiter is externalised | Deployment | `erp.security.deployment-instances`; `ErpPropertiesTest` |
| 3.10 | Lost refresh response fails closed with no retry loop | Test | `AuthControllerIT.refreshRotatesCookieAndReplayingOldCookieRevokesTheFamily` |
| 3.11 | No plaintext password, refresh token or private key persisted, logged or returned | Test | `AuthControllerIT`, `AdminUserControllerIT`; secret scan |
| 3.12 | CSRF and cookie behaviour under same-site topology | Test | `AuthControllerIT.missingOrInvalidCsrfRejectsLoginRefreshAndCookieLogout` |
| 3.13 | Bootstrap admin is explicit, one-shot and file-sourced | Test | `BootstrapAdminIT` |

## Authorization and administration

| # | Criterion | Kind | Evidence |
|---|---|---|---|
| 4.1 | Direct API authorization matches the matrix, DENY wins | Test | `AuthorizationMatrixIT` |
| 4.2 | Forged client claims grant nothing | Test | `JwtValidationIT` |
| 4.3 | Admin changes are versioned, audited and session-safe | Test | `AdminUserControllerIT`, `PhaseOneSecurityRegressionIT.replacingRolesImmediatelyStopsTheOldAccessToken` |
| 4.4 | No endpoint exposes password, hash or token | Test | `AdminUserControllerIT`, `AdminRoleControllerIT` |
| 4.5 | Two recovery-capable admins survive every destructive command | Test | `AdminUserControllerIT.destructiveCommandCannotReduceRecoveryAdminsBelowTwo`, `PhaseOneSecurityRegressionIT.concurrentStatusChangesCannotDropRecoveryAdminsBelowTwo` |
| 4.6 | Login events append-only and paginated | Test | `AdminMonitoringControllerIT` |
| 4.7 | Allowlist backend-backed, visibly not enforced, no global toggle | Test | `AdminMonitoringControllerIT`; ERP `white-list-page.test.tsx`; `production-boundary.test.ts` forbids `whiteListEnabled` |
| 4.8 | SALE has no admin access | Test | `PhaseOneMasterJourneyIT.saleBaselineCannotReachAnyAdministrationEndpoint` |

## Master data

| # | Criterion | Kind | Evidence |
|---|---|---|---|
| 5.1 | Reference, party and process operations match V003 invariants | Test | `ReferenceMasterControllerIT`, `PartyMasterControllerIT`, `ProcessMasterControllerIT` |
| 5.2 | SALE and ADMIN permissions enforced directly | Test | `PhaseOneMasterJourneyIT.saleBaselineCreatesAndUpdatesEveryScopedMasterAndIsRefusedEveryArchive` |
| 5.3 | Archive replaces delete | Test | Same as 5.2; no delete mapping exists on any master controller |
| 5.4 | Contact ownership and default rules hold under concurrency | Test | `PartyMasterServiceTest` |
| 5.5 | Client usage flags and audit fields rejected | Test | `RawMaterialControllerIT` overposting cases |
| 5.6 | Lists deterministic and bounded | Test | `PhaseOneMasterJourneyIT.everyScopedListPagesAtTwentyFiveAndRefusesAnUnknownSortField` |
| 5.7 | Writes and audit commit or roll back together | Test | `AuditEventWriterTest`, master controller ITs |
| 5.8 | R/M is master-only with no inventory source | Test | `RawMaterialControllerIT` |
| 5.9 | F/G composite uniqueness matches V003 | Test | `FinishedGoodControllerIT` |
| 5.10 | Media deferred with no Data URL fallback | Test | ERP `fg-master-page.test.tsx`, `user-master-page.test.tsx`; no media column is written |
| 5.11 | Reference IDs and statuses validated server-side | Test | `ProductMasterServiceTest`, `PartyMasterServiceTest` |
| 5.12 | Usage and archive decisions derive from relationships | Test | `PhaseOneMasterJourneyIT.administratorArchiveIsStillRefusedWhenBusinessDataReferencesTheRecord`, `ProductMasterServiceTest` |

## Frontend cutover

| # | Criterion | Kind | Evidence |
|---|---|---|---|
| 7.1 | No access token or session in browser persistent storage | Test | ERP `production-boundary.test.ts` (source), plus operator check of storage at runtime |
| 7.2 | Login has no role selector or demo credentials | Test | ERP `login-page.test.tsx` |
| 7.3 | Refresh and CSRF single-flight, one retry, loop-safe | Test | ERP `api-client.test.ts`, `auth-session.test.ts` |
| 7.4 | Identity, Admin and Master pages use only `/api/v1` | Test | ERP `production-boundary.test.ts`; per-feature adapter tests |
| 7.5 | SALE and ADMIN route and action visibility follow effective permissions | Test | ERP `routes.test.ts`, `erp-layout.test.tsx`, per-page permission tests |
| 7.6 | Mock Identity/Master writers unreachable in production | Test | ERP `production-boundary.test.ts`; the modules no longer exist |
| 7.7 | Transaction modules absent from the production boundary; demo Unit/Rate adapters isolated | Test | ERP `production-boundary.test.ts`, `demo-api.test.ts`; bundle audit |
| 7.8 | Media controls hidden and no Data URL sent | Test | ERP `fg-master-page.test.tsx`, `user-master-page.test.tsx` |
| 7.9 | Route registry has no duplicates or missing definitions | Test | ERP `routes.test.ts` |
| 7.10 | Every scoped list has server paging, default 25, max 100, server filter and sort | Test | `PhaseOneMasterJourneyIT.everyScopedListPagesAtTwentyFiveAndRefusesAnUnknownSortField`; ERP `page-controls.test.tsx`, `list-state.test.ts` |
| 7.11 | `npm ci`, test and build pass with pinned dependencies | Test | Release gate |

## Release

| # | Criterion | Kind | Evidence |
|---|---|---|---|
| 8.1 | Live same-site browser journeys for ADMIN and SALE | Operator | Deployment guide browser journey section |
| 8.2 | Production cookie flags observed in a browser over TLS | Operator | Same |
| 8.3 | Backup, restore and forward-fix rehearsed | Operator | Rollback guide |
| 8.4 | Deployed artifact pair recorded together | Deployment | Deployment guide release record |
| 8.5 | No unresolved blocking review finding | Operator | Review record |

## Criteria with no automated owner

These are real obligations that no test can discharge. They are listed so they are not mistaken for
covered ground.

| Criterion | Why a test cannot own it |
|---|---|
| 3.3 public-key retention duration | The application cannot stop a secret store from deleting a key file early. |
| 3.9 single-instance deployment | The process can refuse to start with a bad value, but only the deployment decides how many run. |
| 8.1–8.3 live journeys and restore drills | They need a running database, a TLS origin and a real browser. |

## Open items

**Two administrators cannot exist until the second one changes its password.** The recovery quorum
counts only accounts that owe no password change, and it is evaluated after every role, status,
override and reset command. Immediately after bootstrap exactly one account qualifies, so *every*
role command is refused with `RECOVERY_ADMIN_REQUIRED` until a second account becomes
credential-ready. The working order is: create the second administrator, have it sign in and
complete its own forced change, and only then assign it the ADMIN role. Assigning the role first
fails, and the failure is correct. The deployment guide states this order; the live journey asserts
both halves.

**An out-of-range page size answered 500 until this phase.** Controllers carry `@Validated`, so
parameter constraints surfaced as `ConstraintViolationException`, which no handler mapped; the
generic handler turned every `size=101` into an unexplained server error on every list endpoint.
`ApiExceptionHandler.handleParameterConstraints` now answers 400 and names the parameter. The
deeper cleanup — dropping `@Validated` so Spring's own `HandlerMethodValidationException` path
applies, which the handler above it already anticipates — is deliberately not done here, because it
changes how validation is dispatched across every controller and belongs in its own change.

**A UOM in use stays archivable.** Archiving a UOM referenced by an existing raw material succeeds
by design: the guard prevents selecting an archived reference, it does not freeze rows that already
carry one. Customer, Supplier, Process, Exchange Rate and Finished Good do refuse. The asymmetry is
intentional and asserted in both directions.
