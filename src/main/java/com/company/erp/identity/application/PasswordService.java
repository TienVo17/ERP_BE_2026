package com.company.erp.identity.application;

import java.util.Map;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {

    private static final String DEFAULT_ID = "argon2";

    private final PasswordEncoder encoder;

    public PasswordService() {
        Argon2PasswordEncoder argon2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        this.encoder = new DelegatingPasswordEncoder(DEFAULT_ID, Map.of(DEFAULT_ID, argon2));
    }

    public String encode(CharSequence rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return encodedPassword != null && encoder.matches(rawPassword, encodedPassword);
    }

    public boolean needsUpgrade(String encodedPassword) {
        return encodedPassword == null || encoder.upgradeEncoding(encodedPassword);
    }
}
