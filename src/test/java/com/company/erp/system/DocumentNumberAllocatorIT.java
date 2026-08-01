package com.company.erp.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.erp.support.PostgresTestConfiguration;
import com.company.erp.system.application.DocumentNumberAllocator;
import com.company.erp.system.application.DocumentType;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class DocumentNumberAllocatorIT {

    @Autowired
    private DocumentNumberAllocator allocator;

    @Test
    void allocatesUniqueNumbersAtomicallyWithinTheRequestedTypeAndYear() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> calls = java.util.stream.IntStream.range(0, 40)
                    .mapToObj(ignored -> (Callable<String>) () -> allocator.next(DocumentType.PRODUCTION, 2032))
                    .toList();

            List<String> numbers = executor.invokeAll(calls).stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(numbers).hasSize(40).doesNotHaveDuplicates();
            assertThat(numbers).allMatch(number -> number.matches("PR-2032-[0-9]{6}"));
        }
    }

    @Test
    void keepsDocumentTypeAndExplicitYearIndependent() {
        assertThat(allocator.next(DocumentType.SALES_ORDER, 2033)).startsWith("SO-2033-");
        assertThat(allocator.next(DocumentType.PRODUCTION, 2034)).startsWith("PR-2034-");
        assertThat(allocator.next(DocumentType.DELIVERY, 2035)).startsWith("DN-2035-");
    }
}
