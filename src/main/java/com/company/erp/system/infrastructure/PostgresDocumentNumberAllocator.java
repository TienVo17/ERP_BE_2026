package com.company.erp.system.infrastructure;

import com.company.erp.system.application.DocumentNumberAllocator;
import com.company.erp.system.application.DocumentType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresDocumentNumberAllocator implements DocumentNumberAllocator {

    private final JdbcClient jdbc;

    public PostgresDocumentNumberAllocator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String next(DocumentType type, int year) {
        if (year < 2000 || year > 9999) {
            throw new IllegalArgumentException("Document year must be between 2000 and 9999");
        }
        return jdbc.sql("SELECT system.next_document_number(:type, :prefix, CAST(:year AS smallint))")
                .param("type", type.databaseValue())
                .param("prefix", type.prefix())
                .param("year", year)
                .query(String.class)
                .single();
    }
}
