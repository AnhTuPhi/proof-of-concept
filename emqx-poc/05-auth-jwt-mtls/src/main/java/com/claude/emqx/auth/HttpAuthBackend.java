package com.claude.emqx.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP authn/authz backend that EMQX calls on every CONNECT (and optionally
 * every PUBLISH/SUBSCRIBE for ACL).
 *
 * <p>EMQX configuration (in emqx-overrides.conf) for HTTP authn:
 * <pre>
 *   authentication = [
 *     { mechanism = "password_based"
 *       backend = "http"
 *       method = "post"
 *       url = "http://host.docker.internal:8105/auth/mqtt"
 *       body { username = "${username}", password = "${password}", clientid = "${clientid}", peerhost = "${peerhost}" }
 *       headers { "Content-Type" = "application/json" }
 *     }
 *   ]
 * </pre>
 *
 * <p>Response contract:
 * <pre>
 *   200 + { "result": "allow", "is_superuser": false, "client_attrs": {...} }   -> CONNECT accepted
 *   200 + { "result": "deny" }                                                  -> CONNECT denied
 *   any other status                                                            -> ignored (next auth)
 * </pre>
 */
@RestController
@RequestMapping("/auth")
public class HttpAuthBackend {

    private final JdbcTemplate jdbc;

    public HttpAuthBackend(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping("/mqtt")
    public ResponseEntity<Map<String, Object>> authenticate(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        if (username == null || password == null) return ok(Map.of("result", "deny"));

        // Look up in same Postgres table EMQX could query directly. We could
        // skip this and let EMQX hit Postgres - but this endpoint exists to
        // show the HTTP backend pattern (e.g. for orgs whose user store is
        // behind a custom API).
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT password_hash, salt, is_superuser, tenant_id FROM mqtt_user WHERE username = ?",
                    username);
            String storedHash = (String) row.get("password_hash");
            String salt = (String) row.get("salt");
            Boolean isSuper = (Boolean) row.get("is_superuser");
            String tenantId = (String) row.get("tenant_id");

            String givenHash = sha256Hex(password + salt);
            if (!givenHash.equalsIgnoreCase(storedHash)) {
                return ok(Map.of("result", "deny"));
            }

            // client_attrs lets you propagate fields to ACL templates server-side.
            // The tenant_id flows into the ACL chain so per-tenant topic prefixes work.
            return ok(Map.of(
                    "result", "allow",
                    "is_superuser", isSuper != null && isSuper,
                    "client_attrs", Map.of("tenant_id", tenantId == null ? "" : tenantId)
            ));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ok(Map.of("result", "deny"));
        }
    }

    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok(body); }

    private static String sha256Hex(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
