---
title: "Phase 5 Reference and Party Masters Completed"
date: 2026-07-30T14:27:00+07:00
status: resolved
component: master-data-api-and-v012
---

# Phase 5 Reference and Party Masters Completed

## What Happened

Phase 5 delivered the frozen-contract APIs for read-only Currency; UOM; monthly Exchange Rate; Customer/PIC; Supplier/PIC; and Process. Mutable resources use explicit archive commands, optimistic versions, bounded allowlisted list queries, SALE VIEW/CREATE/UPDATE permissions, ADMIN archive permission, and same-transaction audit snapshots. Contacts retain owner-scoped IDs and support transactional default promotion.

## The Brutal Truth

The initial RED run had 12 `404` failures because none of these API routes existed. That was expected TDD evidence, not a production incident, and no tests were weakened to hide it. The meaningful defect was review-discovered: contact audit snapshots could represent `false` as absent and did not reliably record both sides of default promotion. That would have made an audit trail misleading exactly when an operator changed the default PIC.

## Technical Details

V012 creates `ix_delivery_note_used_exchange_rate` as a partial index on `delivery.delivery_note(exchange_rate_id)` for `status IN ('POSTED', 'REVERSED')`. It starts with a seven-table `SHARE ROW EXCLUSIVE MODE NOWAIT` migration fence over Customer, Process, Exchange Rate, and their guarded relationship tables.

The migration refuses inconsistent V011 data with `MASTER_GUARD_MIGRATION_INVALID` and SQLSTATE `23514`; it does not silently archive or rewrite business rows. An active V011 writer makes the fence fail with SQLSTATE `55P03`; the correct operational response is to quiesce writers and retry the rolled-back migration.

## What We Tried

We considered row locks, relation-level `SHARE` locks, and advisory-lock protocols keyed by multiple masters. They were rejected after proving deadlocks from conflicting lock order and multi-statement paths. Review also added/fixed tests for contact default/version concurrency and the endpoint permission matrix, then corrected the audit snapshot handling of boolean `isDefault` and prior/default contact state.

## Root Cause Analysis

A per-row or per-key locking protocol assumed all writers would acquire the same resources in the same order. That assumption was false for lifecycle changes and relationship writes spanning different master tables. Separately, treating an optional audit value as a truthy value conflated `false` with missing data. Both were design mistakes, not database flakiness.

## Lessons Learned

For guarded relationships, prove a global lock order before using fine-grained locks. If there is no durable order, use one transaction-scoped coordination point and accept its throughput cost. Audit snapshots need explicit null/boolean semantics; test `false`, null, previous default, and selected default independently. Deployment validation must fail on incompatible old data rather than manufacture a clean-looking but corrupted state.

## Next Steps

Phase 6 owner must implement Raw Material and Finished Good without bypassing the V012 coordination/fence model when adding new usage guards. Operations must quiesce guarded writes before any V012 upgrade and retry on `55P03`. Verification closed cleanly: focused tests 60/60, `./mvnw.cmd clean package` 124/124 with zero failures/errors/skips, clean and upgrade migrations passed, and final review had 0 findings. `plans/` remains ignored local state and is not part of commits.
