package com.company.erp.system.application;

public interface DocumentNumberAllocator {

    String next(DocumentType type, int year);
}
