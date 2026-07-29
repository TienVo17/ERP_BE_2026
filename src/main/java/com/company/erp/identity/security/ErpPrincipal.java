package com.company.erp.identity.security;

import java.util.UUID;

import com.company.erp.identity.domain.AppUser;

public record ErpPrincipal(AppUser user, UUID sessionId, String purpose) {
}
