package com.example.webapipoc.versioning;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Content negotiation demo — same URL, different shapes per requested version.
 *
 * Three styles, all responding to GET /api/users/{id}:
 *
 *   A) Custom vendor media type (most "correct" RESTful style):
 *        Accept: application/vnd.webapipoc.v1+json
 *        Accept: application/vnd.webapipoc.v2+json
 *
 *   B) Version parameter on media type:
 *        Accept: application/json;version=1
 *        Accept: application/json;version=2
 *
 *   C) Custom header (pragmatic, easy for SDKs):
 *        Api-Version: 1
 *        Api-Version: 2
 *
 *   Fallback: latest version when no hint.
 *
 * v1 returns {id, name, email}.
 * v2 splits name into firstName/lastName, adds phone & joinedOn, drops email.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Map<Long, RawUser> USERS = Map.of(
        1L, new RawUser(1L, "Andre", "Nguyen", "andre@example.com",
            "+84-901-000-001", LocalDate.of(2022, 3, 1)),
        2L, new RawUser(2L, "Linh", "Tran", "linh@example.com",
            "+84-901-000-002", LocalDate.of(2023, 7, 15))
    );

    // --- Style A: vendor media types -----------------------------------------------------------

    @GetMapping(value = "/{id}", produces = "application/vnd.webapipoc.v1+json")
    public ResponseEntity<UserV1> getV1Vendor(@PathVariable Long id) {
        return resolve(id).map(u -> ResponseEntity.ok()
            .header("X-Resolved-Version", "1")
            .header("X-Negotiation", "vendor-media-type")
            .body(toV1(u))).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{id}", produces = "application/vnd.webapipoc.v2+json")
    public ResponseEntity<UserV2> getV2Vendor(@PathVariable Long id) {
        return resolve(id).map(u -> ResponseEntity.ok()
            .header("X-Resolved-Version", "2")
            .header("X-Negotiation", "vendor-media-type")
            .body(toV2(u))).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // --- Styles B & C: same plain Accept, dispatch by query of headers -------------------------

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getJson(
        @PathVariable Long id,
        @RequestHeader(value = "Accept", required = false) String accept,
        @RequestHeader(value = "Api-Version", required = false) String apiVersionHeader
    ) {
        Optional<RawUser> maybe = resolve(id);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();

        int version = resolveVersion(accept, apiVersionHeader);
        String negotiation = apiVersionHeader != null ? "header:Api-Version"
            : (accept != null && accept.contains("version=")) ? "accept-param:version"
            : "default-latest";

        return switch (version) {
            case 1 -> ResponseEntity.ok()
                .header("X-Resolved-Version", "1")
                .header("X-Negotiation", negotiation)
                .body(toV1(maybe.get()));
            default -> ResponseEntity.ok()
                .header("X-Resolved-Version", "2")
                .header("X-Negotiation", negotiation)
                .body(toV2(maybe.get()));
        };
    }

    private int resolveVersion(String accept, String apiVersionHeader) {
        if (apiVersionHeader != null) {
            try { return Integer.parseInt(apiVersionHeader.trim()); } catch (NumberFormatException ignored) {}
        }
        if (accept != null) {
            // Look for `version=N` parameter in any Accept entry
            for (String part : accept.split(",")) {
                for (String p : part.split(";")) {
                    String t = p.trim();
                    if (t.startsWith("version=")) {
                        try { return Integer.parseInt(t.substring("version=".length()).trim()); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return 2; // default to latest
    }

    private Optional<RawUser> resolve(Long id) { return Optional.ofNullable(USERS.get(id)); }

    private UserV1 toV1(RawUser u) {
        return new UserV1(u.id(), u.firstName() + " " + u.lastName(), u.email());
    }

    private UserV2 toV2(RawUser u) {
        return new UserV2(u.id(), u.firstName(), u.lastName(), u.phone(), u.joinedOn());
    }

    private record RawUser(Long id, String firstName, String lastName, String email,
                           String phone, LocalDate joinedOn) {}

    public record UserV1(Long id, String name, String email) {}
    public record UserV2(Long id, String firstName, String lastName, String phone, LocalDate joinedOn) {}
}
