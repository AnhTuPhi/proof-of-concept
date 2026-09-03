package com.example.webapipoc.jwt;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Hard-coded demo users. Production: replace with a UserRepository.
 *
 *   alice / password
 *   bob   / hunter2
 */
@Component
public class UserStore {

    private final Map<String, String> users = Map.of(
        "alice", "password",
        "bob", "hunter2"
    );

    public Optional<String> authenticate(String username, String password) {
        String stored = users.get(username);
        if (stored == null || !stored.equals(password)) return Optional.empty();
        return Optional.of(username);
    }
}
