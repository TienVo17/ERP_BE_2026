package com.company.erp.identity.security;

import com.company.erp.config.ErpSecurityProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class JwtKeyConfiguration {

    @Bean
    @ConditionalOnMissingBean(RSAKey.class)
    RSAKey rsaSigningKey(ErpSecurityProperties properties) {
        String location = properties.keyRing().privateKeyLocation();
        if (location == null) {
            throw new IllegalStateException("ERP_PRIVATE_KEY_LOCATION must reference an external RSA signing key");
        }
        JWK parsed = JwtKeyFiles.load(location);
        if (!(parsed instanceof RSAKey rsaKey)
                || !rsaKey.isPrivate()
                || rsaKey.size() < properties.keyRing().rsaBits()
                || !properties.keyRing().activeKid().equals(rsaKey.getKeyID())
                || !JWSAlgorithm.RS256.equals(rsaKey.getAlgorithm())) {
            throw new IllegalStateException(
                    "Configured signing key must be the active RSA-3072 RS256 private JWK");
        }
        return rsaKey;
    }
}
