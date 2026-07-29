package com.company.erp.identity.domain;

import java.util.UUID;

public record AppUser(
        UUID id,
        String kind,
        String loginId,
        String passwordHash,
        String name,
        String status,
        boolean mustChangePassword,
        long passwordGeneration) {

    public boolean isActive() {
        return "ACTIVE".equals(status) && !"SYSTEM".equals(kind);
    }
}
