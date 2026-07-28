# Spring Boot Skeleton Setup

---
title: Spring Boot skeleton setup
status: completed
created: 2026-07-28
---

## Context

BE-ERP đã có PostgreSQL/Flyway artifacts nhưng chưa có Java application buildable. Mục tiêu: tạo skeleton tối thiểu, chưa có nghiệp vụ, giữ nguyên database contracts.

## What happened

- Tạo Maven project Java 21, Spring Boot 4.1.0.
- Generate official Maven Wrapper 3.3.4, chạy Maven 3.9.9, `only-script`.
- Tạo `ErpApplication` và module markers: `identity`, `masterdata`, `sales`, `production`, `inventory`, `delivery`.
- Tạo `application.yml` chỉ có application name.
- Tạo context smoke test.
- Không thêm API, controller, service, entity, repository, JPA/JDBC, Flyway runtime hay security implementation.
- Flyway V001–V010 và verification SQL giữ nguyên exact SHA-256.

## Verification

- `./mvnw test`: pass, 1 test, 0 failures.
- `./mvnw package`: pass.
- Executable JAR chứa `ErpApplication.class` và đủ V001–V010.
- Wrapper Windows và shell đều chạy Maven 3.9.9.
- Review xác nhận không có business/API/persistence scope drift.

`tester` và `code-reviewer` Agent không spawn được vì repository chưa có initial Git `HEAD`. Dùng direct Maven verification và `ck-code-review` fallback. Wrapper được regenerate bằng Maven Wrapper official sau review.

## Decisions

- Package-by-feature, không tạo layer/package rỗng sâu khi chưa có code thật.
- Debit vẫn thuộc Delivery projection, không tạo module riêng.
- Flyway SQL chỉ được đóng gói như resource; chưa auto-run vì skeleton không có DB dependencies.
- Database integration và runtime role provisioning là phase riêng.

## Next

- Khi bắt đầu nghiệp vụ: thêm dependency theo use case thật, không theo template.
- Khi tích hợp DB: thêm Flyway/PostgreSQL/Testcontainers và cấu hình secret ngoài source.
- Tạo initial Git commit trước workflow cần isolated agent/worktree.

## Unresolved questions

Không có.
