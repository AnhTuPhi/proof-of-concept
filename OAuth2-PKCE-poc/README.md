# Web / API Patterns — POC

A Spring Boot 3 + Java 21 demo of **four web/API patterns every backend dev should know in production**:

| # | Pattern | What it solves |
|---|---|---|
| 1 | **ETag + If-None-Match (HTTP 304)** | Save bandwidth + roundtrip cost on unchanged resources; also enables If-Match optimistic concurrency. |
| 2 | **OAuth2 Authorization Code + PKCE** | Safe login for public clients (SPA, mobile) — no client secret needed; stolen auth codes are useless without the verifier. |
| 3 | **JWT refresh rotation + reuse detection** | Short access TTL + rotating refresh; if the refresh token is replayed, the whole "family" is revoked — kills stolen sessions. |
| 4 | **Content negotiation** | One URL, multiple schemas. Vendor media types, version params, custom headers — three pragmatic styles. |

## Run

```bash
mvn spring-boot:run
# then open http://localhost:8080
```

Demo accounts (in `UserStore.java`):

| user | password |
|------|----------|
| alice | password |
| bob   | hunter2  |

## Source map

```
src/main/java/com/example/webapipoc/
├── WebApiPocApplication.java
├── config/
│   ├── AppProperties.java        — typed config from application.yml
│   └── SecurityConfig.java       — stateless filter chain, JWT filter wiring
├── etag/                          ← Pattern 1
│   ├── Product.java
│   ├── ProductStore.java         — in-memory store + seed
│   └── ProductController.java    — ETag + If-None-Match + If-Match
├── pkce/                          ← Pattern 2
│   └── PkceController.java       — /oauth/authorize + /oauth/token + S256 check
├── jwt/                           ← Pattern 3 (and the auth backbone for 2)
│   ├── JwtService.java           — sign/parse access & refresh tokens
│   ├── RefreshTokenStore.java    — rotation + reuse detection (family revocation)
│   ├── UserStore.java
│   ├── JwtAuthFilter.java
│   └── AuthController.java       — /auth/login + /auth/refresh + /auth/logout + /auth/debug
└── versioning/                    ← Pattern 4
    └── UserController.java       — vendor media type / version param / Api-Version header

src/main/resources/static/         — HTML/JS demo pages
├── index.html
├── etag-demo.html
├── pkce-demo.html
├── jwt-demo.html
└── content-negotiation-demo.html
```

---

## Pattern 1 — ETag + If-None-Match

### The flow

1. Client `GET /api/products/1000` → server returns `200 OK` + `ETag: "abc123"`.
2. Client caches the body keyed by the ETag.
3. Next time it asks for the same resource it adds `If-None-Match: "abc123"`.
4. If the resource is unchanged, server returns `304 Not Modified` **with no body** — saves all the JSON serialization + network bytes.

### curl

```bash
# Step 1 — login to get an access token
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password"}' | jq -r .accessToken)

# Step 2 — initial GET, capture the ETag
curl -i http://localhost:8080/api/products/1000 \
  -H "Authorization: Bearer $TOKEN"
# → 200 OK
#   ETag: "abc123…"
#   { "id":1000, ... }

# Step 3 — replay with If-None-Match → 304 Not Modified, empty body
curl -i http://localhost:8080/api/products/1000 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'If-None-Match: "abc123…"'
# → HTTP/1.1 304
#   (no body)

# Step 4 — If-Match optimistic update
curl -i -X PUT http://localhost:8080/api/products/1000 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "abc123…"' \
  -d '{"price": 73000}'
# → 200 if matched, 412 Precondition Failed if a concurrent update bumped the ETag
```

### Notes

- We compute the ETag from `SHA-256(JSON body)` and truncate to 16 hex chars (plenty of entropy, keeps headers small).
- This is a **strong** ETag (changes on any byte change). Spring also ships `ShallowEtagHeaderFilter` which generates these generically — we hand-roll so the demo is explicit and so the `If-Match` precondition flow is visible.
- For list endpoints we compute the ETag from `(id, version)` tuples rather than the whole body — much cheaper, just as correct.

---

## Pattern 2 — OAuth2 Authorization Code + PKCE

### Why PKCE

Classical Authorization Code requires a `client_secret` at the token endpoint. SPAs and mobile apps **cannot hold a secret** safely. PKCE replaces the secret with a per-flow random string (`code_verifier`) that the client keeps in memory and only reveals at the token-exchange step.

| Channel | What travels | If intercepted |
|---|---|---|
| Front-channel redirect | `code_challenge` = SHA-256 of verifier | useless without the verifier (one-way hash) |
| Back-channel `POST /oauth/token` | `code` + `code_verifier` | already-consumed code is one-shot |

### The flow (S256)

```
client                                       authorization-server                 token-endpoint
  │                                                  │                                  │
  │ generate verifier (random 43-128 chars)          │                                  │
  │ challenge = base64url(sha256(verifier))          │                                  │
  │                                                  │                                  │
  │ GET /oauth/authorize?response_type=code         │                                  │
  │   &client_id=demo-spa&redirect_uri=...          │                                  │
  │   &code_challenge=...&code_challenge_method=S256 │                                  │
  │   &state=...                                     │                                  │
  │─────────────────────────────────────────────────>│                                  │
  │                                                  │ store {code → challenge,method}  │
  │            302 → redirect_uri?code=...&state=... │                                  │
  │<─────────────────────────────────────────────────│                                  │
  │                                                  │                                  │
  │ POST /oauth/token  grant_type=authorization_code &code=...&code_verifier=...        │
  │────────────────────────────────────────────────────────────────────────────────────>│
  │                                                  │ assert sha256(verifier)==challenge│
  │                                  200 { access_token, refresh_token, family_id, … }  │
  │<────────────────────────────────────────────────────────────────────────────────────│
```

### Try it in the browser

Visit **<http://localhost:8080/pkce-demo.html>** — click `Start PKCE flow`. The page:
- Generates a `code_verifier` in your browser (`crypto.getRandomValues`).
- Computes `code_challenge` via `crypto.subtle.digest('SHA-256', …)`.
- Redirects you through `/oauth/authorize` and back.
- Exchanges the code + verifier at `/oauth/token`.

### curl

The HTML demo is the cleanest way to see this end-to-end, but you can also run it manually:

```bash
# 1. Generate a verifier (43-128 char URL-safe random string)
VERIFIER=$(openssl rand -base64 48 | tr '/+' '_-' | tr -d '=')

# 2. Derive the challenge (base64url-encoded sha256)
CHALLENGE=$(printf %s "$VERIFIER" | openssl dgst -sha256 -binary | openssl base64 | tr '/+' '_-' | tr -d '=')

# 3. Hit /oauth/authorize — follow the redirect to capture ?code=…
curl -i "http://localhost:8080/oauth/authorize?response_type=code&client_id=demo-spa&redirect_uri=http://localhost:8080/pkce-demo.html&state=xyz&code_challenge=$CHALLENGE&code_challenge_method=S256&subject=alice"
# → 302 Location: http://localhost:8080/pkce-demo.html?code=<CODE>&state=xyz

# 4. Exchange code + verifier for tokens
curl -s -X POST http://localhost:8080/oauth/token \
  -d grant_type=authorization_code \
  -d code=<CODE> \
  -d code_verifier=$VERIFIER \
  -d client_id=demo-spa \
  -d redirect_uri=http://localhost:8080/pkce-demo.html
# → { "access_token":"…", "refresh_token":"…", "family_id":"…", … }
```

If you send a **wrong** verifier the server rejects with `400 invalid_grant: code_verifier does not match code_challenge`.

---

## Pattern 3 — JWT refresh rotation + reuse detection

### The threat model

A typical refresh-token-as-cookie scheme has a hole: if the refresh token leaks (XSS, malware, dev console), the attacker can mint access tokens forever — even after the legit user logs in again — and **nobody notices**.

Rotation + reuse detection plugs the hole:

- Every refresh call **issues a fresh refresh token AND marks the consumed one as `used`**.
- All refresh tokens issued in one login share a `family_id`.
- If the server **ever** sees a refresh token marked `used` come in again → **revoke the whole family**. Both attacker and legit user are kicked out. The legit user logs in again with a clean family; the attacker is locked out forever.

### State machine

```
                 first POST /auth/login            POST /auth/refresh
                ───────────────────────►            (used=false)
              ┌──────────────────┐    ┌──────────────────────────────────┐
              │  fam=F1 refresh1 │ ─► │  fam=F1 refresh2 (refresh1=used) │
              └──────────────────┘    └──────────────────────────────────┘
                                                       │
                                       legit user POST /auth/refresh
                                       (refresh1 stolen by attacker)
                                                       ▼
                          ┌─────────────────────────────────────────────────┐
                          │ attacker replays refresh1 (which is used=true)  │
                          │ → REUSE_DETECTED → revoke entire family F1      │
                          │ refresh2 also invalidated                       │
                          └─────────────────────────────────────────────────┘
```

### Try it in the browser

Visit **<http://localhost:8080/jwt-demo.html>** — login, click `Save snapshot` (simulates an attacker stealing the refresh token at that moment), click `Replay stolen token`. You'll see the family revoked. Inspect `/auth/debug` (button on the page) to see the in-memory state.

### curl

```bash
# Login → access + refresh (new family)
RESP=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password"}')
ACCESS=$(echo "$RESP"  | jq -r .accessToken)
REFRESH=$(echo "$RESP" | jq -r .refreshToken)
FAMILY=$(echo "$RESP"  | jq -r .familyId)

# (attacker steals $REFRESH at this point)
STOLEN=$REFRESH

# Legit user rotates → new refresh issued, old marked used
RESP=$(curl -s -X POST http://localhost:8080/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}")
REFRESH=$(echo "$RESP" | jq -r .refreshToken)

# Attacker replays the OLD token → reuse detected, family revoked
curl -s -X POST http://localhost:8080/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$STOLEN\"}" | jq
# → { "error": "reuse_detected", "familyId": "…" }

# Legit user's NEW refresh now also fails
curl -s -X POST http://localhost:8080/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}" | jq
# → { "error": "family_revoked" } or "unknown_token"  (records wiped)
```

### Notes

- Access tokens are 5 min, refresh tokens 7 days (`app.jwt.*-ttl-seconds` in `application.yml`).
- HS256 with a 256-bit secret is used for simplicity. **Production**: RS256/ES256 with the private key in a KMS, so verifiers only need the public key.
- The store is an in-memory `ConcurrentHashMap`. Production: Redis with TTL == refresh expiry, or a DB table indexed by `family_id`.
- The `RefreshTokenStore.rotate` method is the heart of this — read it. The whole pattern fits in ~50 lines of state machine logic.

---

## Pattern 4 — Content negotiation

Same URL `GET /api/users/{id}` returns **different shapes** depending on what the client requests.

### Three styles, side by side

| Style | Request header | When to use |
|---|---|---|
| Vendor media type | `Accept: application/vnd.webapipoc.v1+json` | Most "RESTful". Spring routes to a different controller method per type — zero `if` logic. |
| Version on `Accept` param | `Accept: application/json;version=1` | Pragmatic. Body stays plain JSON, only the version param changes. |
| Custom header | `Api-Version: 1` | Easiest for SDKs to set globally; works with tooling that strips `Accept` (some proxies). |

If no hint is sent we default to the **latest** version.

### Schema diff

```json
// v1
{ "id": 1, "name": "Andre Nguyen", "email": "andre@example.com" }

// v2 — name split, email dropped, phone + joinedOn added
{ "id": 1, "firstName": "Andre", "lastName": "Nguyen", "phone": "+84-901-000-001", "joinedOn": "2022-03-01" }
```

### Try it

Visit **<http://localhost:8080/content-negotiation-demo.html>** — three button groups, one per style. Server echoes the resolved version via `X-Resolved-Version` and which style won the negotiation via `X-Negotiation`.

### curl

```bash
# A. vendor media type
curl -s http://localhost:8080/api/users/1 -H 'Accept: application/vnd.webapipoc.v1+json' | jq
curl -s http://localhost:8080/api/users/1 -H 'Accept: application/vnd.webapipoc.v2+json' | jq

# B. Accept param
curl -s http://localhost:8080/api/users/1 -H 'Accept: application/json;version=1' | jq
curl -s http://localhost:8080/api/users/1 -H 'Accept: application/json;version=2' | jq

# C. custom header
curl -s http://localhost:8080/api/users/1 -H 'Api-Version: 1' | jq
curl -s http://localhost:8080/api/users/1 -H 'Api-Version: 2' | jq

# Default → latest
curl -s http://localhost:8080/api/users/1 | jq
```

---

## Production checklist (what's intentionally NOT in this POC)

- [ ] Replace in-memory stores with persistent ones (`RefreshTokenStore` → Redis, `ProductStore` → JPA, `PkceController.codes` → Redis with TTL).
- [ ] HS256 → RS256/ES256 with private key in a KMS (AWS KMS, Vault, HSM).
- [ ] PKCE authorize endpoint must show a real **login + consent** screen instead of auto-issuing for `?subject=alice`.
- [ ] `/auth/debug` MUST be removed or behind admin auth.
- [ ] CORS configured for actual SPA origin (currently relies on same-origin since UI is served by the same app).
- [ ] Log scrubbing — never log full JWTs in production.
- [ ] Rate-limit `/auth/login`, `/auth/refresh`, `/oauth/token`.
- [ ] Add CSRF protection if you serve any session-cookie auth alongside JWT.
- [ ] Use HTTPS everywhere; mark refresh-token cookies `Secure; HttpOnly; SameSite=Strict` if you keep them in cookies.
- [ ] Soft delete of refresh-token records keyed by `family_id` for forensics — don't wipe them, mark them.

## Further reading

- ETag / conditional requests — [RFC 9110 §13](https://www.rfc-editor.org/rfc/rfc9110#name-conditional-requests)
- OAuth 2.0 PKCE — [RFC 7636](https://datatracker.ietf.org/doc/html/rfc7636)
- OAuth 2.1 (PKCE mandatory) — [draft](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1)
- Refresh token rotation + reuse detection — [Auth0 write-up](https://auth0.com/docs/secure/tokens/refresh-tokens/refresh-token-rotation)
- API versioning — [Roy Fielding's "Versioning REST Services" pragmatic note](https://www.infoq.com/articles/roy-fielding-on-versioning/)
