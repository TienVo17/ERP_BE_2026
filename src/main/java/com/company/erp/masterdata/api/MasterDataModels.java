package com.company.erp.masterdata.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class MasterDataModels {

    private MasterDataModels() {
    }

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public record PageResponse<T>(List<T> items, PageMetadata page, Map<String, Object> filters, List<String> sort) {
    }

    public record CurrencyResponse(String code, String name, boolean active) {
    }

    public record UomRequest(@NotBlank String code, String name) {
    }

    public record UomResponse(
            UUID id, long version, String status, String code, String name,
            Instant createdAt, Instant updatedAt) {
    }

    public record ExchangeRateRequest(
            @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String month,
            @NotBlank @Pattern(regexp = "^(?=.*[1-9])\\d{1,12}(\\.\\d{1,6})?$") String vndUsdRate,
            @NotBlank @Pattern(regexp = "^(?=.*[1-9])\\d{1,12}(\\.\\d{1,6})?$") String wonUsdRate,
            String source) {
    }

    public record ExchangeRateResponse(
            UUID id, long version, String status, String month, String vndUsdRate,
            String wonUsdRate, String source, Instant createdAt, Instant updatedAt) {
    }

    public record ContactCreateRequest(
            String division, @NotBlank String name, String telephone, @Email String email,
            String remark, Boolean isDefault) {
    }

    public record ContactUpdateRequest(
            String division, @NotBlank String name, String telephone, @Email String email,
            String remark, Boolean isDefault) {
    }

    public record ContactResponse(
            UUID id, long version, String status, String division, String name,
            String telephone, String email, String remark, boolean isDefault,
            Instant createdAt, Instant updatedAt) {
    }

    public record CustomerCreateRequest(
            @NotBlank String shortName, @NotBlank String name, String address, String telephone,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {
    }

    public record CustomerUpdateRequest(
            @NotBlank String name, String address, String telephone,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {
    }

    public record CustomerResponse(
            UUID id, long version, String status, String shortName, String name,
            String address, String telephone, String currencyCode, List<ContactResponse> contacts,
            Instant createdAt, Instant updatedAt) {
    }

    public record SupplierRequest(@NotBlank String name, String address, String telephone) {
    }

    public record SupplierResponse(
            UUID id, long version, String status, String name, String address, String telephone,
            List<ContactResponse> contacts, Instant createdAt, Instant updatedAt) {
    }

    public record ProcessCreateRequest(
            @NotBlank String name, @Min(1) int sequenceNo, @NotBlank String qrValue) {
    }

    public record ProcessUpdateRequest(@NotBlank String name, @Min(1) int sequenceNo) {
    }

    public record ProcessResponse(
            UUID id, long version, String status, String name, int sequenceNo, String qrValue,
            Instant createdAt, Instant updatedAt) {
    }
}
