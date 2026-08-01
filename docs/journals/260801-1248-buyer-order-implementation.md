---
title: "Buyer Order implementation"
date: 2026-08-01
tags: [buyer-order, transactions, tdd, postgresql]
---

# Buyer Order implementation

## Context

Phase 3 implemented the first production F/G aggregate on the V015 command foundation. The frozen API required draft CRUD, copy, confirm, history-preserving reopen, durable idempotency, version checks, permissions, and PostgreSQL concurrency behavior.

## What happened

- RED began with controller, service, and concurrency tests that could not compile because the Buyer Order API/classes did not exist.
- GREEN added paged list/detail and create/update/copy/confirm/reopen over PostgreSQL.
- Standard and custom items now persist canonical Customer, contact, Finished Good, UOM, currency, and product snapshots. Quantity/price remain JSON strings; amounts round half-up and reject values outside `numeric(18,2)`.
- Confirm allocates Bangkok-year PR numbers, creates exactly one OPEN Production Order plus CREATED event per active item, and creates no Stock.
- Reopen retains prior item revisions and Production rows, marks them CANCELLED, appends history events, and creates a new editable revision only when no downstream activity exists.

## Reflection

Review exposed failures that happy-path tests missed: missing `no-store` on protected GETs, expected 4xx marking the idempotency transaction rollback-only, deferred guards running after replay completion, master lifecycle races, duplicate CREATE/COPY audit records, nullable Production configuration, amount overflow, and responses too large for the 256 KiB replay boundary. Fixes were added at the owning boundaries rather than hidden in generic exception handling.

## Decisions

- Expected domain errors use `noRollbackFor` while the idempotency savepoint removes partial effects before terminal Problem Details are stored.
- Commands force deferred constraints before completion and retain SQL/unexpected failures as non-replayable rollbacks.
- Confirm acquires the existing global advisory lock and row-locks active master references against concurrent archive.
- Buyer Order free text and persisted snapshot lengths are bounded in DTO and OpenAPI so every accepted 100-line command remains replayable.
- Copy writes one COPY audit event; it does not manufacture a second CREATE event.
- V001–V015 remain unchanged. `plans/` is ignored, local-only, and must never be staged or pushed.

## Next

Phase 4 can build Production grouping/configuration/finish and Stock on the confirmed Buyer Order/Production seams. Final evidence for this phase: deterministic race coverage, full backend suite 203 tests with zero failures/errors/skips, and successful executable package generation.
