# Kiến trúc PostgreSQL cho ERP

## Tổng quan

PostgreSQL là OLTP source of truth cho backend Spring Boot. Thiết kế dùng module ownership, master data chuẩn hóa, immutable document snapshots và append-only stock/audit records.

Quyết định nghiệp vụ đã chốt:

- Một Customer Master.
- Production `OPEN → FINISHED`, finish một lần; chỉ lúc finish mới tăng physical stock.
- FG stock theo Buyer Order Item/Production Order; chưa có warehouse/location/lot/transfer.
- Raw Material chỉ là master data; chưa có Procurement/RM inventory.
- Buyer Order Item dùng Finished Good FK khi là hàng chuẩn; custom item dùng snapshots.
- `USE STOCK QTY = 0` trong phase đầu; allocation/cross-PO stock ngoài scope.
- Posted Delivery chỉ reverse, không hard-delete; reverse bị chặn nếu Delivery đã có Return.
- Debit Note là projection từ posted Delivery; reference dùng Delivery No + line.
- Single-company.
- Role baseline + user permission override; permission theo nghiệp vụ.
- Document number theo loại/năm, server cấp, chấp nhận gap.
- Quantity `numeric(18,4)`, price/rate `numeric(18,6)`, amount `numeric(18,2)`, VAT `numeric(7,4)`; item amount round half-up rồi total cộng item.
- Login event giữ 365 ngày; business audit/document/stock history giữ tối thiểu 7 năm.
- Full WOVEN fields nằm trong schema nhưng nullable phase đầu.
- Buyer Order PIC dùng Customer Contact FK tùy chọn + immutable snapshot.

## Phạm vi module

| Module | Sở hữu | Không nằm trong scope |
|---|---|---|
| Identity | User, role, permission, login, allowlist, audit | SSO/MFA implementation chi tiết |
| Master Data | Customer, Supplier, UOM, rate, Process, RM, F/G, media metadata | Procurement/RM ledger |
| Sales | Buyer Order và items | Invoice/accounting |
| Production | Order, group, PRINT/WOVEN config, process, yarn, events | Partial receipts/rework/scrap |
| Inventory | FG stock position và movement ledger | Warehouse/location/lot/transfer |
| Delivery | Delivery document, item, post/reversal events | Payment/AR posting |
| Debit Read Model | Projection từ posted Delivery | Mutable Debit aggregate |

## Dependency direction

```text
Identity ───────────────────────────────► actor/audit reference
Master Data ─────► Sales ─────► Production ─────► Inventory
       │                                          ▲
       └──────────────────────► Delivery ──────────┘
                                      │
                                      └────► Debit projection
```

- Module khác chỉ tham chiếu public identifiers/contracts; không sửa table owner trực tiếp ngoài application transaction đã định nghĩa.
- Delivery tiêu thụ Inventory, nhưng Inventory không sở hữu Delivery status.
- Debit projection chỉ đọc Delivery; không ghi ngược.

## Data ownership

### Source records

- Master FK là identity hiện tại của Customer, F/G, UOM, Process và rate.
- Document snapshots là lịch sử bất biến tại thời điểm create/post.
- Stock Movement là journal nguồn để đối soát physical stock.
- Stock Position là current balance tối ưu query, cập nhật cùng transaction với movement.
- Audit Event ghi actor/action/entity và before/after có kiểm soát.

### Không tin client

Backend tự tạo hoặc tính lại:

- document number;
- status/transition;
- `production_qty`;
- line amount và document totals;
- customer/currency/rate authoritative của Delivery;
- stock balances;
- actor/timestamps/audit;
- effective permission.

## Identifier và numbering

- Internal PK: UUID thống nhất; ưu tiên UUIDv7 nếu Java/PostgreSQL stack được chọn hỗ trợ ổn định, nếu không dùng UUIDv4.
- Business document number độc lập PK.
- Format đã chốt:
  - `SO-YYYY-NNNNNN` — SYS PO/Buyer Order.
  - `PR-YYYY-NNNNNN` — Production Order.
  - `DN-YYYY-NNNNNN` — Delivery Note.
- `document_number_counter` sở hữu counter theo `(document_type, year)` và cấp số atomic.
- Với SYS PO, `YYYY` là năm allocation khi tạo draft; sửa `po_date` không đổi số đã cấp.
- Không tái sử dụng số; gap được chấp nhận.
- External Buyer PO là user input, không dùng internal sequence.

## Time và numeric policy

- Instant/audit: `timestamptz`, lưu UTC.
- Business date: `date`.
- Exchange month: `date` normalized ngày đầu tháng.
- UI hiển thị theo `Asia/Bangkok`.
- Quantity: `numeric(18,4)`.
- Unit price/FX rate: `numeric(18,6)`.
- Monetary amount: `numeric(18,2)`.
- VAT percent: `numeric(7,4)`.
- Item amount round half-up; header total là tổng item đã round.
- Không dùng `double precision` cho quantity/price/rate/amount.

## State machines

### Buyer Order

```text
STANDBY ──confirm──► CONFIRMED
CONFIRMED ──reopen only without downstream──► STANDBY
```

Confirm tạo đúng một Production Order cho mỗi item. Reopen bị chặn nếu Production đã group/finish hoặc đã có Delivery/Stock Movement.

### Production

```text
OPEN ──finish once──► FINISHED
```

Finish một lần, với final `produced_qty`; tạo Stock Position và `PRODUCTION` Movement nguyên tử. Không partial production phase đầu.

### Delivery

```text
DRAFT ──post──► POSTED ──reverse──► REVERSED
```

Sửa posted document:

1. reverse document cũ;
2. append reverse stock movements;
3. tạo replacement draft tham chiếu document cũ;
4. post replacement thành document mới.

Posted/Reversed data không hard-delete.

## Transaction boundaries

### Confirm Buyer Order

1. Lock Buyer Order.
2. Verify `STANDBY` và version.
3. Validate items/master references/formulas.
4. SYS PO đã được cấp ngay khi tạo draft; giữ nguyên và không cấp lại tại confirm.
5. Transition `CONFIRMED`.
6. Create one Production Order/item idempotently.
7. Append business/audit events.
8. Commit.

### Reopen Buyer Order

- Lock Buyer Order và related Production Orders.
- Reject nếu group, finish, stock position/movement hoặc Delivery tồn tại.
- Remove/cancel only auto-created open Production records theo retention implementation.
- Transition về `STANDBY`; append audit.

### Finish Production

1. Lock Production Order; group/ungroup dùng cùng lock để không chạy đồng thời.
2. Verify `OPEN`, đúng đúng một config subtype khớp product kind và final quantity.
3. Transition `FINISHED`.
4. Create unique Stock Position.
5. Append signed `PRODUCTION` movement.
6. Update position balances/version.
7. Append Production/Audit Event.
8. Commit.

### Post Delivery

1. Lock Delivery draft.
2. Lock Stock Positions theo UUID ascending để tránh deadlock.
3. Re-read physical/order capacity.
4. Verify same customer/currency; lookup monthly rate tại Post, yêu cầu effective month khớp delivery date và snapshot rate bất biến.
5. Canonicalize snapshots; compute line/header totals.
6. Allocate Delivery Number.
7. Set `POSTED`.
8. Append `DELIVERY` movements and update positions.
9. Append Delivery/Audit Event.
10. Commit.

### Reverse Delivery

- Lock posted Delivery, all positions và outbound item sources theo cùng order.
- Reject repeated reversal.
- Reject nếu bất kỳ Delivery Item nào đã có `RETURN` movement; phase đầu chưa có workflow đảo Return, tránh tạo tồn vượt sản xuất.
- Append `DELIVERY_REVERSAL` movements.
- Restore positions; mark `REVERSED` with actor/reason/time.
- Append Delivery/Audit Event.

### Return/Dispose

- Lock Stock Position.
- Return phải tham chiếu một Delivery Note Item đã post của cùng Stock Position; lock outbound source và giới hạn tổng return không vượt delivered quantity ròng chưa return/reverse.
- Dispose phải có quantity dương và không vượt current stock.
- Append `RETURN`/`DISPOSE` movement trực tiếp; movement là record nghiệp vụ và ledger duy nhất, không tạo bảng adjustment song song.
- Update position và append audit trong cùng transaction.
- Return đồng thời với Delivery reversal/return khác phải serialize trên cùng Stock Position và outbound source.

## Concurrency và idempotency

- Default isolation: `READ COMMITTED`.
- Pessimistic row lock cho state transition và stock.
- Optimistic `version` trên mutable aggregate/current position để phát hiện stale update.
- Command endpoints create/confirm/finish/post/reverse/adjust nhận idempotency key.
- Unique constraints chống duplicate downstream relation và repeated command.
- Core Delivery Item ↔ Stock Movement dùng composite/deferred integrity để bảo đảm cùng position, đúng movement type và source line; polymorphic source IDs chỉ bổ sung audit/navigation.
- Production Group scope và Buyer Order PIC ownership dùng composite FK; config subtype/group member count dùng deferred validation khi simple FK/check không đủ.
- Nâng `SERIALIZABLE` chỉ khi concurrency tests chứng minh row locking không đủ.

## Referential và delete policy

| Loại record | Policy |
|---|---|
| Master chưa dùng | Có thể delete theo admin policy |
| Master/User đã dùng | Archive/disable; giữ FK/history |
| Buyer Order draft | Discard có audit theo retention |
| Confirmed order | Reopen/cancel workflow; không generic delete |
| Finished Production | Không delete |
| Stock Movement | Append-only |
| Delivery draft | Discard có audit |
| Posted/Reversed Delivery | Không delete; only reverse/replace |
| Login Event | Retention 365 ngày |
| Business Audit/Events/Stock | Tối thiểu 7 năm |

Không dùng cascading delete từ master xuống transaction.

## Permission model

Effective permission precedence:

```text
explicit user DENY
> explicit user ALLOW
> role permission
> default DENY
```

Permission key theo nghiệp vụ, ví dụ:

- Master: `VIEW`, `CREATE`, `UPDATE`, `ARCHIVE`.
- Buyer Order: `VIEW`, `CREATE`, `UPDATE`, `CONFIRM`, `REOPEN`.
- Production: `VIEW`, `GROUP`, `CONFIGURE`, `FINISH`.
- Delivery: `VIEW`, `CREATE`, `POST`, `REVERSE`, `PRINT`, `EXPORT`.
- Stock: `VIEW`, `RETURN`, `DISPOSE`.
- Admin: `MANAGE_USERS`, `MANAGE_ROLES`, `MANAGE_ALLOWLIST`, `VIEW_AUDIT`.

Role SALE/ADMIN chứa baseline; override chỉ lưu khác biệt. Mọi thay đổi role/permission có audit.

## Security

- Password chỉ lưu Argon2id hash; bcrypt chỉ dùng nếu runtime compatibility bắt buộc.
- Không lưu hoặc log password/plain credential.
- Profile/F/G image ở object storage; DB lưu key, MIME, byte size, checksum.
- API role không UPDATE/DELETE Stock Movement, Audit Event hoặc posted history.
- V010 revoke quyền `PUBLIC`; deployment tạo runtime role và truyền PostgreSQL setting `erp.runtime_role` để migration cấp quyền tối thiểu.
- Document-number allocator là `SECURITY DEFINER` với fixed `search_path`; runtime role không được update counter table trực tiếp.
- TLS, least-privilege DB roles, encrypted backup và secret manager là bắt buộc production.
- IP allowlist chỉ enforce khi đã chốt trusted proxy chain; không tin `X-Forwarded-For` tùy ý.
- JSONB audit phải redact password/hash/token và dữ liệu nhạy cảm không cần thiết.

## Index strategy

Index theo query thực, không index mọi cột:

- mọi FK phục vụ join/filter;
- unique login/customer short name/RM code/UOM/rate month/F/G composite/document number;
- Buyer Order `(status, po_date)`, `customer_id`;
- Production `(status, delivery_date)`, group và BO item;
- Delivery `(status, delivery_date)`, customer, document number;
- Stock Position partial/query index cho `current_qty > 0` và `= 0` nếu query plan chứng minh;
- Stock Movement `(stock_position_id, occurred_at, id)`;
- Login/Audit `(actor_user_id, occurred_at desc)` và entity/time.

Partition audit/login chỉ khi volume/retention đo được yêu cầu; YAGNI mặc định không partition.

## Debit projection

Active Debit row được derive từ `POSTED Delivery Note JOIN Delivery Note Item`.

- Reference: `Delivery No + line number`, ví dụ `DN-2026-000001/01`.
- Không sequence/table Debit riêng.
- Reversed Delivery không xuất hiện trong active projection.
- Historical query vẫn thấy reversed source với status/audit.
- List và export dùng cùng view/query để tránh lệch dữ liệu.

## Vận hành và migration

Khi user duyệt logical schema:

- dùng Flyway tại `src/main/resources/db/migration/` nếu backend Spring Boot;
- migration immutable sau khi đã chạy môi trường chia sẻ;
- thay đổi dùng forward migration;
- reference seed tách demo data;
- test apply từ database trống và upgrade từ version trước;
- backup/restore drill trước destructive data migration.

## Verification gates

1. Constraint tests.
2. Workflow integration tests.
3. Concurrent Delivery test.
4. Stock ledger reconciliation.
5. Authorization matrix/security tests.
6. Frontend contract tests.
7. `EXPLAIN (ANALYZE, BUFFERS)` với dataset đại diện.
8. Review logical schema trước DDL; review DDL trước apply.

## References

- [ERP logical ERD](erp-erd.md)
- [ERP data dictionary](erp-data-dictionary.md)
- [Evidence and requirements](../../plans/260728-0906-postgresql-erp-schema/evidence-and-requirements.md)
- [Frontend contract mapping](../../plans/260728-0906-postgresql-erp-schema/frontend-contract-mapping.md)
- [Operational Core v1 — Phase 1 source of truth](../operational-core-v1-spec.md)
- [API v1 OpenAPI contract](../api/erp-v1-openapi.yaml)
- [Authentication and authorization](../security/authentication-and-authorization.md)

## Phase 1 auth/session extension status

The D1-D12/current architecture decisions above remain preserved historical database design. Phase 1 froze the application contract before implementation. V011 now adds `app_user.must_change_password` and `password_generation`, the `identity.auth_session` and `identity.refresh_token` persistence required for rotating hash-only refresh tokens, the approved SALE master-data grants, and explicit least-privilege grants on its new objects. V001-V010 remain byte-identical.

The backend test profile uses PostgreSQL 16 Testcontainers with Flyway. `mvn test` includes the `*IT` classes and verifies both empty-database V001→V011 migration and V010→V011 upgrade, a separate runtime database role, append-only audit/login protection, and the absence of H2. Production deployment still creates the migration owner and runtime role externally and configures `erp.runtime_role` before Flyway runs; application source contains no database credentials.

The authoritative session, CSRF, key-ring, recovery-admin, permission-precedence, and allowlist-configuration semantics remain in the linked Operational Core v1 and security documents; this architecture document does not duplicate those application contracts.