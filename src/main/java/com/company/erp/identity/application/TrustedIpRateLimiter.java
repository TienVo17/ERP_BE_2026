package com.company.erp.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.company.erp.config.ErpSecurityProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TrustedIpRateLimiter {

    private final int loginLimit;
    private final int refreshLimit;
    private final Clock clock;
    private final Map<String, Window> loginWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> refreshWindows = new ConcurrentHashMap<>();

    @Autowired
    public TrustedIpRateLimiter(ErpSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    TrustedIpRateLimiter(ErpSecurityProperties properties, Clock clock) {
        this.loginLimit = properties.loginRateLimitPerMinute();
        this.refreshLimit = properties.refreshRateLimitPerMinute();
        this.clock = clock;
    }

    public boolean tryLogin(String clientIp) {
        return acquire(loginWindows, clientIp, loginLimit);
    }

    public boolean tryRefresh(String clientIp) {
        return acquire(refreshWindows, clientIp, refreshLimit);
    }

    private boolean acquire(Map<String, Window> windows, String clientIp, int limit) {
        Instant minute = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Window window = windows.compute(clientIp, (ignored, current) -> {
            if (current == null || !current.minute().equals(minute)) {
                return new Window(minute, 1);
            }
            return new Window(minute, current.count() + 1);
        });
        evictExpired(windows, minute);
        return window.count() <= limit;
    }

    private static void evictExpired(Map<String, Window> windows, Instant currentMinute) {
        if (windows.size() < 1_024) {
            return;
        }
        windows.entrySet().removeIf(entry -> entry.getValue().minute().isBefore(currentMinute));
    }

    private record Window(Instant minute, int count) {
    }
}
