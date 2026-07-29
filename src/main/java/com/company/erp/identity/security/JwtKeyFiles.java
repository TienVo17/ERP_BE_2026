package com.company.erp.identity.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;

import com.nimbusds.jose.jwk.JWK;

final class JwtKeyFiles {

    private JwtKeyFiles() {
    }

    static JWK load(String location) {
        Path path = Path.of(location).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Configured JWT key file is unavailable");
        }
        try {
            return JWK.parse(Files.readString(path, StandardCharsets.UTF_8).trim());
        } catch (IOException | ParseException exception) {
            throw new IllegalStateException("Configured JWT JWK file cannot be loaded", exception);
        }
    }
}
