# Data Dictionary logic PostgreSQL ERP

## Tổng quan

Đây là logical schema đã áp dụng các quyết định được xác nhận. Tài liệu chốt table/column/type/constraint/index intent để review trước DDL. Chưa có migration SQL.

## Convention chung

### PostgreSQL schemas

| Schema | Trách nhiệm |
|---|---|
| `identity` | User, role, permission, login, IP allowlist |
| `master_data` | Shared business master/reference/media |
| `sales` | Buyer Order |
| `production` | Production Order/config/history |
| `inventory` | FG stock position và movement ledger |
| `delivery` | Delivery document/history/read projection |
| `audit` | Cross-module immutable audit |
| `system` | Document number counters/idempotency infrastructure |

### Type convention

| Dữ liệu | Type logic |
|---|---|
| PK/FK | `uuid` |
| Timestamp | `timestamptz`, UTC |
| Ngày nghiệp vụ | `date` |
| Quantity/dimension | `numeric(18,4)` |
| Unit price/FX rate | `numeric(18,6)` |
| Amount | `numeric(18,2)` |
| VAT percent | `numeric(7,4)` |
| Version optimistic lock | `bigint` |
| IP address | `inet` |
| Status/action | `varchar` + controlled check/reference, không PostgreSQL enum phase đầu |

Common mutable record columns khi phù hợp:

- `id uuid PK`;
- `version bigint NOT NULL DEFAULT 0`;
- `created_at`, `created_by`;
- `updated_at`, `updated_by`;
- `status` hoặc `archived_at`, không dùng soft-delete boolean mơ hồ.

Tên/code canonical được trim/uppercase/lowercase ở application và có unique functional index tương ứng. Không dựa vào collation mặc định để enforce business key case-insensitive.

## Schema `system`

### `document_number_counter`

Cấp số chứng từ theo loại và năm.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `document_type` | varchar(20) | No | `SALES_ORDER`, `PRODUCTION`, `DELIVERY` |
| `document_year` | smallint | No | năm business document |
| `last_value` | bigint | No | `>= 0` |
| `updated_at` | timestamptz | No | UTC |

- PK: `(document_type, document_year)`.
- Counter update atomic/locked; gap được chấp nhận; không decrement/tái sử dụng.
- Counter không cần FK từ document.

### `idempotency_record`

Chống command retry tạo side effect kép.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `scope` | varchar(60) | No | command type |
| `idempotency_key` | varchar(120) | No | caller-provided opaque key |
| `request_hash` | varchar(128) | No | phát hiện key reuse khác payload |
| `resource_type` | varchar(60) | Yes | result aggregate type |
| `resource_id` | uuid | Yes | result aggregate ID |
| `status` | varchar(20) | No | `IN_PROGRESS`, `COMPLETED`, `FAILED` theo implementation policy |
| `created_at`, `expires_at` | timestamptz | No | retention theo command |

- Unique: `(scope, idempotency_key)`.
- Không lưu raw credential/request body nhạy cảm.

## Schema `identity`

### `app_user`

Credential identity và profile USER/STAFF.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `kind` | varchar(10) | No | `USER`, `STAFF` |
| `login_id` | varchar(100) | No | immutable; canonical unique |
| `password_hash` | varchar(255) | No | Argon2id ưu tiên; không output API |
| `group_name` | varchar(120) | Yes | profile metadata |
| `division` | varchar(120) | Yes | profile metadata |
| `position` | varchar(120) | No | BRD required |
| `name` | varchar(200) | No | BRD required |
| `sex` | varchar(20) | Yes | `MALE`, `FEMALE`, `OTHER` |
| `phone` | varchar(50) | Yes | không ép numeric |
| `email` | varchar(320) | Yes | canonical validation application |
| `remark` | text | Yes | profile note |
| `avatar_asset_id` | uuid | Yes | FK `master_data.media_asset` |
| `status` | varchar(20) | No | `ACTIVE`, `DISABLED`, `ARCHIVED` |
| `password_changed_at` | timestamptz | No | session/security control |
| common audit/version | — | No | creator/updater/version |

- Unique functional index: `lower(trim(login_id))`.
- Không hard-delete khi đã có creator/audit references.
- Index: `(status)`, canonical login.

### `role`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `code` | varchar(60) | No | canonical unique, seed `SALE`, `ADMIN` |
| `name` | varchar(120) | No | display |
| `description` | text | Yes | purpose |
| `active` | boolean | No | default true |
| common audit/version | — | No | audit |

### `permission`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `module_code` | varchar(60) | No | e.g. `BUYER_ORDER` |
| `action_code` | varchar(60) | No | e.g. `CONFIRM` |
| `description` | text | Yes | behavior protected |
| `active` | boolean | No | controlled reference |

- Unique: `(module_code, action_code)`.
- Seed permission theo nghiệp vụ, không dùng bitmask/JSON.

### `user_role`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `user_id` | uuid | No | FK App User |
| `role_id` | uuid | No | FK Role |
| `assigned_at` | timestamptz | No | UTC |
| `assigned_by` | uuid | No | FK App User |

- PK: `(user_id, role_id)`.
- Index reverse lookup: `(role_id, user_id)`.

### `role_permission`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `role_id` | uuid | No | FK Role |
| `permission_id` | uuid | No | FK Permission |
| `granted_at` | timestamptz | No | UTC |
| `granted_by` | uuid | No | FK App User |

- PK: `(role_id, permission_id)`.

### `user_permission_override`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `user_id` | uuid | No | FK App User |
| `permission_id` | uuid | No | FK Permission |
| `effect` | varchar(10) | No | `ALLOW` hoặc `DENY` |
| `reason` | text | Yes | audit rationale |
| `updated_at`, `updated_by` | — | No | audit |

- PK: `(user_id, permission_id)`.
- Precedence: DENY > ALLOW > role > default DENY.

### `login_event`

Append-only, retention 365 ngày.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `user_id` | uuid | Yes | nullable khi login ID không tồn tại |
| `login_id_attempted` | varchar(100) | No | redact/canonical policy |
| `outcome` | varchar(30) | No | success/failure category, không leak detail cho client |
| `client_ip` | inet | Yes | chỉ trusted proxy resolved |
| `ip_name_snapshot` | varchar(200) | Yes | optional allowlist label |
| `user_agent` | varchar(500) | Yes | length-limited |
| `occurred_at` | timestamptz | No | UTC |

- Index: `(user_id, occurred_at DESC)`, `(occurred_at)`.
- Không lưu password/token.

### `ip_allowlist_entry`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `network` | inet | No | IPv4/IPv6/CIDR canonical |
| `name` | varchar(200) | No | business label |
| `active` | boolean | No | enable entry |
| common audit/version | — | No | audit |

- Unique: `network`.
- Global feature enable nằm application config/secret-managed config, không tạo singleton setting table phase đầu.

## Schema `audit`

### `audit_event`

Append-only, giữ tối thiểu 7 năm.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `actor_user_id` | uuid | Yes | FK App User, nullable system actor |
| `actor_type` | varchar(20) | No | `USER`, `SYSTEM` |
| `action` | varchar(100) | No | stable business action |
| `entity_type` | varchar(100) | No | aggregate type |
| `entity_id` | uuid | No | aggregate ID; logical reference |
| `request_id` | varchar(120) | Yes | trace ID |
| `reason` | text | Yes | mandatory với reverse/admin override theo application |
| `before_data` | jsonb | Yes | redacted business snapshot |
| `after_data` | jsonb | Yes | redacted business snapshot |
| `occurred_at` | timestamptz | No | UTC |

- Index: `(entity_type, entity_id, occurred_at)`, `(actor_user_id, occurred_at DESC)`, `(occurred_at)`.
- JSON không chứa password/hash/token/raw image.
- Application DB role không UPDATE/DELETE.

## Schema `master_data`

### `media_asset`

Object storage metadata; không lưu binary/Data URL.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `storage_key` | varchar(500) | No | unique opaque object key |
| `original_filename` | varchar(255) | No | display only, sanitized |
| `mime_type` | varchar(100) | No | allowlist PNG/JPEG/WEBP theo use case |
| `byte_size` | bigint | No | `> 0` và app max limit |
| `sha256` | char(64) | No | integrity/dedup aid |
| `uploaded_by` | uuid | No | FK App User |
| `created_at` | timestamptz | No | UTC |

- Unique `storage_key`; optional checksum index khi dedup cần.

### `currency`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `code` | char(3) | No | PK ISO-like code; seed USD/VND/WON |
| `name` | varchar(100) | No | display |
| `active` | boolean | No | controlled reference |

### `uom`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `code` | varchar(30) | No | canonical unique |
| `name` | varchar(100) | Yes | optional display |
| `status` | varchar(20) | No | `ACTIVE`, `ARCHIVED` |
| common audit/version | — | No | audit |

- Unique functional index uppercase/trim code.
- Archive khi đã dùng; không conversion phase đầu.

### `monthly_exchange_rate`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `effective_month` | date | No | ngày 01 mỗi tháng, unique |
| `vnd_usd_rate` | numeric(18,6) | No | `> 0` |
| `won_usd_rate` | numeric(18,6) | No | `> 0` |
| `source` | varchar(120) | Yes | manual/Frankfurter metadata |
| `status` | varchar(20) | No | `ACTIVE`, `ARCHIVED` |
| common audit/version | — | No | audit |

- Check day of month = 1.
- Không update/archive nếu posted Delivery tham chiếu; correction tạo version/replacement policy ở application.

### `customer`

Một Customer Master duy nhất.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `short_name` | varchar(50) | No | immutable canonical unique |
| `name` | varchar(200) | No | required |
| `address` | text | Yes | current master address |
| `telephone` | varchar(50) | Yes | text |
| `currency_code` | char(3) | No | FK Currency |
| `status` | varchar(20) | No | `ACTIVE`, `ARCHIVED` |
| common audit/version | — | No | audit |

- Unique functional index `upper(trim(short_name))`.
- Index: `(status)`, canonical name search as required.

### `customer_contact`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `customer_id` | uuid | No | FK Customer |
| `division` | varchar(120) | Yes | optional |
| `name` | varchar(200) | No | required |
| `telephone` | varchar(50) | Yes | optional |
| `email` | varchar(320) | Yes | optional |
| `remark` | text | Yes | optional |
| `is_default` | boolean | No | default false |
| `status` | varchar(20) | No | `ACTIVE`, `ARCHIVED` |
| common audit/version | — | No | audit |

- Unique `(id, customer_id)` để Buyer Order dùng composite FK và chứng minh contact thuộc đúng Customer.
- Partial unique: one `is_default=true AND status='ACTIVE'` per customer.
- Index: `(customer_id, status)`.

### `supplier` / `supplier_contact`

Cùng audit/status/contact pattern với Customer.

`supplier` fields:

- `id`, `name` required canonical unique, `address`, `telephone`, `status`, common audit/version.

`supplier_contact` fields:

- `id`, `supplier_id`, `division`, `name`, `telephone`, `email`, `remark`, `is_default`, `status`, common audit/version.

Constraints/index giống Customer Contact. Không có `used_in_purchase_order` boolean.

### `process_master`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK/stable identity |
| `name` | varchar(150) | No | canonical unique |
| `sequence_no` | integer | No | `> 0` |
| `qr_value` | varchar(255) | No | immutable unique |
| `status` | varchar(20) | No | `ACTIVE`, `ARCHIVED` |
| common audit/version | — | No | audit |

- Process name có thể đổi khi business cho phép; QR không đổi.

### `raw_material`

Master only, không inventory accumulator.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `category` | varchar(120) | Yes | phase đầu free/controlled text |
| `code` | varchar(80) | No | canonical unique |
| `name` | varchar(200) | No | required |
| `specification`, `size`, `color` | varchar/text | Yes | attributes |
| `uom_id` | uuid | No | FK UOM |
| `reference_price` | numeric(18,6) | Yes | `>= 0` |
| `currency_code` | char(3) | No | FK Currency |
| `supplier_id` | uuid | Yes | FK Supplier |
| `safety_stock_qty` | numeric(18,4) | Yes | `>= 0`; policy target, không current inventory |
| `remark` | text | Yes | optional |
| `status` | varchar(20) | No | `ACTIVE`, `ARCHIVED` |
| common audit/version | — | No | audit |

- Không có `inventory`, `balance`, `used_in_purchase_order` phase đầu.

### `finished_good`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `product_kind` | varchar(20) | No | `PRINT`, `WOVEN` |
| `style_no` | varchar(120) | No | required |
| `name` | varchar(200) | No | required |
| `size`, `color` | varchar(120) | Yes | canonical null/blank policy |
| `uom_id` | uuid | No | FK UOM |
| `reference_price` | numeric(18,6) | Yes | `>= 0` |
| `currency_code` | char(3) | Yes | required when reference price exists |
| `image_asset_id` | uuid | Yes | FK Media Asset |
| `status` | varchar(20) | No | `ACTIVE`, `ARCHIVED` |
| common audit/version | — | No | audit |

- Unique canonical composite: product kind + style + name + normalized size + normalized color.
- Check price/currency co-presence.

## Schema `sales`

### `buyer_order`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `sys_po_no` | varchar(30) | No | unique `SO-YYYY-NNNNNN`; YYYY là năm cấp số khi tạo draft, không phụ thuộc PO Date và không cấp lại |
| `order_type` | varchar(80) | No | controlled later if vocabulary stable |
| `customer_id` | uuid | No | FK Customer |
| `customer_name_snapshot` | varchar(200) | No | immutable document snapshot |
| `customer_short_name_snapshot` | varchar(50) | No | snapshot |
| `customer_contact_id` | uuid | Yes | optional FK Customer Contact |
| `pic_source` | varchar(20) | No | `MASTER`, `CUSTOM` |
| `pic_name_snapshot` | varchar(200) | No | immutable |
| `buyer_po` | varchar(120) | No | external customer reference |
| `po_date`, `delivery_date` | date | No | business dates |
| `status` | varchar(20) | No | `STANDBY`, `CONFIRMED` |
| common audit/version | — | No | audit/optimistic lock |

- Check `delivery_date >= po_date` nếu business confirms; currently application validation candidate.
- Check PIC source/FK consistency: MASTER requires contact ID, CUSTOM requires contact ID null.
- Composite FK `(customer_contact_id, customer_id)` → `customer_contact(id, customer_id)` chứng minh contact thuộc đúng Customer; không chỉ dựa application validation.
- Index: unique sys PO; `(status, po_date)`, `(customer_id, po_date)`, external `buyer_po` search.

### `buyer_order_item`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `buyer_order_id` | uuid | No | FK Buyer Order |
| `line_no` | integer | No | `> 0`, unique/order |
| `is_custom` | boolean | No | custom-vs-master mode |
| `finished_good_id` | uuid | Yes | FK F/G; required iff not custom |
| product/style/name/size/color snapshots | varchar | Mixed | product/style/name required |
| `uom_id` | uuid | No | FK UOM source |
| `uom_code_snapshot` | varchar(30) | No | immutable |
| `order_qty` | numeric(18,4) | No | `> 0` |
| `use_stock_qty` | numeric(18,4) | No | phase đầu bắt buộc `= 0`; allocation/cross-PO stock ngoài scope |
| `production_qty` | numeric(18,4) | No | phase đầu bằng `order_qty`; vẫn server-derived |
| `unit_price` | numeric(18,6) | No | `>= 0` |
| `currency_code` | char(3) | No | FK Currency/snapshot source |
| `amount` | numeric(18,2) | No | server-derived/rounded |
| `remark` | text | Yes | optional |
| common audit columns | — | No | immutable after confirm |

- Unique `(buyer_order_id, line_no)` và unique `(id, buyer_order_id)` để Production Order dùng composite source FK.
- Check `(is_custom AND finished_good_id IS NULL) OR (!is_custom AND finished_good_id IS NOT NULL)`.
- Check `use_stock_qty = 0` trong phase đầu; backend trả business error nếu client gửi giá trị dương.
- DB generated/check formula may be evaluated in DDL design; application always recomputes.

## Schema `production`

### `production_group`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `buyer_order_id` | uuid | No | FK Buyer Order; group scope |
| `group_no` | varchar(40) | No | unique display/reference |
| common audit/version | — | No | audit |

- Unique `(id, buyer_order_id)` trên group và composite FK từ Production Order `(production_group_id, buyer_order_id)` → group `(id, buyer_order_id)` ngăn group xuyên Buyer Order.
- Deferred group-membership constraint/transaction rule yêu cầu ít nhất hai members trước commit; group/ungroup lock member orders, xóa group rỗng atomically và không chạy đồng thời với Finish.

### `production_order`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `production_no` | varchar(30) | No | unique `PR-YYYY-NNNNNN` |
| `buyer_order_item_id` | uuid | No | unique FK BO Item |
| `buyer_order_id` | uuid | No | immutable FK Buyer Order; copied from source item for enforceable group scope |
| `production_group_id` | uuid | Yes | part of composite FK group scope |
| `product_kind_snapshot` | varchar(20) | No | `PRINT`, `WOVEN` |
| `product_no` | varchar(120) | No | derived/reference display |
| `qr_value` | varchar(255) | No | immutable unique |
| `planned_qty` | numeric(18,4) | No | from BO production qty |
| `produced_qty` | numeric(18,4) | Yes | set exactly once on finish; `>= 0` |
| `status` | varchar(20) | No | `OPEN`, `FINISHED` |
| `finished_at`, `finished_by` | timestamptz/uuid | Yes | both present iff FINISHED |
| common audit/version | — | No | optimistic lock |

- One Production Order per BO Item.
- Composite FK `(buyer_order_item_id, buyer_order_id)` → `buyer_order_item(id, buyer_order_id)` chứng minh Production Order thuộc đúng Buyer Order của source item.
- Unique `(id, buyer_order_item_id)` để Stock Position dùng composite source FK.
- Finish once; status/fields consistency checks.
- Index: `(status, created_at)`, group, source item.

### `production_print_config`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `production_order_id` | uuid | No | PK/FK Production |
| `material_source` | varchar(20) | Yes | `STOCK`, `PURCHASE` |
| `order_kind` | varchar(40) | Yes | Layout/Sample/New/Repeat/Second |
| `remark` | text | Yes | optional |
| `updated_at`, `updated_by` | — | No | audit |

- Deferred subtype constraint/trigger chỉ cho phép row khi Production `product_kind_snapshot='PRINT'`, cấm WOVEN config đồng thời; Finish yêu cầu đúng một config subtype khớp kind.

### `production_order_process`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `production_order_id` | uuid | No | FK Production |
| `process_id` | uuid | No | FK Process Master |
| `sequence_no` | integer | No | snapshot ordering, `> 0` |
| `speed` | numeric(18,4) | Yes | `> 0` when supplied |

- PK `(production_order_id, process_id)`; unique order/sequence if required.

### `production_woven_config`

Full BRD fields, nullable phase đầu.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `production_order_id` | uuid | No | PK/FK Production |
| `bim` | varchar(40) | Yes | WHITE/BLACK/100D/75D/50D or controlled text |
| `ten_dia` | varchar(120) | Yes | domain text pending validation |
| `mat_do` | varchar(120) | Yes | density text pending unit |
| `pick` | varchar(120) | Yes | pending unit |
| `x_ngang_mm` | numeric(18,4) | Yes | `> 0` |
| `y_doc_mm` | numeric(18,4) | Yes | `> 0` |
| `hai_da_mm` | numeric(18,4) | Yes | `>= 0` |
| `logo_x_mm`, `logo_y_mm` | numeric(18,4) | Yes | `>= 0` |
| `ho_percent` | numeric(7,4) | Yes | `0..100` |
| `remark` | text | Yes | optional |
| `updated_at`, `updated_by` | — | No | audit |

- Deferred subtype constraint/trigger chỉ cho phép row khi Production `product_kind_snapshot='WOVEN'`, cấm PRINT config đồng thời; các WOVEN detail nullable nhưng config parent phải tồn tại trước Finish.

### `production_woven_weave_type`

- Columns: `production_order_id` FK Woven Config, `weave_type` varchar(40).
- PK `(production_order_id, weave_type)`.
- Initial values: THUONG, HAI_DA, SATIN, SATIN_HAI_DA.

### `production_woven_yarn_line`

- `id uuid PK`, `production_order_id FK`, `line_no > 0`, `yarn`, `yarn_code`, `denier`, audit timestamps.
- Unique `(production_order_id, line_no)`.

### `production_event`

Append-only 7 năm:

- `id`, `production_order_id`, `event_type` (`CREATED`, `GROUPED`, `UNGROUPED`, `CONFIGURED`, `FINISHED`), actor, reason/note, occurred_at, payload JSONB redacted.
- Index `(production_order_id, occurred_at, id)`.

## Schema `inventory`

### `stock_position`

Một row/finished Production Order.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `production_order_id` | uuid | No | unique FK Production |
| `buyer_order_item_id` | uuid | No | unique source relation |
| `customer_id` | uuid | No | source FK for query/integrity |
| `currency_code` | char(3) | No | source currency |
| `uom_id` | uuid | No | source UOM |
| `order_qty` | numeric(18,4) | No | source snapshot |
| `produced_qty` | numeric(18,4) | No | `>= 0` |
| `delivered_qty` | numeric(18,4) | No | `>= 0` |
| `returned_qty` | numeric(18,4) | No | `>= 0` |
| `disposed_qty` | numeric(18,4) | No | `>= 0` |
| `current_qty` | numeric(18,4) | No | `>= 0` |
| `order_balance_qty` | numeric(18,4) | No | `>= 0` |
| common audit/version | — | No | row locking/version |

- Composite FK `(production_order_id, buyer_order_item_id)` → `production_order(id, buyer_order_item_id)` chứng minh position thuộc đúng source chain; Customer/UOM/Currency/order quantity được canonicalize từ chain này trong Finish transaction.
- No transfer accumulators phase đầu.
- Reconcile:
  - `current = produced + returned - delivered - disposed`.
  - `order balance = order - delivered + returned`.
- Index: customer, production/source item; partial current > 0 if query plan supports.

### `stock_movement`

Immutable journal.

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `stock_position_id` | uuid | No | FK Position |
| `movement_type` | varchar(30) | No | PRODUCTION, DELIVERY, DELIVERY_REVERSAL, RETURN, DISPOSE |
| `quantity_signed` | numeric(18,4) | No | non-zero; sign consistent with type |
| `balance_after` | numeric(18,4) | No | `>= 0` |
| `business_date` | date | No | transaction date |
| `source_type` | varchar(40) | No | production/delivery/adjustment |
| `source_id`, `source_item_id` | uuid | Mixed | source document/item; core Delivery relation được enforce, không chỉ logical best effort |
| `return_source_delivery_item_id` | uuid | Yes | FK Delivery Item, required iff movement type RETURN |
| `idempotency_key` | varchar(120) | No | unique scoped command effect |
| `reason` | text | Yes | required for reverse/return/dispose |
| `created_by` | uuid | No | actor FK |
| `occurred_at` | timestamptz | No | UTC |

- Unique `(stock_position_id, idempotency_key)`; command-level key vẫn được quản lý bởi `system.idempotency_record`.
- RETURN phải trỏ Delivery Item cùng Stock Position, từ một posted outbound delivery; tổng concurrent returns không vượt delivered quantity ròng chưa return/reverse. Transaction lock position và source item.
- Core Delivery/Return source integrity dùng FK/composite keys/deferred trigger; polymorphic `source_type/source_id` chỉ bổ sung audit/navigation.
- Index `(stock_position_id, occurred_at, id)`, source reference và `return_source_delivery_item_id`.
- Application role cannot UPDATE/DELETE.

Không cần bảng Return/Dispose riêng: movement type + enforced source/reason/business date chứa đủ nghiệp vụ phase đầu, tránh hai nguồn sự thật.

## Schema `delivery`

### `delivery_note`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `delivery_no` | varchar(30) | Yes until post | unique khi POSTED/REVERSED |
| `customer_id` | uuid | No | FK Customer |
| `customer_name_snapshot` | varchar(200) | No | document history |
| `customer_address_snapshot` | text | Yes | PDF/history |
| `delivery_date` | date | No | business date |
| `currency_code` | char(3) | No | same as all source positions |
| `exchange_rate_id` | uuid | Yes in DRAFT | FK monthly rate; required POSTED/REVERSED |
| `vnd_usd_rate_snapshot` | numeric(18,6) | Yes in DRAFT | required và immutable after post |
| `won_usd_rate_snapshot` | numeric(18,6) | Yes in DRAFT | required và immutable after post |
| `vat_percent` | numeric(7,4) | No | `0..100` |
| `remark` | text | Yes | optional |
| `total_qty` | numeric(18,4) | No | server-derived |
| `total_amount` | numeric(18,2) | No | sum rounded items |
| `status` | varchar(20) | No | `DRAFT`, `POSTED`, `REVERSED` |
| `replaces_delivery_id` | uuid | Yes | self FK to reversed original |
| `reversed_at`, `reversed_by`, `reversal_reason` | mixed | Yes | all required iff REVERSED |
| `posted_at`, `posted_by` | mixed | Yes | required iff POSTED/REVERSED |
| common audit/version | — | No | draft mutability/lock |

- Unique non-null delivery number.
- DRAFT cho phép rate/rate snapshots null. POSTED/REVERSED bắt buộc đủ rate, effective month khớp `delivery_date`; snapshots bất biến sau post.
- Reverse bị chặn nếu bất kỳ Delivery Item nào đã có RETURN movement; phase đầu chưa có workflow đảo Return.
- A replacement references at most one prior Delivery; application prevents replacement chains/cycles ambiguity.
- Index `(status, delivery_date)`, `(customer_id, delivery_date)`, replacement.

### `delivery_note_item`

| Column | Type | Null | Rule |
|---|---|---:|---|
| `id` | uuid | No | PK |
| `delivery_note_id` | uuid | No | FK Delivery |
| `line_no` | integer | No | `> 0` |
| `stock_position_id` | uuid | No | FK Position |
| source order/production IDs | uuid | No | immutable traceability |
| SYS PO/Buyer PO/PIC/date snapshots | varchar/date | Mixed | immutable |
| product/style/name/size/color snapshots | varchar | Mixed | immutable |
| `uom_code_snapshot` | varchar(30) | No | immutable |
| `currency_code` | char(3) | No | must match header |
| `order_qty_snapshot` | numeric(18,4) | No | immutable |
| `produced_qty_snapshot` | numeric(18,4) | No | immutable |
| `delivery_qty` | numeric(18,4) | No | `> 0` |
| `unit_price` | numeric(18,6) | No | authoritative stock/order price |
| `amount` | numeric(18,2) | No | rounded server-derived |
| `delivery_movement_id` | uuid | Yes until post | unique FK Stock Movement |
| `reversal_movement_id` | uuid | Yes | unique FK Stock Movement after reverse |
| common timestamps | — | No | history |

- Unique `(delivery_note_id, line_no)`.
- Unique `(delivery_note_id, stock_position_id)`.
- Post fills delivery movement; reverse fills reversal movement exactly once.
- Enforce bằng composite reference/deferred trigger: delivery movement cùng `stock_position_id`, type `DELIVERY`, source document/item đúng Delivery/line; reversal tương tự với type `DELIVERY_REVERSAL`.
- POSTED yêu cầu mỗi item có đúng một delivery movement; REVERSED yêu cầu thêm đúng một reversal movement. Không chấp nhận FK tới movement khác position/type/source.

### `delivery_event`

Append-only 7 năm:

- `id`, `delivery_note_id`, `event_type` (`CREATED`, `UPDATED_DRAFT`, `POSTED`, `REVERSED`, `REPLACED`, `PRINTED` optional), actor, reason, occurred_at, payload redacted.
- Index `(delivery_note_id, occurred_at, id)`.

### `debit_note_projection` view

Không phải table mutable.

Nguồn: POSTED `delivery_note` + items.

Columns:

- `debit_reference = delivery_no || '/' || lpad(line_no, 2, '0')`;
- delivery ID/no/date;
- customer snapshot;
- SYS PO, Buyer PO, product/style/name snapshots;
- UOM, quantity, unit price, amount, currency.

Reversed Delivery bị loại khỏi active view. Historical/report query có thể dùng status-aware view riêng.

## Constraints xuyên bảng/application

Các rule dưới đây cần application transaction + DB constraints hỗ trợ:

- Customer Contact trên BO thuộc cùng Customer bằng composite FK.
- Standard BO item snapshots lấy từ F/G; custom item không có F/G ID.
- Production Group chỉ chứa orders cùng Buyer Order bằng composite FK và ít nhất hai rows bằng deferred validation.
- PRINT/WOVEN config subtype khớp product kind, cấm đồng thời, và đúng config parent bắt buộc trước Finish.
- Production finish chỉ một lần.
- Delivery positions cùng Customer/Currency.
- Delivery quantity không vượt locked physical/order capacity.
- Posted Delivery có number/movement cho mọi item.
- Reversed Delivery có reversal movement đúng position/type/source cho mọi item.
- RETURN có posted outbound Delivery Item source và không vượt net delivered quantity chưa return/reverse.
- Effective user permission precedence.

## Reference seed

Production-safe seed:

- Currency: USD, VND, WON.
- UOM: danh sách business duyệt (CON, EA, KG, M, MT, PC, PCS, ROLL, SET, YARD, YD, YDS...).
- Roles: SALE, ADMIN.
- Business-specific permissions.
- Product kinds/status/movement values nếu dùng reference tables.

Không seed production exchange rate giả, document, login history, IP allowlist hoặc plaintext admin password. Bootstrap admin lấy secret qua environment và hash an toàn.

## Index validation

Trước production, dùng dataset đại diện và `EXPLAIN (ANALYZE, BUFFERS)` cho:

- Buyer Order list/filter/search;
- Production OPEN/FINISHED/group;
- Delivery WAIT/POSTED và customer/date filters;
- Stock in/out list;
- Stock movement history;
- Debit projection/export;
- Login/audit queries.

Chỉ thêm index khi query path chứng minh cần; không partition phase đầu nếu chưa có volume evidence.

## Unresolved questions

Không còn business-decision blocker cho logical schema. Cần user duyệt logical schema này trước khi tạo DDL/Flyway migrations.