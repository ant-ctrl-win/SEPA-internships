# Piano di Migrazione Keycloak → Zitadel (OIDC)

**Approccio**: conservativo, "Make it work". Nessuna riscrittura architetturale.
**Stato attuale**: Keycloak dormiente — l'engine valida solo JWT esterni, non emette token.

---

## File 1: `engine/.../authorization/KeyCloakSecurityManager.java`

### 1a. Claim: aggiungere fallback su `client_id`
**Intento**: Il token Zitadel per Utente Macchina (Service User, grant `client_credentials`) non contiene `preferred_username`, ma contiene `client_id`. Aggiungere un terzo livello di fallback dopo `username` e `preferred_username`.

**Modifica**: Nel metodo `validateToken()`, dopo il controllo di `preferred_username` (riga ~100), se anche quello è null, cercare `claimsSet.getStringClaim("client_id")`. Se trovato, usarlo come `uid`. Se anch'esso null, restituire errore.

### 1b. LDAP: rimuovere `SyncLdap` e `VirtuosoIsql` dal costruttore
**Intento**: Disabilitare completamente la connessione a LDAP di produzione e il thread `UsersSync`. Sostituire con uno stub che restituisce una password fissa.

**Modifiche**:
- Rimuovere i campi `ldap` (tipo `SyncLdap`) e `isql` (tipo `VirtuosoIsql`).
- Cambiare la firma del costruttore: non accettare più `LdapProperties` e `IsqlProperties` (o accettarli ma ignorarli).
- Non istanziare `new SyncLdap(...)`, non istanziare `new VirtuosoIsql(...)`, non avviare `new UsersSync(...)`.
- Rimuovere il log `EndpointUsersPassword: ...` che stampava la password reale LDAP.
- Aggiungere una costante `private static final String MOCK_PASSWORD = "MOCK_PASSWORD_123";`.

### 1c. `getEndpointCredentials()`: usare la password mock
**Intento**: Invece di chiamare `ldap.getEndpointUsersPassword()`, restituire sempre la costante `MOCK_PASSWORD_123`.

**Modifica**: Nel metodo `getEndpointCredentials()` (riga ~203), sostituire:
```
return new Credentials(uid, ldap.getEndpointUsersPassword());
```
con:
```
return new Credentials(uid, MOCK_PASSWORD);
```
(La password fissa è necessaria perché il backend SPARQL si aspetta credenziali per autorizzare l'accesso. Il valore `MOCK_PASSWORD_123` deve coincidere con l'utente/password configurato sul backend SPARQL — es. Jena in-memory non richiede auth, ma Virtuoso sì.)

---

## File 2: `client-api/.../security/KeycloakAuthenticationService.java`

### 2a. Disabilitare `registerClient()`
**Intento**: Il client Zitadel verrà creato manualmente (Zitadel Console / API). La registrazione dinamica non è più necessaria.

**Modifica**: Nel metodo `registerClient()`, restituire immediatamente un `ErrorResponse` con messaggio esplicito, senza effettuare alcuna chiamata HTTP:
```
return new ErrorResponse(501, "not_supported", "Dynamic client registration disabled. Configure client manually in Zitadel.");
```
(Opzionale: eliminare tutto il corpo esistente e lasciare solo questo return anticipato.)

### 2b. Verificare che `requestToken()` usi l'endpoint Zitadel
**Intento**: Il metodo `requestToken()` già funziona per `grant_type=client_credentials`. L'unica modifica necessaria è assicurarsi che il campo `authentication.endpoint` nel JSAP punti a Zitadel (`https://<zitadel-instance>/oauth/v2/token`) anziché a Keycloak.

**Modifica**: Nessuna modifica al codice Java. La modifica è solo di configurazione (vedi File 6).

---

## File 3: `client-api/.../security/OAuthProperties.java`

### 3a. Aggiungere supporto per `client_id` e `client_secret` in chiaro (non cifrati) nella sezione `authentication`
**Intento**: Attualmente OAuthProperties legge `client_id` e `client_secret` decifrandoli via `Encryption.decrypt()`. Per l'iniezione manuale delle credenziali statiche, permettere anche la lettura in chiaro (raw) dal JSAP, rendendo la cifratura opzionale.

**Modifica**: Nel costruttore, alla lettura di `auth.get("client_id")` e `auth.get("client_secret")`:
- Se il valore inizia con il prefisso di cifratura (attualmente `encryption.encrypt()` produce un output specifico), decifralo.
- Altrimenti usalo in chiaro (raw string).
- *(Alternativa più rapida):* Pre-cifrare le credenziali Zitadel con `Encryption.encrypt()` e inserirle già cifrate nel `.jsap`. In questo caso nessuna modifica al codice.

### 3b. Rinominare l'enum o i riferimenti testuali a "keycloak"
**Intento**: Il JSAP ha `"provider": "keycloak"`. Aggiungere il riconoscimento del valore `"zitadel"` come alias di `"keycloak"`, così da non dover cambiare tutti i JSAP esistenti immediatamente.

**Modifica**: Nell'enum `OAUTH_PROVIDER` aggiungere `ZITADEL`. Nel parsing del provider (riga ~118), riconoscere sia `"keycloak"` che `"zitadel"` e mapparli entrambi al nuovo provider (o tenerli distinti ma con lo stesso comportamento).

### 3c. (Nota) JSAP bypassa OAuthProperties se `oauth.enable: false`
**Intento**: Se il JSAP ha `"oauth": { "enable": false }`, OAuthProperties non viene popolato e il ClientSecurityManager usa `DefaultAuthenticationService` (SEPA nativo). Per attivare Zitadel, il JSAP deve avere `"enable": true`.

**Nessuna modifica**: È un vincolo di configurazione, non di codice.

---

## File 4: `engine/.../core/EngineProperties.java`

### 4a. Riconoscere il tipo `"zitadel"` nel `engine.jpar`
**Intento**: Il campo `parameters.gates.security.type` oggi accetta `local`, `ldap`, `keycloak`. Aggiungere `zitadel` come alias che instrada allo stesso `KeyCloakSecurityManager` (che dopo la migrazione sarà di fatto lo ZitadelSecurityManager).

**Modifica**: Nel metodo `setSecurity()` (riga ~793), la condizione `isKeycìCloakEnabled()` controlla `type.equals("keycloak")`. Modificare per accettare anche `"zitadel"`:
```
if (type.equals("keycloak") || type.equals("zitadel"))
```
*(N.B. il metodo ha un typo nel nome: `isKeycìCloakEnabled()` con la `ì` accentata.*)

### 4b. Passare parametri LDAP/ISQL solo se necessari
**Intento**: Poiché `KeyCloakSecurityManager` non userà più LDAP e Virtuoso, valutare se semplificare la chiamata `Dependability.enableKeyCloakSecurity(ssl, jwt, ldap, isql)` per non passare più `ldap` e `isql`.

**Modifica**: Se la firma del costruttore di `KeyCloakSecurityManager` viene cambiata (File 1), adattare questa chiamata. Altrimenti, passare `null` o oggetti vuoti per `ldap` e `isql` — ma attenzione ai NullPointerException nel costruttore.

---

## File 5: `engine/.../dependability/Dependability.java`

### 5a. Eventuale nuovo metodo factory per Zitadel
**Intento**: Se si vuole mantenere separazione pulita tra Keycloak e Zitadel, aggiungere un metodo `enableZitadelSecurity(SSLContext, RSAKey)`.

**Modifica**: Aggiungere un nuovo metodo statico che istanzia `KeyCloakSecurityManager` (o una sua versione rifattorizzata) senza LDAP/ISQL. Questo evita di dover toccare il metodo `enableKeyCloakSecurity()` esistente.

---

## File 6: JSAP di configurazione client

### 6a. Struttura JSAP per Zitadel
**Intento**: Il file `.jsap` caricato dal client/dashboard deve contenere la configurazione Zitadel.

**Struttura aggiornata**:
```json
{
  "oauth": {
    "enable": true,
    "ssl": "TLSv1.2",
    "loadTrustMaterial": {
      "jks": "store.jks",
      "secret": "<JKS_password>"
    },
    "registration": {
      "endpoint": "https://<zitadel-instance>/oauth/v2/token",
      "username": "<service_user_id>"
    },
    "authentication": {
      "endpoint": "https://<zitadel-instance>/oauth/v2/token",
      "client_id": "<client_id>",
      "client_secret": "<client_secret>"
    },
    "provider": "zitadel"
  }
}
```

### 6b. Note sui campi
- `registration.endpoint` — ex Keycloak `.../clients-registrations/default`. Con Zitadel non serve più (client creato a mano). Può essere omesso o lasciato vuoto, ma il codice di `OAuthProperties` lo richiede se la sezione `registration` esiste. Se si omette l'intera sezione `registration`, `registerClient()` non verrà mai chiamato. **Raccomandazione**: rimuovere l'intera sezione `registration` dal JSAP.
- `authentication.endpoint` — **DEVE** puntare a Zitadel: `https://<zitadel-instance>/oauth/v2/token`
- `authentication.client_id` e `authentication.client_secret` — credenziali dell'applicazione/service user creato manualmente su Zitadel (grant type: `urn:ietf:params:oauth:grant-type:jwt-bearer` con `client_credentials`).
- `provider` — impostare a `"zitadel"` (dopo la modifica 3b).

---

## File 7: `engine.jpar`

### 7a. Aggiornare il tipo di sicurezza
```json
{
  "parameters": {
    "gates": {
      "security": {
        "enabled": true,
        "type": "zitadel",
        "tls": true
      }
    }
  }
}
```
(Se la modifica 4a è stata applicata, `"zitadel"` viene riconosciuto. Altrimenti tenere temporaneamente `"keycloak"`.)

---

## Riepilogo delle dipendenze

| Priorità | File | Tipo modifica |
|---|---|---|
| **CRITICA** | `KeyCloakSecurityManager.java` | Aggiungere fallback `client_id`, rimuovere SyncLdap/VirtuosoIsql, password mock |
| **ALTA** | `KeycloakAuthenticationService.java` | Stub `registerClient()` |
| **MEDIA** | `OAuthProperties.java` | Supporto `"zitadel"` come provider, credenziali raw |
| **MEDIA** | `EngineProperties.java` | Riconoscere `"zitadel"` nel type |
| **BASSA** | `Dependability.java` | Eventuale factory dedicato |
| **CONFIG** | `.jsap` client | Endpoint Zitadel, credenziali statiche |
| **CONFIG** | `engine.jpar` | `type: "zitadel"` |

---

## Cosa NON toccare

- **Nessuna nuova dipendenza Maven** — Zitadel è OIDC standard, il client HTTP + nimbus-jose-jwt sono sufficienti.
- **Nessuna modifica a `SecurityManager.java` (base class)** — la validazione JWT generica già funziona.
- **Nessuna modifica a `SecureSPARQL11Handler.java` / `SecureWebsocketGate.java`** — il flusso di estrazione Bearer token e chiamata a `Dependability.validateToken()` è invariato.
- **Nessuna modifica a `ClientSecurityManager.java`** — è un semplice factory/router; il grosso del lavoro è nei Service.
- **Nessuna modifica a `Login.java` (Dashboard)** — la UI è invariata.
- **Nessuna modifica a `Dashboard.java`** — l'avvio e il flusso di login sono invariati.

---

## Verifiche post-migrazione

1. Avviare Zitadel con un Service User configurato per `client_credentials`.
2. Ottenere un token JWT da Zitadel: `POST /oauth/v2/token` con `grant_type=client_credentials` e Basic Auth.
3. Decodificare il token (es. jwt.io) e verificare che contenga il claim `client_id`.
4. Avviare l'engine SEPA con `engine.jpar` che imposta `security.type=zitadel`.
5. Inviare una query SPARQL con header `Authorization: Bearer <zitadel_token>`.
6. Verificare nei log che il token venga validato e che `uid=<client_id>` venga usato per le credenziali endpoint.
