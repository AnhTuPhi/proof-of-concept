package com.example.webapipoc.config;

import com.example.webapipoc.jwt.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * POC security config:
 * - Stateless (JWT-based)
 * - CSRF disabled for /api/**, /auth/**, /oauth/** (token-protected APIs)
 * - Static resources + demo HTML are public
 * - /api/products/** requires authentication (demo ETag with secured endpoint)
 * - /api/users/** is open (demo content negotiation without auth noise)
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/index.html",
                    "/pkce-demo.html", "/jwt-demo.html", "/etag-demo.html", "/content-negotiation-demo.html",
                    "/js/**", "/css/**", "/favicon.ico"
                ).permitAll()
                .requestMatchers("/auth/**", "/oauth/**").permitAll()
                .requestMatchers("/api/users/**").permitAll()
                .requestMatchers("/api/products/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
