package com.company.erp.system.application;

public enum DocumentType {
    SALES_ORDER("SALES_ORDER", "SO"),
    PRODUCTION("PRODUCTION", "PR"),
    DELIVERY("DELIVERY", "DN");

    private final String databaseValue;
    private final String prefix;

    DocumentType(String databaseValue, String prefix) {
        this.databaseValue = databaseValue;
        this.prefix = prefix;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public String prefix() {
        return prefix;
    }
}
