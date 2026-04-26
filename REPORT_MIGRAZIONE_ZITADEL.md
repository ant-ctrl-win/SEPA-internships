# Report Migrazione Zitadel — SEPA Client-API e Dashboard

Commit: `5f37b17` — *feat: implementata autenticazione Zitadel, fix UI freeze e persistenza credenziali*

---

## 1. OAuthProperties.java

**File:** `client-api/src/main/java/com/vaimee/sepa/api/commons/security/OAuthProperties.java`

### Contesto Precedente

- L'enum `OAUTH_PROVIDER` conteneva solo `SEPA` e `KEYCLOAK` — impossibile selezionare Zitadel dal JSAP.
- La decrittazione dei campi (`client_id`, `client_secret`, `jwt`, `expires`, `type`) usava `encryption.decrypt()` direttamente: se il valore era in chiaro, lanciava `SEPASecurityException` causando `SEPAPropertiesException` a livello costruttore.
- Il costruttore usava `oauthJsonObject` senza mai inizializzarlo (la riga `oauthJsonObject = jsap.getAsJsonObject("oauth")` era commentata con un `TODO`). Conseguenza: NPE al primo accesso.
- `storeProperties()` costruiva un `JsonObject` vuoto standalone e lo scriveva su disco, **sovrascrivendo l'intero JSAP** (cancellando `host`, `namespaces`, `queries`, `updates`).
- `propertiesFile` non veniva mai impostato → `storeProperties()` falliva silenziosamente (null-guard introdotta a sessione corrente).
- `isClientRegistered()` in `JSAP.getAuthenticationProperties()` restituiva sempre null poiché il metodo era uno stub.

### Intervento Effettuato

1. **Enum esteso**: aggiunto `ZITADEL` a `OAUTH_PROVIDER`.
2. **Riconoscimento provider**: il costruttore riconosce `"zitadel"` come provider valido.
3. **Decrypt resiliente**: sostituiti `encryption.decrypt()` con helper `decryptOrDefault()` / `decryptLongOrDefault()` — se il decrypt fallisce (es. valore già in chiaro), il valore raw viene usato così com'è.
4. **Inizializzazione `oauthJsonObject`**: ora viene assegnato via `jsap.getOauth()` (nuovo metodo su JSAP), con guardia `if (oauthJsonObject == null) return`.
5. **`propertiesFile` inizializzato**: nel costruttore `this.propertiesFile = new File(jsap.getBaseUri())`.
6. **`storeProperties()` riscritto (Read-Merge-Write)**:
   - Legge il file JSAP intero con Gson (`FileReader` → `JsonObject`)
   - Costruisce un nuovo `oauthBlock` con i valori aggiornati
   - Sostituisce il blocco `"oauth"` nel JSON completo con `fullJsap.add("oauth", oauthBlock)`
   - Scrive il JSON completo su disco (try-with-resources)
   - Null-guard: se `propertiesFile == null`, logga warning e ritorna
7. **Tre helper privati finali**: `decryptOrDefault(String)` e `decryptLongOrDefault(String)` con fallback a valore raw.

### Flusso e Risultato

Il JSAP ora può contenere `"provider": "zitadel"` con `client_id` e `client_secret` in chiaro. La prima autenticazione popola i campi, e al `storeProperties()` successivo il token JWT e i credential vengono criptati e salvati nel file senza distruggere il resto del JSAP. Al riavvio, `decryptOrDefault` decritta correttamente i valori salvati.

---

## 2. JSAP.java

**File:** `client-api/src/main/java/com/vaimee/sepa/api/pattern/JSAP.java`

### Contesto Precedente

- Nessun campo per il blocco `"oauth"`: Gson ignorava silenziosamente la sezione OAuth del JSON.
- `isSecure()` restituiva `false` hardcoded → la Dashboard saltava sempre il login, qualunque cosa ci fosse nel JSAP.
- `getAuthenticationProperties()` restituiva `null` hardcoded → qualsiasi chiamante che provava a usare l'auth NPE-ava.

### Intervento Effettuato

1. **Campo `oauth`**: `protected JsonObject oauth = null` — Gson ora deserializza il blocco `"oauth"` dal JSON.
2. **Campo cache**: `private transient OAuthProperties cachedOauthProperties = null` — singleton pattern per evitare N chiamate costruttore (e perdita del token in memoria).
3. **Getter `getOauth()`**: espone il `JsonObject` raw a `OAuthProperties`.
4. **`isSecure()`**: controlla `oauth != null && oauth.has("enable") && oauth.get("enable").getAsBoolean()`.
5. **`getAuthenticationProperties()`**: lazy-init via `cachedOauthProperties`; crea `new OAuthProperties(this)` solo al primo accesso.
6. **Propagazione**: il campo `oauth` viene copiato nel costruttore, in `read()` (replace mode), e nel `merge()`.

### Flusso e Risultato

Quando un file JSAP contiene `"oauth": {"enable": true, ...}`, la Dashboard ora mostra il dialog di login. L'istanza `OAuthProperties` è condivisa tra tutti i chiamanti (Client, GenericClient, Dashboard) grazie alla cache, evitando doppie richieste HTTP e preservando il token JWT in memoria.

---

## 3. KeycloakAuthenticationService.java

**File:** `client-api/src/main/java/com/vaimee/sepa/api/commons/security/KeycloakAuthenticationService.java`

### Contesto Precedente

- `registerClient()` implementava la registrazione dinamica su Keycloak con `POST /clients-registrations/default`, `initialAccessToken`, hardcoded claim mapper per `username`.
- `requestToken()` inviava `grant_type=client_credentials` con header `Authorization: Basic <base64(clientId:clientSecret)>` senza URL-encoding delle credenziali (non conforme a Zitadel).
- `requestToken()` usava `StringEntity` senza `UrlEncodedFormEntity`.

### Intervento Effettuato

1. **`registerClient()` stubato**: restituisce immediatamente `ErrorResponse(501, "not_supported", "Dynamic client registration disabled...")`. La registrazione dei client su Zitadel avviene manualmente da Console.
   - Rimossi 6 import non più utilizzati: `UnsupportedEncodingException`, `URISyntaxException`, `ParseException`, `JsonArray`, `JsonPrimitive`, `RegistrationResponse`.
2. **`requestToken()` riscritto**:
   - Body: `UrlEncodedFormEntity` con `grant_type=client_credentials&scope=openid profile`.
   - Header `Authorization: Basic <base64(urlEncoded(clientId) + ":" + urlEncoded(clientSecret))>` — URL-encoding prima del Base64 come richiesto da Zitadel.
   - Aggiunti import: `ArrayList`, `List`, `NameValuePair`, `UrlEncodedFormEntity`, `BasicNameValuePair`.
3. **Debug println**: stampa header e body esatto inviato a Zitadel (da rimuovere in produzione).

### Flusso e Risultato

La richiesta token ora è conforme a OAuth 2.0 client_secret_basic con URL-encoding. Zitadel restituisce `200 OK` con `access_token`, `token_type=bearer`, `expires_in`. Il client non tenta più la registrazione dinamica (che non esiste su Zitadel per i service user).

---

## 4. KeyCloakSecurityManager.java

**File:** `engine/src/main/java/com/vaimee/sepa/engine/dependability/authorization/KeyCloakSecurityManager.java`

### Contesto Precedente

- Il costruttore inizializzava `SyncLdap`, `VirtuosoIsql` e avviava un thread `UsersSync` (sincronizzazione LDAP → Virtuoso ogni 5 secondi). Tutte dipendenze da LDAP e Virtuoso non necessarie con Zitadel.
- `validateToken()` cercava il claim `username` con fallback a `preferred_username` (pattern Keycloak). Zitadel non fornisce `username` nei token `client_credentials`.
- `getEndpointCredentials()` usava `ldap.getEndpointUsersPassword()` — password SPARQL endpoint condivisa da LDAP.

### Intervento Effettuato

1. **`MOCK_PASSWORD`**: costante `"MOCK_PASSWORD_123"` — sostituisce la password condivisa LDAP. In produzione va configurata con la password reale dell'endpoint SPARQL.
2. **Costruttore pulito**: rimossi `SyncLdap ldap`, `VirtuosoIsql isql`, `new UsersSync(ldap, isql)`, `Logging.log` della password LDAP. I parametri `LdapProperties` e `IsqlProperties` sono ancora accettati ma ignorati (per retrocompatibilità coi chiamanti `Dependability`).
3. **Fallback claim a 3 livelli**: `preferred_username` → `username` → `client_id`.
   - Zitadel fornisce `client_id` nei token `client_credentials`.
   - Il messaggio di errore ora dice `"User identity claim not found"` anziché `"Username claim not found"`.

### Flusso e Risultato

L'engine ora valida correttamente i token Zitadel estraendo il `client_id` dal JWT. Non servono più LDAP o Virtuoso per il funzionamento base. La password SPARQL endpoint è `MOCK_PASSWORD_123` (sostituibile).

---

## 5. EngineProperties.java

**File:** `engine/src/main/java/com/vaimee/sepa/engine/core/EngineProperties.java`

### Contesto Precedente

- `setSecurity()` supportava solo `local`, `ldap`, `keycloak` come valori di `gates.security.type`.
- Il metodo `isKeycìCloakEnabled()` (con typo `ì`) era l'unico check per Keycloak.

### Intervento Effettuato

1. **Nuovo metodo `isZitadelEnabled()`**: controlla `this.parameters.gates.security.type.equals("zitadel")`.
2. **`setSecurity()` esteso**: nuovo ramo `else if (isZitadelEnabled())` che chiama `Dependability.enableKeyCloakSecurity(ssl, jwt, ldap, isql)` — riusa l'infrastruttura KeyCloak esistente.

### Flusso e Risultato

Per attivare Zitadel sull'engine, basta impostare `"gates": {"security": {"type": "zitadel"}}` in `engine.jpar`. L'engine riusa la stessa pipeline di validazione JWT di Keycloak.

---

## 6. JWTResponse.java

**File:** `client-api/src/main/java/com/vaimee/sepa/api/commons/response/JWTResponse.java`

### Contesto Precedente

- Il costruttore `JWTResponse(JsonObject json)` si limitava a `this.json = json`. Nessun parsing dei campi opzionali.
- I getter (`getAccessToken()`, `getTokenType()`, `getExpiresIn()`) usavano try-catch, quindi resilienti.
- Nessun campo per i campi opzionali Keycloak/Zitadel.

### Intervento Effettuato

1. **Nuovi campi**: `accessToken`, `tokenType`, `expiresIn`, `refreshToken`, `sessionState`, `scope`, `refreshExpiresIn`, `notBeforePolicy`.
2. **Parsing null-safe**: tutti i campi opzionali (`refresh_token`, `session_state`, `scope`, `refresh_expires_in`, `not-before-policy`) vengono estratti solo se presenti e non-null nel JSON. I campi obbligatori (`access_token`, `token_type`, `expires_in`) usano guardia `isJsonNull()`.

### Flusso e Risultato

La risposta token di Zitadel (che non include `refresh_token` o `session_state` nel flusso `client_credentials`) non causa più eccezioni durante il parsing o in fasi downstream.

---

## 7. Login.java (Dashboard UI)

**File:** `tool-dashboard/src/main/java/com/vaimee/sepa/tools/dashboard/utils/Login.java`

### Contesto Precedente

- `submit()` eseguiva `sm.refreshToken()` **in modo sincrono sull'Event Dispatch Thread (EDT)**, bloccando l'intera UI Swing per la durata della chiamata HTTP a Zitadel.
- Dopo il successo, chiamava `m_listener.onLogin(uid)` ma **non chiudeva mai il dialog** (`dispose()` assente).
- La X della finestra chiudeva il dialog senza alcuna protezione.
- Il costruttore importava `SEPASecurityException` e `SEPAPropertiesException` (ora non più necessari).

### Intervento Effettuato

1. **`SwingWorker` per HTTP asincrono**: `doInBackground()` esegue `sm.refreshToken()` fuori dall'EDT; `done()` (su EDT) gestisce il risultato.
   - Successo: `dispose()` chiude il dialog modale, sbloccando la Dashboard.
   - Errore: dialog resta aperto con titolo "Wrong credentials", bottone riabilitato.
2. **`JDialog.DO_NOTHING_ON_CLOSE`**: impedisce la chiusura del dialog via X/Alt+F4. L'unico modo per chiudere è login riuscito (`dispose()` in `done()`) o terminazione del processo.
3. **Stato UI durante richiesta**: bottone disabilitato, titolo "Authenticating...".
4. **Pulizia import**: rimossi `SEPASecurityException`, `SEPAPropertiesException` non più usati.

### Flusso e Risultato

L'utente clicca Login → il bottone si disabilita e appare "Authenticating..." → la chiamata HTTP avviene in background (UI responsive) → al successo il dialog si chiude e la Dashboard si avvia → al fallimento il dialog resta visibile per riprovare. La X non chiude più il dialog (difesa in profondità).

---

## 8. Dashboard.java

**File:** `tool-dashboard/src/main/java/com/vaimee/sepa/tools/dashboard/Dashboard.java`

### Contesto Precedente

- `onLoginClose()` era vuoto: chiudere il Login con la X sbloccava l'EDT e la Dashboard proseguiva senza autenticazione (bypass di sicurezza).

### Intervento Effettuato

1. **`onLoginClose()`**: ora chiama `System.exit(0)` con log warning. Chiudere il dialog di login senza autenticarsi termina il processo.

### Flusso e Risultato

Non è più possibile bypassare l'autenticazione chiudendo la finestra di login. L'applicazione termina immediatamente.

---

## Riepilogo complessivo

| File | Modulo | Modifiche |
|---|---|---|
| `OAuthProperties.java` | client-api | Enum ZITADEL, decrypt resiliente, oauthJsonObject inizializzato, Read-Merge-Write in storeProperties(), propertiesFile da baseUri |
| `JSAP.java` | client-api | Campo `oauth` (Gson), `isSecure()` funzionante, `getAuthenticationProperties()` con cache singleton |
| `KeycloakAuthenticationService.java` | client-api | registerClient() stubato, requestToken() con Basic Auth URL-encoded + body `grant_type=client_credentials&scope=openid profile` |
| `KeyCloakSecurityManager.java` | engine | MOCK_PASSWORD, costruttore pulito (no SyncLdap/UsersSync), claim fallback `client_id` |
| `EngineProperties.java` | engine | `isZitadelEnabled()`, integrazione in `setSecurity()` |
| `JWTResponse.java` | client-api | Campi opzionali con parsing null-safe |
| `Login.java` | dashboard | SwingWorker (HTTP async), dispose() dopo login, DO_NOTHING_ON_CLOSE |
| `Dashboard.java` | dashboard | System.exit(0) in onLoginClose() |
