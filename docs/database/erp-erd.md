# ERD logic PostgreSQL ERP

## Tổng quan

ERD này là logical model đã áp dụng toàn bộ quyết định D1–D12. Chưa phải DDL. Chi tiết cột/type/constraint xem [erp-data-dictionary.md](erp-data-dictionary.md).

## ERD toàn hệ thống

```mermaid
erDiagram
    APP_USER ||--o{ USER_ROLE : assigned
    ROLE ||--o{ USER_ROLE : contains
    ROLE ||--o{ ROLE_PERMISSION : grants
    PERMISSION ||--o{ ROLE_PERMISSION : maps
    APP_USER ||--o{ USER_PERMISSION_OVERRIDE : overrides
    PERMISSION ||--o{ USER_PERMISSION_OVERRIDE : targets
    APP_USER ||--o{ LOGIN_EVENT : attempts
    APP_USER o|--o{ AUDIT_EVENT : acts

    MEDIA_ASSET o|--o{ APP_USER : avatar
    MEDIA_ASSET o|--o{ FINISHED_GOOD : image

    CUSTOMER ||--o{ CUSTOMER_CONTACT : has
    SUPPLIER ||--o{ SUPPLIER_CONTACT : has
    UOM ||--o{ RAW_MATERIAL : measures
    UOM ||--o{ FINISHED_GOOD : measures
    SUPPLIER o|--o{ RAW_MATERIAL : supplies

    CUSTOMER ||--o{ BUYER_ORDER : owns
    CUSTOMER_CONTACT o|--o{ BUYER_ORDER : same_customer_pic
    APP_USER ||--o{ BUYER_ORDER : creates
    BUYER_ORDER ||--|{ BUYER_ORDER_ITEM : contains
    FINISHED_GOOD o|--o{ BUYER_ORDER_ITEM : standard_item
    UOM ||--o{ BUYER_ORDER_ITEM : measures

    BUYER_ORDER_ITEM ||--|| PRODUCTION_ORDER : creates
    PRODUCTION_GROUP o|--o{ PRODUCTION_ORDER : groups
    PRODUCTION_ORDER ||--o| PRODUCTION_PRINT_CONFIG : print_config
    PRODUCTION_ORDER ||--o| PRODUCTION_WOVEN_CONFIG : woven_config
    PRODUCTION_WOVEN_CONFIG ||--o{ PRODUCTION_WOVEN_WEAVE_TYPE : weave_types
    PRODUCTION_WOVEN_CONFIG ||--o{ PRODUCTION_WOVEN_YARN_LINE : yarns
    PRODUCTION_ORDER ||--o{ PRODUCTION_ORDER_PROCESS : routes
    PROCESS_MASTER ||--o{ PRODUCTION_ORDER_PROCESS : selected
    PRODUCTION_ORDER ||--o{ PRODUCTION_EVENT : histories

    PRODUCTION_ORDER ||--o| STOCK_POSITION : finishes_to
    STOCK_POSITION ||--|{ STOCK_MOVEMENT : journals

    CUSTOMER ||--o{ DELIVERY_NOTE : receives
    MONTHLY_EXCHANGE_RATE ||--o{ DELIVERY_NOTE : snapshots
    DELIVERY_NOTE o|--o{ DELIVERY_NOTE : replaces
    DELIVERY_NOTE ||--|{ DELIVERY_NOTE_ITEM : contains
    STOCK_POSITION ||--o{ DELIVERY_NOTE_ITEM : allocates
    STOCK_MOVEMENT ||--o| DELIVERY_NOTE_ITEM : delivers
    STOCK_MOVEMENT o|--o| DELIVERY_NOTE_ITEM : reverses
    DELIVERY_NOTE_ITEM o|--o{ STOCK_MOVEMENT : return_source
    DELIVERY_NOTE ||--o{ DELIVERY_EVENT : histories

    DOCUMENT_NUMBER_COUNTER ||--o{ BUYER_ORDER : numbers
    DOCUMENT_NUMBER_COUNTER ||--o{ PRODUCTION_ORDER : numbers
    DOCUMENT_NUMBER_COUNTER ||--o{ DELIVERY_NOTE : numbers
```

Các quan hệ `DOCUMENT_NUMBER_COUNTER` trong sơ đồ là logical allocation relation, không nhất thiết là FK từ chứng từ tới counter row.

## Identity ERD

```mermaid
erDiagram
    APP_USER {
        uuid id PK
        varchar login_id UK
        varchar password_hash
        varchar kind
        varchar status
        bigint version
        timestamptz created_at
    }
    ROLE {
        uuid id PK
        varchar code UK
        varchar name
        boolean active
    }
    PERMISSION {
        uuid id PK
        varchar module_code
        varchar action_code
    }
    USER_ROLE {
        uuid user_id FK
        uuid role_id FK
    }
    ROLE_PERMISSION {
        uuid role_id FK
        uuid permission_id FK
    }
    USER_PERMISSION_OVERRIDE {
        uuid user_id FK
        uuid permission_id FK
        varchar effect
    }
    LOGIN_EVENT {
        uuid id PK
        uuid user_id FK
        varchar outcome
        inet client_ip
        timestamptz occurred_at
    }
    AUDIT_EVENT {
        uuid id PK
        uuid actor_user_id FK
        varchar action
        varchar entity_type
        uuid entity_id
        jsonb before_data
        jsonb after_data
        timestamptz occurred_at
    }

    APP_USER ||--o{ USER_ROLE : assigned
    ROLE ||--o{ USER_ROLE : assigned
    ROLE ||--o{ ROLE_PERMISSION : grants
    PERMISSION ||--o{ ROLE_PERMISSION : granted
    APP_USER ||--o{ USER_PERMISSION_OVERRIDE : overrides
    PERMISSION ||--o{ USER_PERMISSION_OVERRIDE : overridden
    APP_USER o|--o{ LOGIN_EVENT : produces
    APP_USER o|--o{ AUDIT_EVENT : acts
```

## Master Data ERD

```mermaid
erDiagram
    CUSTOMER {
        uuid id PK
        varchar short_name UK
        varchar name
        varchar currency_code
        varchar status
    }
    CUSTOMER_CONTACT {
        uuid id PK
        uuid customer_id FK
        varchar name
        boolean is_default
        varchar status
    }
    SUPPLIER {
        uuid id PK
        varchar name UK
        varchar status
    }
    SUPPLIER_CONTACT {
        uuid id PK
        uuid supplier_id FK
        varchar name
        boolean is_default
        varchar status
    }
    UOM {
        uuid id PK
        varchar code UK
        varchar status
    }
    MONTHLY_EXCHANGE_RATE {
        uuid id PK
        date effective_month UK
        numeric vnd_usd_rate
        numeric won_usd_rate
        varchar status
    }
    PROCESS_MASTER {
        uuid id PK
        varchar name UK
        integer sequence_no
        varchar qr_value UK
        varchar status
    }
    RAW_MATERIAL {
        uuid id PK
        varchar code UK
        uuid uom_id FK
        uuid supplier_id FK
        numeric reference_price
        varchar currency_code
        varchar status
    }
    FINISHED_GOOD {
        uuid id PK
        uuid uom_id FK
        varchar product_kind
        varchar style_no
        varchar name
        varchar size
        varchar color
        numeric reference_price
        varchar status
    }

    CUSTOMER ||--o{ CUSTOMER_CONTACT : has
    SUPPLIER ||--o{ SUPPLIER_CONTACT : has
    UOM ||--o{ RAW_MATERIAL : measures
    SUPPLIER o|--o{ RAW_MATERIAL : supplies
    UOM ||--o{ FINISHED_GOOD : measures
```

## Sales và Production ERD

```mermaid
erDiagram
    BUYER_ORDER {
        uuid id PK
        varchar sys_po_no UK
        uuid customer_id FK
        uuid customer_contact_id FK
        varchar pic_name_snapshot
        date po_date
        date delivery_date
        varchar status
        bigint version
    }
    BUYER_ORDER_ITEM {
        uuid id PK
        uuid buyer_order_id FK
        integer line_no
        uuid finished_good_id FK
        boolean is_custom
        uuid uom_id FK
        numeric order_qty
        numeric use_stock_qty
        numeric production_qty
        numeric unit_price
        numeric amount
    }
    PRODUCTION_ORDER {
        uuid id PK
        varchar production_no UK
        uuid buyer_order_item_id FK
        uuid buyer_order_id FK
        uuid production_group_id FK
        varchar status
        numeric planned_qty
        numeric produced_qty
        bigint version
    }
    PRODUCTION_GROUP {
        uuid id PK
        uuid buyer_order_id FK
        varchar group_no UK
    }
    PRODUCTION_PRINT_CONFIG {
        uuid production_order_id PK
        varchar material_source
        varchar order_kind
        varchar remark
    }
    PRODUCTION_WOVEN_CONFIG {
        uuid production_order_id PK
        varchar bim
        numeric x_width_mm
        numeric y_length_mm
        numeric ho_percent
        varchar remark
    }
    PRODUCTION_WOVEN_WEAVE_TYPE {
        uuid woven_config_id FK
        varchar weave_type
    }
    PRODUCTION_WOVEN_YARN_LINE {
        uuid id PK
        uuid woven_config_id FK
        integer line_no
        varchar yarn
        varchar yarn_code
        varchar denier
    }
    PRODUCTION_ORDER_PROCESS {
        uuid production_order_id FK
        uuid process_id FK
        integer sequence_no
        numeric speed
    }

    BUYER_ORDER ||--|{ BUYER_ORDER_ITEM : contains
    BUYER_ORDER_ITEM ||--|| PRODUCTION_ORDER : creates
    PRODUCTION_GROUP o|--o{ PRODUCTION_ORDER : groups
    PRODUCTION_ORDER ||--o| PRODUCTION_PRINT_CONFIG : print
    PRODUCTION_ORDER ||--o| PRODUCTION_WOVEN_CONFIG : woven
    PRODUCTION_WOVEN_CONFIG ||--o{ PRODUCTION_WOVEN_WEAVE_TYPE : types
    PRODUCTION_WOVEN_CONFIG ||--o{ PRODUCTION_WOVEN_YARN_LINE : yarns
    PRODUCTION_ORDER ||--o{ PRODUCTION_ORDER_PROCESS : routes
    PROCESS_MASTER ||--o{ PRODUCTION_ORDER_PROCESS : selected
```

## Inventory và Delivery ERD

```mermaid
erDiagram
    STOCK_POSITION {
        uuid id PK
        uuid production_order_id UK
        numeric produced_qty
        numeric delivered_qty
        numeric returned_qty
        numeric disposed_qty
        numeric current_qty
        numeric order_balance_qty
        bigint version
    }
    STOCK_MOVEMENT {
        uuid id PK
        uuid stock_position_id FK
        uuid return_source_item_id FK
        varchar movement_type
        numeric quantity_signed
        numeric balance_after
        date business_date
        varchar idempotency_key UK
        timestamptz occurred_at
    }
    DELIVERY_NOTE {
        uuid id PK
        varchar delivery_no UK
        uuid customer_id FK
        uuid exchange_rate_id FK
        uuid replaces_delivery_id FK
        varchar status
        date delivery_date
        varchar currency_code
        numeric total_qty
        numeric total_amount
        bigint version
    }
    DELIVERY_NOTE_ITEM {
        uuid id PK
        uuid delivery_note_id FK
        integer line_no
        uuid stock_position_id FK
        uuid delivery_movement_id UK
        uuid reversal_movement_id UK
        numeric delivery_qty
        numeric unit_price
        numeric amount
    }
    DELIVERY_EVENT {
        uuid id PK
        uuid delivery_note_id FK
        varchar event_type
        varchar reason
        timestamptz occurred_at
    }

    PRODUCTION_ORDER ||--o| STOCK_POSITION : creates
    STOCK_POSITION ||--|{ STOCK_MOVEMENT : journals
    DELIVERY_NOTE ||--|{ DELIVERY_NOTE_ITEM : contains
    DELIVERY_NOTE o|--o{ DELIVERY_NOTE : replaces
    STOCK_POSITION ||--o{ DELIVERY_NOTE_ITEM : supplies
    STOCK_MOVEMENT ||--o| DELIVERY_NOTE_ITEM : outbound
    STOCK_MOVEMENT o|--o| DELIVERY_NOTE_ITEM : reversal
    DELIVERY_NOTE_ITEM o|--o{ STOCK_MOVEMENT : return_source
    DELIVERY_NOTE ||--o{ DELIVERY_EVENT : histories
```

## Debit projection

```text
DebitProjectionRow
  = DeliveryNote(status = POSTED)
    JOIN DeliveryNoteItem

reference = delivery_no || '/' || line_no padded 2 digits
```

Output tối thiểu:

- reference;
- delivery ID/no/date;
- customer snapshot;
- Buyer PO/item snapshots;
- quantity, UOM, unit price, amount, currency.

Không có Debit table mutable hoặc Debit sequence phase đầu.

## Constraint xuyên bảng cần application transaction

Một số rule không thể diễn đạt bằng simple FK/check:

- Buyer Order PIC ownership dùng composite FK `(customer_contact_id, customer_id)`.
- Production Group cùng Buyer Order dùng composite FK; ít nhất hai members được deferred-validate trước commit.
- Config subtype phải khớp item product kind, cấm tồn tại đồng thời PRINT/WOVEN và đúng subtype bắt buộc trước Finish.
- Delivery items phải cùng Customer/Currency.
- Source chain dùng composite FK: Buyer Order Item ↔ Buyer Order, Production Order ↔ Buyer Order Item và Stock Position ↔ Production Order/Buyer Order Item.
- Delivery quantity không vượt locked physical/order capacity.
- Production Finish chỉ một lần.
- Reversal phải tạo movement chính xác một lần.
- Effective permission precedence.

Các rule này được enforce trong application service + row lock + unique/check constraints hỗ trợ, và integration tests.