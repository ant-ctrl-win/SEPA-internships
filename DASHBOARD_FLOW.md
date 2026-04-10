# SEPA Dashboard Startup Flow & Auth Logic

## 1. Dashboard Boot Sequence

```
Dashboard.main()
  └─ EventQueue.invokeLater → new Dashboard()
       └─ initialize()
            └─ loadJSAP(null, true)
                 ├─ loadDashboardProperties()          → reads "dashboard.properties" from CWD
                 │    └─ appProfile = path to JSAP file
                 │
                 ├─ new JSAP("file:///" + jsapPath)      → CONSTRUCTOR CHAIN (3 reads of same file)
                 │    ├─ SPARQL11Properties(uri, args)
                 │    │    └─ Gson.fromJson → this.sparql11protocol = jsap.sparql11protocol  ⚠ null if key absent
                 │    │    └─ else: sparql11protocol = new SPARQL11ProtocolProperties()       ✅ safe defaults
                 │    │
                 │    ├─ SPARQL11SEProperties(uri, args)
                 │    │    └─ Gson.fromJson → this.sparql11seprotocol = jsap.sparql11seprotocol ⚠ null if key absent
                 │    │    └─ else: sparql11seprotocol = new SPARQL11SEProtocolProperties()    ✅ safe defaults
                 │    │
                 │    └─ JSAP(uri, args)
                 │         └─ Gson.fromJson → copies host, namespaces, queries, updates, extended
                 │
                 ├─ Classpath merge: appProfile.read(explorer.jsap from classpath, false)
                 │    └─ merge(): if this.sparql11protocol==null → assign temp.sparql11protocol
                 │               (if temp also null → stays null ⚠)
                 │
                 ├─ appProfile.isSecure() → checks if OAuth should be used
                 │    ├─ true  → new Login(oauth, listener, frame) → modal dialog
                 │    └─ false → onLogin("ვაიმეე")               → skip auth
                 │
                 └─ onLogin(id)
                      └─ new DashboadApp(appProfile, handler)
                           └─ new GenericClient(appProfile, handler)
                                └─ creates SPARQL11Protocol (HTTP client) + WebSocket connector
```

## 2. The `isSecure()` Decision

**File:** `JSAP.java:386` — currently hardcoded to return `false`.

The JSAP format supports `"oauth": { "enable": true, ... }`, but `JSAP.isSecure()` does not parse it. When `false`:

- No `Login` dialog is shown
- `onLogin("ვაიმეე")` is called directly
- No `ClientSecurityManager` is created
- No Bearer token is attached to requests
- The engine must also have security disabled (`engine.jpar` → `gates.security.enabled: false`)

When `true` (after the fix):

- A `Login` JDialog appears with ID + Password fields
- The user enters OAuth client credentials
- The login flow proceeds (see Section 3)

## 3. OAuth 2.0 Client-Credential Flow (Local Mode)

### Step 1: Registration (if not already registered)

```
Login.submit()
  └─ new ClientSecurityManager(oauth)
  └─ oauth.setCredentials(clientId, clientSecret)
  └─ if !oauth.isClientRegistered():
       └─ sm.registerClient(uid, username, initialAccessToken)
            └─ POST https://<host>:8443/oauth/register
                 Body: { "client_identity": "<uid>", "grant_types": ["client_credentials"] }
                 → RegistrationResponse(clientId, clientSecret, jwkPublicKey)
```

Engine-side (`InMemorySecurityManager.register()`):
- Verifies identity is in the pre-authorized list
- Generates a UUID client_secret (or `secret = uid` for test identities)
- Stores credentials in memory
- Removes identity from authorized list (one-time registration)
- Returns client ID, secret, and RSA public key (for JWT validation)

### Step 2: Token Request

```
ClientSecurityManager.refreshToken()
  └─ POST https://<host>:8443/oauth/token
       Authorization: Basic <Base64(clientId:clientSecret)>
       → JWTResponse(accessToken, tokenType="bearer", expiresIn)
```

Engine-side (`SecurityManager.getToken()`):
- Base64-decodes the Authorization header
- Validates client_id and client_secret against stored credentials
- If a valid, non-expired token exists, returns it
- Otherwise builds JWT claims: `{ sub: clientId, iat: now, exp: now + period }`
- Signs JWT with RS256 using RSA private key from `sepa.jks`
- Stores the signed JWT
- Token expiry by identity type: application=12h, device=1h, user=5min

### Step 3: Using the Token

Every SPARQL request from `GenericClient` includes:
```
Authorization: Bearer <jwt-access-token>
```

Engine-side validation (`SecureSPARQL11Handler.authorize()` or `Gate.authorize()`):
- Extracts Bearer token from header
- Parses `SignedJWT`
- Verifies RS256 signature against RSA public key from JKS
- Validates claims (expiration, not-before)
- Extracts `sub` (client ID) and looks up SPARQL endpoint credentials
- Returns `ClientAuthorization` with endpoint credentials for the SPARQL store

## 4. JWT Signing & Keystore

**File:** `JKSUtil.java` + `SecurityManager.java`

| Property | Default Value |
|---|---|
| Keystore file | `sepa.jks` |
| Keystore password | `sepa2020` |
| JWT key alias | `jwt` |
| JWT key password | `sepa2020` |
| Signing algorithm | RS256 (RSA SHA-256) |

Initialization in `EngineProperties.setSecurity()`:
```java
ssl = JKSUtil.getSSLContext("sepa.jks", "sepa2020");
jwt = JKSUtil.getRSAKey("sepa.jks", "sepa2020", "jwt", "sepa2020");
```

The RSA key pair is extracted using Nimbus JOSE's `RSAKey.load(keyStore, alias, password)`.

## 5. TLS Configuration

Two modes (controlled by JSAP `oauth` section):

**JKS-based TLS** (JSAP has `"loadTrustMaterial": { "jks": "...", "secret": "..." }`):
- Loads JKS as both key material and trust material
- Protocol: TLSv1.2

**Trust-all-CA** (JSAP has `"trustall": true` or no JKS):
- Custom `X509TrustManager` that accepts all certificates
- Hostname verification is disabled (`HostnameVerifier.verify()` always returns `true`)

## 6. Security Manager Modes

| Mode | JPAR `gates.security.type` | Signing | Identity Store | Token Store |
|---|---|---|---|---|
| **Local** | `local` | Engine signs JWTs | In-memory HashMap | In-memory HashMap |
| **LDAP** | `ldap` | Engine signs JWTs | Apache Directory LDAP | LDAP (`ou=tokens`) |
| **Keycloak** | `keycloak` | Keycloak signs JWTs | Keycloak + LDAP sync | Keycloak |

### Local (InMemorySecurityManager)
- Pre-seeded test identity: `SEPATest` / `SEPATest`
- All data stored in JVM memory (lost on restart)
- Best for development and testing

### LDAP (LdapSecurityManager)
- LDAP tree: `ou=authorizedIdentities,o=vaimee`, `ou=credentials,o=vaimee`, `ou=tokens,o=vaimee`
- Passwords hashed with SSHA
- Endpoint credentials stored as serialized Java objects
- Requires external LDAP server

### Keycloak (KeyCloakSecurityManager)
- Registration and token issuance delegated to Keycloak
- Engine only validates tokens (using Keycloak's realm public key loaded from JKS)
- `UsersSync` synchronizes LDAP users to Virtuoso ISQL for SPARQL endpoint access
- Requires external Keycloak + LDAP + Virtuoso

## 7. Post-Login Connection Flow

After `onLogin(id)` succeeds, the dashboard is ready to communicate with the engine. Every operation goes through `DashboadApp` → `GenericClient` → `SPARQL11Protocol` (HTTP) or WebSocket.

### Query flow (file:line references)

```
User clicks "Query" button
  → Dashboard.onQueryButton()                    [Dashboard.java:1487]
    → Dashboard.query()                           [Dashboard.java:1546]
      → DashboadApp.query(queryID, sparql, bindings, timeout, nRetry)  [DashboadApp.java:27]
        → GenericClient.query(queryID, sparql, forced, timeout, nRetry) [GenericClient.java:378]
          → SPARQL11Protocol.executeRequest(...)   [SPARQL11Protocol.java:125]
            → POST http://localhost:8000/query
              Authorization: Bearer <jwt> (if secure)
              Body: query=<url-encoded-sparql>
            ← QueryResponse (200) or ErrorResponse (4xx/5xx)
```

### Update flow

```
User clicks "Update" button
  → Dashboard.onUpdateButton()                    [Dashboard.java:1495]
    → Dashboard.update()                          [Dashboard.java:1587]
      → DashboadApp.update(updateID, sparql, bindings, timeout, nRetry) [DashboadApp.java:36]
        → GenericClient.update(updateID, sparql, forced, timeout, nRetry) [GenericClient.java:345]
          → SPARQL11Protocol.executeRequest(...)
            → POST http://localhost:8000/update
              Authorization: Bearer <jwt> (if secure)
              Body: update=<url-encoded-sparql>
            ← UpdateResponse (200) or ErrorResponse
```

### Subscribe flow

```
User clicks "Subscribe" button
  → Dashboard.onSubscribeButton()                 [Dashboard.java:1478]
    → Dashboard.subscribe()                       [Dashboard.java:1516]
      → DashboadApp.subscribe(queryID, sparql, bindings, timeout, nRetry) [DashboadApp.java:84]
        → GenericClient.subscribe(...)            [GenericClient.java:426]
          → WebSocket connect to ws://localhost:9000/subscribe
            → Send: { "subscribe": { "sparql": "...", "authorization": "Bearer <jwt>" } }
            ← { "subscribed": { "spuid": "..." } }
            ← { "added": { ... }, "removed": { ... } }  (on data change)
```

### Where Auth logic resides

| Component | File | Role |
|---|---|---|
| `JSAP.isSecure()` | `client-api/.../JSAP.java:386` | Decides if auth is needed (currently broken: always false) |
| `Login` dialog | `tool-dashboard/.../utils/Login.java` | Collects client_id + client_secret from user |
| `ClientSecurityManager` | `client-api/.../security/ClientSecurityManager.java` | Orchestrates register + token request on client side |
| `DefaultAuthenticationService` | `client-api/.../security/DefaultAuthenticationService.java` | HTTP calls to engine /oauth/register and /oauth/token |
| `OAuthProperties` | `client-api/.../security/OAuthProperties.java` | Stores credentials, JWT, expiry; generates auth headers |
| `SSLManager` | `client-api/.../security/SSLManager.java` | TLS context (JKS or trust-all-CA) |
| `SecurityManager` (engine) | `engine/.../authorization/SecurityManager.java` | Abstract base: JWT signing, validation, registration |
| `InMemorySecurityManager` | `engine/.../authorization/InMemorySecurityManager.java` | Local mode: HashMap-backed identity/credential/token store |
| `LdapSecurityManager` | `engine/.../authorization/LdapSecurityManager.java` | LDAP mode: Apache Directory LDAP for all stores |
| `KeyCloakSecurityManager` | `engine/.../authorization/KeyCloakSecurityManager.java` | Keycloak mode: delegates register/token to Keycloak |
| `JKSUtil` | `engine/.../authorization/JKSUtil.java` | Loads RSA key pair and SSLContext from sepa.jks |
| `Dependability` | `engine/.../dependability/Dependability.java` | Gatekeeper: selects and initializes SecurityManager mode |
| `SecureSPARQL11Handler` | `engine/.../gates/SecureSPARQL11Handler.java:97` | Validates Bearer token on incoming HTTP requests |
| `Gate.authorize()` | `engine/.../gates/Gate.java:211` | Validates Bearer token on incoming WebSocket messages |

## 8. Current Bugs in Auth Flow

1. **`JSAP.isSecure()` returns `false` always** — `oauth.enable` from JSAP is never parsed
2. **`JSAP.getAuthenticationProperties()` returns `null`** — OAuth section is not deserialized
3. **Null `sparql11protocol`/`sparql11seprotocol`** — See CONTEXT.md for the Gson deserialization bug
4. **Hostname verification disabled** — `SSLManager.verify()` always returns `true`
5. **Trust-all-CA mode** — Accepts any certificate by default in development
