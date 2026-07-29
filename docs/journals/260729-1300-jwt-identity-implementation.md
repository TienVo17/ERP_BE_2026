# JWT Identity Implementation

---
title: Phase 3 JWT identity implementation
status: completed
created: 2026-07-29
plan: plans/260728-1326-identity-master-contract-freeze/phase-03-implement-jwt-identity.md
commit: 0b825d5
---

## Context

Phase 3 của plan `260728-1326-identity-master-contract-freeze`. Nền tảng đã có: V001–V011,
test foundation với PostgreSQL Testcontainers, OpenAPI v1 đã freeze. Nhiệm vụ: thay mock
identity bằng authentication thật — JWT, refresh rotation, forced password change, bootstrap
admin. Contract đã đóng băng nên implementation phải khớp, không được sửa OpenAPI.

## What happened

TDD theo đúng thứ tự: RED (44 compile errors vì production API chưa tồn tại) → GREEN →
regression. Không nới test nào để build xanh.

Module `identity` mới: `api` (AuthController + DTO records), `application`
(AuthenticationService, RefreshTokenService, PasswordService, TrustedIpRateLimiter,
BootstrapAdminRunner, SessionSort), `domain` (AppUser, AuthSession), `infrastructure`
(IdentityJdbcRepository, AuthSessionJdbcRepository), `security` (JwtTokenService,
CurrentPrincipalLoader, SecurityConfiguration, TrustedClientIpResolver, JwtKeyConfiguration).

Xóa `config/ErpSecurityConfiguration` và `config/ErpAccessDeniedHandler`: sau khi security
chain của identity thay thế, cả hai thành inert. Để lại thì repo có hai filter chain trông
giống nhau, một cái im lặng không chạy.

## Verification

`.\mvnw.cmd clean package`: 75 tests, 0 failures, 0 errors. Xác nhận lại sau commit.

Ba agent xác minh độc lập (tester, debugger, code-reviewer) tìm được defect thật. Mỗi lỗi
được tái hiện bằng RED test trước khi sửa:

| Lỗi | Failure path | Sửa |
|---|---|---|
| Logout bypass CSRF | Bearer + refresh cookie, không CSRF → `204` + revoke family | Bắt buộc CSRF khi request mang cookie |
| Challenge sống sót logout | Challenge + cookie cũ → logout chỉ xử lý cookie, challenge vẫn đổi được mật khẩu | Revoke cả family lẫn challenge |
| XFF spoofing | Proxy *append* vào header client gửi → phần tử trái nhất do attacker kiểm soát → bypass 10 login/phút | Đi phải-sang-trái, bỏ qua hop tin cậy |
| DNS trên input attacker | `X-Forwarded-For: host.evil.com` → DNS lookup trên servlet thread, NXDOMAIN → 500 thoát ProblemDetail | Chỉ chấp nhận IP literal |
| N+1 revoke | Đổi mật khẩu duyệt mọi session lịch sử: 2 query/row | 2 câu lệnh set-based lọc `revoked_at IS NULL` |
| Session list trả session chết | Response schema không có field lifecycle → user không biết revoke cái nào | Lọc live-only; revoke session đã kết thúc → `404` |
| CSRF matcher | `getRequestURI()` gồm context path + hardcode cookie name → CSRF im lặng tắt | `pathPattern` + đọc config |
| JWT thiếu `kid` | Token không `kid` vẫn verify được bằng key đã cấu hình | Bắt buộc `kid` |

Trong lúc sửa, fallback ProblemDetail handler mới nuốt luôn `NoHandlerFoundException`
(404 → 500). Full suite bắt được ngay; đã loại trừ `ErrorResponse` khỏi handler đó.

Một session Claude khác gửi report kết luận "Phase 3 chưa implement" — report đọc worktree
nền cũ nên không thấy thay đổi chưa commit. Không áp dụng cho checkout chính.

## Decisions

- **Logout CSRF theo refresh cookie.** Spring resource server coi request có Bearer là
  CSRF-exempt; nếu dựa vào đó thì mixed-credential logout revoke được family mà không cần
  token. Guard đặt tại boundary controller cho trường hợp mixed.
- **Mixed challenge + cookie cũ revoke cả hai.** Challenge logout phải terminal; không được
  để challenge sống sót chính lệnh kết thúc nó.
- **Normal access bearer không có refresh cookie thì không logout được** — trả
  `UNAUTHENTICATED` thay vì `204` không revoke gì.
- **CSRF state xóa sau login/logout**, đúng contract SPA phải reacquire.
- **Bootstrap one-shot toàn cục**, không chỉ theo `loginId`: đổi login ID vẫn bị từ chối.
- **Test dùng IP riêng cho mỗi method** thay vì nới rate limit. Giới hạn production 10/phút
  là hành vi đúng; test không được làm yếu nó.

## Deferred

- Admin reset/disable endpoints → Phase 4 (`phase-04` sở hữu scope này). Phase 3 chỉ cung cấp
  primitive `revokeAllSessions`.
- Audit rows cho change-password/session-revoke → Phase 4, nơi có `AuditEventWriter`. Phase 3
  chỉ ghi `identity.login_event`.
- Thời hạn giữ previous public key: code verify được key cũ qua `public-key-locations`, nhưng
  *duration* là nghĩa vụ deployment — process không ngăn được secret store xóa file.

## Next

- Phase 4: effective permission matrix, admin APIs, recovery-admin invariant, audit writer.
- Trước go-live: cấu hình `erp.security.trusted-proxy-addresses` khớp reverse proxy thật,
  nếu không limiter sẽ key theo IP của proxy.
- Rate limiter còn in-memory: multi-instance vẫn bị chặn cho tới khi chuyển sang
  gateway/Redis.

## Unresolved questions

- Deployment có dùng `server.servlet.context-path` khác root không? Đã sửa matcher để an toàn
  cả hai, nhưng cấu hình thật nên được xác nhận.
- Reverse proxy sẽ *replace* hay *append* `X-Forwarded-For`? Implementation hiện an toàn với
  cả hai; tài liệu vận hành nên chốt.
