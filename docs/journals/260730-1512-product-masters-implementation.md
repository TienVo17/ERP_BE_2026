---
title: "Phase 6 Product Masters Completed"
date: 2026-07-30T15:12:00+07:00
status: resolved
component: master-data-api-and-v013
---

# Phase 6 Product Masters Completed

## What Happened

Phase 6 delivered the frozen-contract Raw Material and Finished Good APIs: list, detail, create, update and archive, with SALE VIEW/CREATE/UPDATE, ADMIN archive, optimistic versions, bounded allowlisted queries, strict unknown-field rejection and same-transaction audit. V013 extends the V012 coordination model to Finished Good and Buyer Order Item.

## The Brutal Truth

The two masters are not symmetric and pretending otherwise would have been a lie in code. Finished Good has a real usage source — `sales.buyer_order_item.finished_good_id` — so it gets a derived guard. Raw Material has none: no table in the current schema references it, because Procurement and RM inventory are out of scope. No fake guard was invented for it, and a test now asserts that an unused Raw Material archives cleanly rather than pretending a guard exists.

## Technical Details

V013 reuses `master_data.coordinate_guarded_master_usage()` instead of adding a second advisory key, so Finished Good lifecycle updates and Buyer Order Item writes serialize on the same global transaction lock as Customer, Process and Exchange Rate. It adds an `AFTER STATEMENT` check requiring an ACTIVE Finished Good for new references, and a `BEFORE UPDATE` row check freezing product kind, style, name, size, color, UOM, price, currency and status while a Buyer Order Item references the record.

The upgrade takes a `SHARE ROW EXCLUSIVE NOWAIT` fence on `sales.buyer_order_item` and `master_data.finished_good` before validating, and refuses inconsistent data with `MASTER_GUARD_MIGRATION_INVALID`. Because Flyway commits each migration separately, a failed V013 leaves the schema at `012`; the operator quiesces writers and reruns. No new index was needed — `ix_buyer_order_item_finished_good` from V004 already serves the usage query.

## What We Tried

The first draft froze `image_asset_id` along with every other business column. That was removed on review: Buyer Order Item snapshots carry kind, style, name, UOM and price, so an image can never invalidate a referencing document, and blocking it would have made a future media phase fight a guard for no integrity reason.

Raw Material canonicalizes `code` to uppercase like UOM, but Finished Good keeps `styleNo` as typed and enforces uniqueness canonically, because that key is composite with free-text name, size and color — uppercasing a product name in storage would be wrong.

## Root Cause Analysis

The one defect worth naming was latent, not introduced: the service reads usage before it holds the coordination lock, so an uncommitted Buyer Order Item writer is invisible to that read and can commit in between. A test now drives exactly that interleaving and asserts the archive returns `MASTER_IN_USE` rather than an unmapped database error. Without V013 the same interleaving would have silently archived a referenced Finished Good.

## Lessons Learned

When a second master needs the same guard, extend the existing coordination point rather than adding a parallel one; a second advisory key would have reintroduced the multi-key cycle that Phase 5 spent three designs eliminating. Guard only the columns that can invalidate a referencing document — a guard that is broader than the invariant is a future obstacle, not extra safety. And when a master genuinely has no usage source, say so in a test and in the data dictionary instead of leaving a reader to assume the guard was forgotten.

## Next Steps

Phase 7 cuts the frontend over to these endpoints and must keep image and inventory controls absent; `image_asset_id` stays null and no Data URL is accepted. Operations must quiesce guarded writes before the V013 upgrade and retry on `55P03`. Verification closed cleanly: focused Phase 6 tests 16/16, `./mvnw.cmd clean package` 142/142 with zero failures, errors or skips, clean V001→V013 and V011→V013 upgrades passed. `plans/` remains ignored local state and is not part of commits.
