package com.company.erp.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthRequests {

    private AuthRequests() {
    }

    public record LoginRequest(
            @NotBlank String loginId,
            @NotBlank String password) {

        @Override
        public String toString() {
            return "LoginRequest[loginId=" + loginId + ", password=[REDACTED]]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 12) String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[currentPassword=[REDACTED], newPassword=[REDACTED]]";
        }
    }
}
