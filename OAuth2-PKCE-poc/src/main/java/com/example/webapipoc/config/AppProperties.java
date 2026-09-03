package com.example.webapipoc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private OAuth oauth = new OAuth();

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }

    public OAuth getOauth() { return oauth; }
    public void setOauth(OAuth oauth) { this.oauth = oauth; }

    public static class Jwt {
        private String secret;
        private String issuer;
        private long accessTtlSeconds;
        private long refreshTtlSeconds;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public long getAccessTtlSeconds() { return accessTtlSeconds; }
        public void setAccessTtlSeconds(long accessTtlSeconds) { this.accessTtlSeconds = accessTtlSeconds; }
        public long getRefreshTtlSeconds() { return refreshTtlSeconds; }
        public void setRefreshTtlSeconds(long refreshTtlSeconds) { this.refreshTtlSeconds = refreshTtlSeconds; }
    }

    public static class OAuth {
        private String clientId;
        private String redirectUri;
        private long codeTtlSeconds;

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
        public long getCodeTtlSeconds() { return codeTtlSeconds; }
        public void setCodeTtlSeconds(long codeTtlSeconds) { this.codeTtlSeconds = codeTtlSeconds; }
    }
}
