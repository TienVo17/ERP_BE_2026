package com.company.erp.identity.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.company.erp.config.ErpSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

/**
 * Resolves the client address the in-memory rate limiter keys on.
 *
 * <p>A forwarded address is only believed when the immediate peer is a configured proxy, and the
 * chain is then walked from right to left. Proxies such as nginx append the peer to whatever
 * {@code X-Forwarded-For} the client sent, so the leftmost element is attacker-controlled and the
 * rightmost untrusted hop is the real client. Only IP literals are accepted; hostnames are rejected
 * rather than resolved, so an attacker cannot make the request thread perform a DNS lookup.
 */
@Component
public class TrustedClientIpResolver {

    private static final String LOOPBACK = "127.0.0.1";

    private final Set<String> trustedProxyAddresses;

    public TrustedClientIpResolver(ErpSecurityProperties properties) {
        this.trustedProxyAddresses = properties.trustedProxyAddresses().stream()
                .map(TrustedClientIpResolver::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String peer = normalize(request.getRemoteAddr());
        if (!trustedProxyAddresses.contains(peer)) {
            return peer;
        }
        List<String> forwarded = forwardedChain(request);
        for (int index = forwarded.size() - 1; index >= 0; index--) {
            String hop = normalize(forwarded.get(index));
            if (hop != null && !trustedProxyAddresses.contains(hop)) {
                return hop;
            }
        }
        return peer;
    }

    private static List<String> forwardedChain(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return List.of(header.split(","));
    }

    /**
     * Returns the canonical form of an IP literal, or {@code null} when the value is not a literal.
     * The loopback address is substituted only for a missing peer address.
     */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return LOOPBACK;
        }
        String candidate = value.trim();
        if (!isIpLiteral(candidate)) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private static boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            // Only IPv6 literals contain a colon; getByName never resolves these through DNS.
            return true;
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int index = 0; index < octet.length(); index++) {
                if (!Character.isDigit(octet.charAt(index))) {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}
