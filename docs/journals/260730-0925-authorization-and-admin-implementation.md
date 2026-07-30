# Authorization and Admin Implementation

---
title: Phase 4 authorization and admin implementation
status: completed
created: 2026-07-30
plan: plans/260728-1326-identity-master-contract-freeze/phase-04-enforce-authorization-and-admin.md
commit: 963f86d
---

## Context

Phase 4 của plan `260728-1326-identity-master-contract-freeze`. Phase 3 đã có JWT, session và
primitive `revokeAllSessions`. Nhiệm vụ: biến quyền thành thứ được thực thi tại API — effective
permission theo DB, Admin users/roles/overrides/reset, login events, IP allowlist, audit và
invariant hai recovery admin. OpenAPI đã freeze nên implementation phải khớp, không sửa contract.

## What happened

TDD: RED (14 test fail vì API/authorization chưa tồn tại) → GREEN → regression. Không nới test nào.

Module mới: `audit` (AuditEventWriter), `identity.api` (3 Admin controller + DTO records),
`identity.application` (UserAdministrationService, RoleAdministrationService,
MonitoringAdministrationService, AdminQuery), `identity.infrastructure` (AdminUser/AdminRole/
Monitoring JDBC repositories).

Điểm cốt lõi: `CurrentPrincipalLoader` giờ gọi **một** query set-based trả về user active, session
sống và effective permissions. Authorities đó chính là thứ `@PreAuthorize` đánh giá — claim
`roles`/`permissions` trong JWT bị bỏ qua hoàn toàn.

## Verification

`.\mvnw.cmd clean package`: 91 tests, 0 failures, 0 errors, jar build.

Ba agent xác minh độc lập (tester, debugger, code-reviewer). Mỗi lỗi được sửa và khoá bằng test:

| Lỗi | Failure path | Sửa |
|---|---|---|
| N+1 khi list user | mỗi user 1 query overrides; page 25 → 25 query thừa | aggregate override JSON trong chính query trang |
| Audit không che `hash` | key `hash` trần và lồng nhau lọt vào `audit_event` | redact đệ quy, thêm unit test trực tiếp |
| Nullable filter không ép kiểu | `GET /admin/roles` và `/admin/permissions` không truyền filter → PostgreSQL không suy được kiểu `NULL` trong `:param IS NULL` → `500` | `CAST(:param AS ...)` cho mọi optional filter + test list không filter |
| `inet` sai định dạng | network không hợp lệ → `500` thay vì `400` | map `DataIntegrityViolationException` → `VALIDATION_FAILED` |
| `page * size` tràn int | page lớn → offset âm | `Math.multiplyExact((long) page, size)` |

Lỗi nullable bind là loại chỉ lộ ra khi *không* truyền tham số — test ban đầu của tôi luôn truyền
filter nên đã bỏ sót. Reviewer chỉ ra, tôi thêm test cho nhánh không filter trước khi sửa.

## Decisions

- **Recovery quorum dùng advisory lock theo transaction.** Đếm sau khi áp dụng state đề xuất, dưới 2
  thì `RECOVERY_ADMIN_REQUIRED` và rollback. Khoá trước khi ghi, không phải sau, để hai lệnh
  destructive song song không cùng thấy "vẫn còn 2".
- **`reason` giữ nguyên optional.** Plan viết "always audit actor/reason", nhưng OpenAPI đã freeze
  khai báo optional. Đổi thành bắt buộc là phá contract; reason có gửi thì được ghi vào audit.
- **`CONCURRENT_MODIFICATION` trong plan → `VERSION_CONFLICT`.** Contract đã đóng băng thắng tên cũ
  trong plan.
- **Allowlist chỉ có DELETE thật với `If-Match`**, không archive như bản nháp plan mô tả.
- **Network canonical qua `inet` của PostgreSQL**: `192.0.2.42/24` lưu thành `192.0.2.0/24`.

## Deferred

- Query-count instrumentation: cần chọn cơ chế đếm query ổn định trước khi assert được con số.
- Test ma trận sâu hơn: concurrency quorum, split-permission trên mọi route Admin, token cũ phản ánh
  thay đổi quyền ngay lập tức. Invariant đã implement và đã có test đại diện; đây là hardening.

## Next

- Phase 5: reference và party masters.
- Trước go-live: vẫn cần cấu hình `erp.security.trusted-proxy-addresses`, và cần admin recovery thứ
  hai đã đổi mật khẩu + đăng nhập xác minh.

## Unresolved questions

- Có nên thêm coverage plugin (JaCoCo) để có số liệu thay vì suy đoán? Hiện repo không có.
