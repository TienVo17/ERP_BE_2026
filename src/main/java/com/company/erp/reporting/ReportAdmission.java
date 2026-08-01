package com.company.erp.reporting;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ReportAdmission {

    private final Semaphore permits = new Semaphore(2, true);

    public <T> T generate(Supplier<T> report) {
        if (!permits.tryAcquire()) {
            throw new ApiException(ApiErrorCode.REPORT_BUSY,
                    "Both report generation slots are occupied. Retry after five seconds.", 5);
        }
        try {
            return report.get();
        } finally {
            permits.release();
        }
    }
}
