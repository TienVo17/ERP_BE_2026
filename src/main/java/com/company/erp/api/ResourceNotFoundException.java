package com.company.erp.api;

public final class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceType, String identifier) {
        super("Requested resource was not found");
    }
}
