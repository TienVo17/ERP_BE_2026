---
title: "Phase 8 Release Verification"
date: 2026-07-31T10:00:00+07:00
status: resolved
component: release-verification
---

# Phase 8 Release Verification

## What Happened

The release gates ran against a real deployment: PostgreSQL 16 in a container, the packaged jar
migrating as a migration owner and then serving as a separate least-privilege runtime role, and the
production frontend build served over TLS with a same-site `/api` proxy. Two new integration
classes were written first, a traceability matrix was built from every plan criterion, and the whole
release order was rehearsed including a backup and restore drill.

## The Brutal Truth

Two defects were sitting in code that a fully green 152-test suite had never touched.

The first is embarrassing in hindsight. Every list endpoint answered **500** for a page size above
the documented maximum. Controllers carry `@Validated`, so parameter constraints are enforced by the
method-validation proxy and arrive as `ConstraintViolationException` — and the handler class had a
`HandlerMethodValidationException` handler that this configuration can never reach. Somebody
anticipated exactly this problem and wrote a handler for the wrong exception type. `size=101` is the
first thing a client hits when it explores the page-size control.

The second is worse, and it would have stopped the release cold. **Administration was impossible
under the runtime role.** V010 granted `SELECT, INSERT, UPDATE` and never granted DELETE, but four
commands clear rows before writing new ones: replacing a user's roles, replacing their overrides,
editing a role's permissions, and removing an allowlist entry. Every one of them failed with
`permission denied for table user_role`. The reason no test caught it is the sharpest lesson here:
**the controller tests connect as the migration owner.** They exercise the SQL but never the
privileges the deployed system actually runs with. A test suite can be complete about behaviour and
still say nothing about the account that will execute it.

## Technical Details

`PhaseOneSecurityRegressionIT` covers what only concurrency exposes: two refreshes racing on one
cookie, two administrators disabled simultaneously against the recovery quorum, and two writers
holding the same version. It uses a barrier rather than a sleep, and it commits — a race that rolls
back proves nothing — so it neutralises its own fixtures afterwards.

`PhaseOneMasterJourneyIT` sweeps all seven masters with one SALE and one ADMIN principal. Its value
is not depth, which the per-master tests already have, but uniformity: a master added later without
its baseline grants fails here.

V014 grants DELETE on exactly the four relationship tables the application clears. Identities, roles,
sessions and every append-only history table keep their no-delete posture, and `RuntimePrivilegeIT`
now asserts both halves — what the runtime may delete, and what it still may not.

## What We Tried

Three claims I made during this phase turned out to be wrong, and the corrections matter more than
the original statements.

I recorded in the acceptance matrix that the SALE baseline was unseeded and would need a manual
deployment step. V009's comment says SALE stays default-deny, and I stopped reading there. V011
seeds the eighteen approved permissions. Had that stayed in the guide, every operator would have
performed a pointless and risky permission edit on first deployment.

I read the recovery quorum as a possible bootstrap deadlock. It is not — but the real constraint is
sharper than the plan's wording. The quorum counts only accounts that owe no password change, and it
is evaluated after *every* role command. So immediately after bootstrap, no role can be assigned to
anyone until a second administrator completes their own forced change. Both deployment guides now
state the order, and the journey asserts that the wrong order is refused and the right one succeeds.

I also measured the login rate limit twice and got two wrong answers before getting a right one:
first because earlier journey traffic had already consumed the window, then because twelve TLS
round-trips took longer than the minute they were meant to fit inside. Reusing one CSRF token
brought twelve attempts down to one second and produced the exact expected split of ten admitted and
two throttled.

## Root Cause Analysis

Both defects share a shape: a guard that exists but does not apply to the path in production. A
handler for an exception the configuration never throws. A grant policy written for the operations
the code had at the time, never re-derived when commands started clearing rows. Neither is visible
from reading the code that fails; both are visible the moment the system runs the way it will be
deployed.

## Lessons Learned

Test as the account that will run in production. The single highest-value change this phase could
make to the test suite is having at least one integration test connect as the runtime role rather
than the owner, which is what `RuntimePrivilegeIT` now does for the delete paths.

Verify a claim before writing it into a guide. The unseeded-baseline error would have been caught by
reading one more migration, and it was caught only because a live journey contradicted it.

## Next Steps

The DOM-level browser pass is owed: the Chrome extension is not connected to this session, so the
journeys ran through an HTTP client against the same TLS origin and the same cookies. That covers
every server-side property — cookie flags observed on the wire, permission enforcement, rotation and
replay, throttling — but not what a rendered page does with them: storage inspection at runtime,
the back button after logout, and menu visibility.

Not rehearsed and deliberately so: key rotation over a real retention period, and the audit and
login retention jobs. Both are secret-store and scheduler obligations rather than application
behaviour, and both are named in the acceptance matrix as criteria no test can own.
