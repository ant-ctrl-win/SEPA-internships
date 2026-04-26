# DEEP DIVE: Flusso End-to-End della Migrazione da Keycloak a Zitadel

Questo documento traccia il percorso logico-didattico della migrazione, seguendo l'ordine in cui l'applicazione esegue il codice: dall'avvio alla prima richiesta autenticata, fino alla validazione lato server. Ogni sezione espone il _perché_ di ogni modifica architetturale.

---

## 1. Il Flusso di Avvio e la Configurazione (Lettura del JSAP)

### 1.1 Il problema: Gson ignorava il blocco `"oauth"`

Quando la Dashboard si avvia, il primo atto è leggere il file `.jsap` (un JSON che descrive host, porte, endpoint SPARQL e configurazione OAuth). Gson deserializza questo JSON in un oggetto `JSAP`, che estende `SPARQL11SEProperties` → `SPARQL11Properties`.

**Prima della modifica**, `JSAP` aveva questo campo:

```java
// JSAP.java — VECCHIO (linea 177)
protected JsonObject oauth = null;  // CAMPO INESISTENTE
```

Non c'era _nessuna_ dichiarazione di campo `oauth`. Gson, per come funziona la reflection, popola solo i campi che esistono nella classe. Il blocco JSON `"oauth": { ... }` veniva letto dal file, ma non trovando un campo corrispondente nella classe Java, veniva **silenziosamente scartato**. Nessun errore, nessun warning — semplicemente i dati finivano nel vuoto.

Di conseguenza, `isSecure()` — il metodo che decide se mostrare la finestra di login — era hardcodato a `false`:

```java
// JSAP.java — VECCHIO (linea 379)
public boolean isSecure() {
    return false;  // HARDCODED — il blocco oauth non esisteva nel modello
}
```

E `getAuthenticationProperties()`, il metodo che la Dashboard chiama per ottenere l'oggetto `OAuthProperties` con cui fare login, restituiva `null`:

```java
// JSAP.java — VECCHIO (linea 384)
public OAuthProperties getAuthenticationProperties() {
    return null;  // HARDCODED — nessun OAuthProperties costruibile
}
```

**Risultato**: qualsiasi file `.jsap` con `"oauth": { "enable": true }` veniva ignorato. La Dashboard partiva sempre senza login, come se la sicurezza fosse disabilitata. Questo è il motivo per cui `explorer.jsap` (privo del blocco `oauth`) e `localhost.jsap` (con il blocco ma ignorato) si comportavano allo stesso modo.

### 1.2 La soluzione: far esistere il campo `oauth` nel modello

**Dopo la modifica**, `JSAP` dichiara esplicitamente il campo:

```java
// JSAP.java — NUOVO (linea 177)
protected JsonObject oauth = null;
private transient OAuthProperties cachedOauthProperties = null;
```

Ora Gson, quando deserializza il JSON, incontra la chiave `"oauth"` e la mappa sul campo `oauth` di tipo `JsonObject`. I dati sopravvivono alla deserializzazione.

Il campo viene anche propagato in tutti i meccanismi di copia e merge del JSAP:

```java
// Copia nel costruttore (linea 226)
oauth = jsap.oauth;

// Copia in read() — replace (linea 282)
this.oauth = jsap.oauth;

// Merge (linea 310)
if (temp.oauth != null) oauth = temp.oauth;
```

Con il campo popolato, `isSecure()` ora funziona correttamente:

```java
// JSAP.java — NUOVO (linea 405)
public boolean isSecure() {
    if (oauth == null) return false;
    if (!oauth.has("enable")) return false;
    return oauth.get("enable").getAsBoolean();
}
```

E `getOauth()` espone il `JsonObject` raw a chi ne ha bisogno:

```java
// JSAP.java — NUOVO (linea 388)
public JsonObject getOauth() {
    return oauth;
}
```

### 1.3 Il pattern Singleton per `OAuthProperties` (perché è vitale)

`getAuthenticationProperties()` viene chiamata **più volte** durante la vita della Dashboard: all'avvio per decidere se mostrare il login, subito dopo per costruire il `ClientSecurityManager`, e potenzialmente a ogni richiesta SPARQL per allegare il token. Se ogni chiamata creasse un nuovo `OAuthProperties`, ogni istanza avrebbe i propri campi interni (`clientId`, `clientSecret`, `jwt`, `expires`) — **vuoti**. Il token ottenuto al login esisterebbe solo nell'istanza usata per la chiamata HTTP, e l'istanza successiva (usata per la richiesta SPARQL) non lo conterrebbe.

La soluzione è un **lazy singleton con caching**:

```java
// JSAP.java — NUOVO (linea 392)
public OAuthProperties getAuthenticationProperties() {
    if (oauth == null) return null;
    if (cachedOauthProperties == null) {
        try {
            cachedOauthProperties = new OAuthProperties(this);
        } catch (SEPAPropertiesException e) {
            Logging.error("Failed to create OAuthProperties: " + e.getMessage());
            return null;
        }
    }
    return cachedOauthProperties;
}
```

- `transient` su `cachedOauthProperties` è intenzionale: il campo `OAuthProperties` non è serializzabile (contiene `Encryption`, `File`, socket HTTP impliciti). Gson non deve tentare di serializzarlo quando scrive il JSAP su disco.
- Il pattern garantisce che **tutte** le chiamate restituiscano la stessa istanza, quindi il token (`oauth.jwt`), il `clientId`, il `clientSecret`, e l'`expires` siano sempre coerenti.

### 1.4 L'aggancio nel costruttore di `OAuthProperties`

**Prima**, il costruttore aveva una riga commentata con un `TODO`:

```java
// OAuthProperties.java — VECCHIO (linea 107)
//oauthJsonObject = jsap.getAsJsonObject("oauth");
//TODO: to be adapted to the new JSAP structure!
```

Questo `TODO`, lasciato lì da tempo, conferma che il problema era noto — semplicemente non era mai stato risolto. Senza assegnare `oauthJsonObject`, tutto il costruttore saltava la lettura dei parametri OAuth.

**Dopo:**

```java
// OAuthProperties.java — NUOVO (linea 109)
oauthJsonObject = jsap.getOauth();
if (oauthJsonObject == null) return;
```

Ora il costruttore riceve il `JsonObject` dal JSAP (che a sua volta l'ha ricevuto da Gson) e popola tutti i campi: `enabled`, `provider`, `tokenRequestURL`, `clientId`, `clientSecret`, `jwt`, `expires`, `type`, ecc.

---

## 2. Il Flusso di Autenticazione (Network & UI)

### 2.1 Il trigger: `isSecure() == true`

Quando la Dashboard rileva `jsap.isSecure() == true`, chiama `jsap.getAuthenticationProperties()` e passa l'oggetto `OAuthProperties` al costruttore di `Login.java`. Il `Login` è un `JDialog` modale:

```java
// Login.java constructor
setModal(true);
setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
```

Modale significa che il chiamante (la Dashboard) rimane bloccato finché il dialog non viene chiuso. `DO_NOTHING_ON_CLOSE` impedisce alla X di chiudere la finestra senza passare dal flusso di autenticazione.

### 2.2 Il problema dell'EDT (Event Dispatch Thread)

**Prima**, il metodo `submit()` eseguiva la chiamata HTTP in modo sincrono sul thread della GUI:

```java
// Login.java — VECCHIO
private void submit() {
    try {
        sm = new ClientSecurityManager(oauth);
        oauth.setCredentials(ID.getText(), new String(PWD.getPassword()));

        Response ret = sm.refreshToken();  // <-- CHIAMATA DI RETE BLOCCANTE
        if (ret.isError()) {
            logger.error(ret);
            m_listener.onLoginError((ErrorResponse) ret);
            setTitle("Wrong credentials");
            return;
        }

        if (chckRemeberMe.isSelected()) oauth.storeProperties();
        m_listener.onLogin(ID.getText());
    } catch (SEPASecurityException | SEPAPropertiesException e1) {
        logger.error(e1.getMessage());
        m_listener.onLoginError(new ErrorResponse(401, "not_authorized", e1.getMessage()));
    }
}
```

Il problema è architetturale e ben noto in Swing: l'Event Dispatch Thread (EDT) è **l'unico thread che può aggiornare la GUI**. Se l'EDT viene bloccato da un'operazione lunga (come una chiamata di rete su HTTPS), l'intera interfaccia smette di rispondere:

1. Il pulsante "Login" viene premuto → `actionPerformed` chiama `submit()` sull'EDT.
2. `submit()` chiama `sm.refreshToken()` → handshake TLS + richiesta HTTP + attesa risposta (fino a timeout, tipicamente 5-10 secondi).
3. Durante tutta l'attesa, la finestra non può ridisegnarsi, i pulsanti non rispondono, il sistema operativo può marcare la finestra come "Not Responding".
4. `onLogin()` non chiamava mai `dispose()` sul dialog, quindi anche a login riuscito la finestra restava aperta (ma poiché l'EDT era stato liberato, l'utente poteva chiuderla manualmente).

### 2.3 La soluzione: `SwingWorker`

`SwingWorker` è il pattern standard Java per spostare lavoro pesante fuori dall'EDT e riportare i risultati sull'EDT in modo thread-safe:

```java
// Login.java — NUOVO
private void submit() {
    final String uid = ID.getText();
    final String pwd = new String(PWD.getPassword());

    btnLogin.setEnabled(false);
    setTitle("Authenticating...");

    new SwingWorker<Response, Void>() {
        @Override
        protected Response doInBackground() throws Exception {
            sm = new ClientSecurityManager(oauth);
            oauth.setCredentials(uid, pwd);
            return sm.refreshToken();          // ESEGUITO SU THREAD DI BACKGROUND
        }

        @Override
        protected void done() {
            btnLogin.setEnabled(true);         // RIABILITA IL PULSANTE SULL'EDT
            try {
                Response ret = get();          // PRELEVA IL RISULTATO (thread-safe)
                if (ret.isError()) {
                    logger.error(ret);
                    setTitle("Wrong credentials");
                    m_listener.onLoginError((ErrorResponse) ret);
                    return;                    // DIALOG RESTA APERTO PER RIPROVARE
                }

                if (chckRemeberMe.isSelected()) oauth.storeProperties();
                m_listener.onLogin(uid);       // NOTIFICA LA DASHBOARD
                dispose();                     // CHIUDE IL DIALOG MODALE
            } catch (Exception e) {
                logger.error(e.getMessage());
                m_listener.onLoginError(new ErrorResponse(401, "not_authorized", e.getMessage()));
                setTitle("Wrong credentials");
            }
        }
    }.execute();
}
```

**Flusso thread-safe**:

| Passo | Thread | Azione |
|---|---|---|
| `submit()` chiamato | EDT | Disabilita bottone, cambia titolo |
| `doInBackground()` | Worker thread | Esegue handshake TLS + HTTP POST |
| `done()` | EDT | Legge risultato, notifica listener, chiude dialog |

**Perché `done()` è su EDT e non su worker**: `SwingWorker.done()` viene eseguito sull'EDT per contratto. Questo significa che possiamo chiamare `setTitle()`, `dispose()`, `onLogin()` senza `SwingUtilities.invokeLater()` — siamo già sul thread giusto.

**Il `dispose()` ora presente** chiude il dialog modale, sbloccando l'esecuzione della Dashboard che era in attesa nel chiamante.

### 2.4 Difesa contro il bypass: `onLoginClose()` e `DO_NOTHING_ON_CLOSE`

Anche con il dialog modale, c'era una falla: la `WindowAdapter` sul dialog chiamava `m_listener.onLoginClose()` quando l'utente cliccava la X. L'implementazione originale di `onLoginClose()` nella Dashboard era **vuota**:

```java
// Dashboard.java — VECCHIO
@Override
public void onLoginClose() {
    // VUOTO — la Dashboard proseguiva senza autenticazione
}
```

**Dopo**:

```java
// Dashboard.java — NUOVO
@Override
public void onLoginClose() {
    logger.warn("Login dialog closed without authentication. Exiting.");
    System.exit(0);
}
```

Combinato con `setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)` nel costruttore di `Login`, la X del dialog non fa più nulla da sola — è il `WindowAdapter` che, ricevuto l'evento, chiama `onLoginClose()`. A quel punto `System.exit(0)` termina la JVM, impedendo l'accesso non autenticato.

### 2.5 Il payload HTTP verso Zitadel

La richiesta token segue OAuth 2.0 `client_credentials` grant con Zitadel.

**Prima**, il corpo era una stringa hardcodata, senza `scope`, senza URL-encoding sulle credential:

```java
// KeycloakAuthenticationService.java — VECCHIO
StringEntity body = new StringEntity("grant_type=client_credentials");
httpRequest.setEntity(body);
httpRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");
httpRequest.setHeader("Authorization", authorization);  // parametro esterno
```

Il parametro `authorization` veniva passato dal chiamante, che costruiva l'header Basic con credenziali _senza URL-encoding_. Zitadel (e la maggior parte degli OIDC provider) si aspetta che i caratteri speciali in `client_id` e `client_secret` siano URL-encoded **prima** di essere concatenati e codificati in Base64.

**Dopo**, il payload è costruito correttamente:

```java
// KeycloakAuthenticationService.java — NUOVO
// 1. Body con grant_type e scope
List<NameValuePair> params = new ArrayList<NameValuePair>();
params.add(new BasicNameValuePair("grant_type", "client_credentials"));
params.add(new BasicNameValuePair("scope", "openid profile"));
UrlEncodedFormEntity body = new UrlEncodedFormEntity(params, Charset.forName("UTF-8"));
httpRequest.setEntity(body);
httpRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");

// 2. Basic Auth con URL-encoding prima del Base64
String clientId = java.net.URLEncoder.encode(oauthProperties.getClientId(), "UTF-8");
String clientSecret = java.net.URLEncoder.encode(oauthProperties.getClientSecret(), "UTF-8");
String authString = clientId + ":" + clientSecret;
String encodedAuth = "Basic " + java.util.Base64.getEncoder()
    .encodeToString(authString.getBytes(Charset.forName("UTF-8")));

// 3. L'header Authorization ora contiene credenziali URL-encoded
httpRequest.setHeader("Authorization", encodedAuth);
```

**Perché `scope=openid profile` è necessario**: Zitadel richiede almeno lo scope `openid` per emettere un token JWT con i claim OIDC standard. Senza, il token potrebbe non contenere `preferred_username`, `username` o `client_id` — che sono i claim usati dall'engine per identificare l'utente (vedi Sezione 4).

**Perché l'URL-encoding è necessario**: un `client_secret` generato da Zitadel può contenere caratteri come `+`, `/`, `=`. Se questi vengono messi direttamente nella stringa `client_id:client_secret` e poi codificati in Base64, il server riceve una stringa con caratteri non validi per l'header HTTP Basic. L'URL-encoding prima del Base64 garantisce che tutti i caratteri siano safe.

### 2.6 `registerClient()` stubato

**Prima**, `registerClient()` faceva una POST a Keycloak per creare dinamicamente un client con `oidc-hardcoded-claim-mapper`:

```java
// KeycloakAuthenticationService.java — VECCHIO (riassunto)
public Response registerClient(...) {
    // POST a Keycloak con initialAccessToken
    // Crea client con serviceAccountsEnabled=true
    // Aggiunge protocolMapper per il claim "username"
    // Restituisce RegistrationResponse con client_id e secret
}
```

Questo non serve più perché Zitadel non ha un endpoint di registrazione dinamica compatibile. Il client va creato manualmente nella Zitadel Console (o via API Zitadel, che però richiederebbe un service account admin).

**Dopo**:

```java
// KeycloakAuthenticationService.java — NUOVO
public Response registerClient(String client_id, String username,
        String initialAccessToken, int timeout) throws SEPASecurityException {
    return new ErrorResponse(501, "not_supported",
        "Dynamic client registration disabled. Configure client manually in Zitadel.");
}
```

Il codice 501 (`NOT_IMPLEMENTED`) comunica chiaramente al chiamante che questa funzionalità non è disponibile. Il chiamante (tipicamente `Register.java` nella Dashboard) mostra un dialog di errore e guida l'operatore a configurare il client manualmente.

### 2.7 `JWTResponse` reso null-safe

**Prima**, il costruttore `JWTResponse(JsonObject)` faceva accesso diretto ai campi senza verificare `isJsonNull()`:

```java
// JWTResponse.java — VECCHIO
public JWTResponse(JsonObject json) {
    super();
    this.json = json;
    // NESSUN extraction — accesso diretto ai campi JSON
}
```

Questo causava `NullPointerException` se Zitadel ometteva campi opzionali come `refresh_token`, `session_state`, `scope`, `refresh_expires_in`, `not-before-policy` — tutti assenti nel flusso `client_credentials`.

**Dopo**, ogni campo è estratto con guardia `has()` + `!isJsonNull()`:

```java
// JWTResponse.java — NUOVO
accessToken = json.get("access_token") != null
    && !json.get("access_token").isJsonNull()
        ? json.get("access_token").getAsString() : null;
// ... analogo per tokenType, expiresIn ...
if (json.has("refresh_token") && !json.get("refresh_token").isJsonNull()) {
    refreshToken = json.get("refresh_token").getAsString();
}
// ... sessionState, scope, refreshExpiresIn, notBeforePolicy ...
```

---

## 3. La Persistenza (Il bug silente)

### 3.1 Il problema a due strati

`OAuthProperties.storeProperties()` serve a persistere le credenziali dopo un login riuscito — in particolare il JWT, il `client_secret` e l'`expires`. La Dashboard lo chiama quando l'utente spunta "Remember me".

**Bug #1: `propertiesFile` mai inizializzato**

`OAuthProperties` ha un campo `private File propertiesFile;` (riga 88) che non veniva mai assegnato. Né nel costruttore, né tramite setter. `storeProperties()` controllava questo campo:

```java
// OAuthProperties.java — VECCHIO
if (propertiesFile == null) {
    Logging.warn("Cannot store OAuth properties: propertiesFile is null. Skipping save.");
    return;
}
```

Questo controllo era **l'unica cosa che impediva un NPE**, ma trasformava il bug in un **fallimento silenzioso**: il metodo usciva con un warning nei log che nessuno vedeva. Le credenziali non venivano mai scritte su disco, e al riavvio successivo l'utente doveva reinserire tutto da capo.

**Bug #2: sovrascrittura totale del JSAP**

Anche ammesso che `propertiesFile` fosse impostato, il vecchio `storeProperties()` distruggeva il file:

```java
// OAuthProperties.java — VECCHIO (logica)
jsap.add("oauth", new JsonObject());      // Crea un JsonObject standalone
jsap.getAsJsonObject("oauth").add("enable", new JsonPrimitive(enabled));
// ... aggiunge ssl, jks, registration, authentication, provider ...
FileWriter out = new FileWriter(propertiesFile);
out.write(jsap.toString());               // SCRIVE SOLO IL BLOCCO OAUTH
out.close();
```

Il `jsap` qui è un `JsonObject` creato da zero nel metodo stesso (contiene solo il blocco `"oauth"`). Quando lo si scrive su disco, **sovrascrive l'intero file JSAP**, cancellando `host`, `sparql11protocol`, `sparql11seprotocol`, `namespaces`, `queries`, `updates` — tutto ciò che rende il JSAP funzionante. Al riavvio, il file sarebbe un JSON contenente solo `"oauth": { ... }` — e la Dashboard non saprebbe più a quale host connettersi.

### 3.2 La soluzione: Read-Merge-Write

**Inizializzazione di `propertiesFile`** nel costruttore:

```java
// OAuthProperties.java — NUOVO (linea 106)
this.propertiesFile = new File(jsap.getBaseUri());
```

`jsap.getBaseUri()` restituisce l'`URI` del file JSAP da cui l'oggetto è stato caricato (ereditato da `SPARQL11Properties.baseUri`). Convertito in `File`, ora `storeProperties()` sa esattamente dove scrivere.

**Pattern Read-Merge-Write**:

```java
// OAuthProperties.java — NUOVO (linea 262)
public synchronized void storeProperties() throws SEPAPropertiesException, SEPASecurityException {
    if (propertiesFile == null) {
        Logging.warn("Cannot store OAuth properties: propertiesFile is null. Skipping save.");
        return;
    }

    // STEP 1: LEGGI il file JSAP completo da disco
    JsonObject fullJsap;
    try (FileReader reader = new FileReader(propertiesFile)) {
        fullJsap = new Gson().fromJson(reader, JsonObject.class);
    } catch (IOException e) {
        throw new SEPAPropertiesException("Failed to read " + propertiesFile.getPath());
    }

    if (fullJsap == null) fullJsap = new JsonObject();

    // STEP 2: COSTRUISCI SOLO il blocco oauth (non l'intero file)
    JsonObject oauthBlock = new JsonObject();
    oauthBlock.add("enable", new JsonPrimitive(enabled));
    // ... ssl, jks, registration, authentication, provider ...
    oauthBlock.add("provider", new JsonPrimitive("zitadel"));

    // STEP 3: MERGE — sostituisci solo il blocco "oauth" nel JSON completo
    fullJsap.add("oauth", oauthBlock);

    // STEP 4: SCRIVI l'intero JSON su disco
    try (FileWriter out = new FileWriter(propertiesFile)) {
        out.write(fullJsap.toString());
    }
}
```

**Perché Read-Merge-Write**:

- `fullJsap.add("oauth", oauthBlock)` su Gson **sostituisce** la chiave se esiste già (comportamento di `JsonObject`). Quindi il blocco `"oauth"` viene aggiornato, ma tutto il resto (`host`, `sparql11protocol`, `namespaces`, ...) rimane intatto.
- La scrittura finale preserva l'intero file JSON, non solo il blocco oauth.
- `synchronized` impedisce race condition se due thread tentano di scrivere contemporaneamente.

### 3.3 Il supporto per credenziali in chiaro: `decryptOrDefault()`

Zitadel non ha un meccanismo di cifratura automatica dei `client_secret` — quello che restituisce è già il segreto finale. Il vecchio codice chiamava `encryption.decrypt()` su ogni campo (`client_id`, `client_secret`, `jwt`, `expires`, `type`) dando per scontato che fossero cifrati:

```java
// OAuthProperties.java — VECCHIO
clientId = encryption.decrypt(auth.get("client_id").getAsString());  // ESPLODE se in chiaro
```

Se il valore nel JSAP è in chiaro (perché il deployer lo ha scritto a mano), `encryption.decrypt()` lancia `SEPASecurityException` e il costruttore fallisce.

**Dopo**, due helper privati gestiscono il fallback trasparente:

```java
// OAuthProperties.java — NUOVO (linea 373)
private String decryptOrDefault(String value) {
    try {
        return encryption.decrypt(value);   // Tenta decrypt
    } catch (SEPASecurityException e) {
        return value;                       // Fallback: usa il valore in chiaro
    }
}

private long decryptLongOrDefault(String value) {
    try {
        String decrypted = encryption.decrypt(value);
        return Long.parseLong(decrypted);
    } catch (Exception e) {
        return Long.parseLong(value);       // Fallback per long
    }
}
```

**Quando il valore è cifrato** (scritto da `storeProperties()` in un'esecuzione precedente), `decrypt()` riesce e restituisce il valore decifrato.
**Quando il valore è in chiaro** (scritto a mano nel JSAP dall'operatore), `decrypt()` fallisce e il valore viene usato così com'è.

Questo permette un flusso ibrido: la prima configurazione può essere fatta manualmente con valori in chiaro; dopo il primo login, `storeProperties()` li cifra e li persiste; al riavvio successivo, vengono decifrati normalmente.

---

## 4. La Validazione lato Server (Engine)

### 4.1 Il contesto: cosa succede dopo il login

Dopo che il Client ha ottenuto il token JWT da Zitadel, lo allega a ogni richiesta SPARQL come header `Authorization: Bearer <jwt>`. L'Engine riceve la richiesta, il `SecureSPARQL11Handler` estrae il JWT e chiama `Dependability.validateToken(jwt)`, che delega al `SecurityManager` configurato.

Quando l'Engine è configurato con `"type": "zitadel"` nel `engine.jpar`, `EngineProperties.setSecurity()` instanzia `KeyCloakSecurityManager` (il nome della classe è storico — non è stato rinominato per non creare un effetto domino sui riferimenti). La validazione avviene in `KeyCloakSecurityManager.validateToken()`.

### 4.2 Rimozione del coupling con LDAP e Virtuoso

**Prima**, il costruttore di `KeyCloakSecurityManager` aveva un coupling pesante con LDAP e Virtuoso:

```java
// KeyCloakSecurityManager.java — VECCHIO
private SyncLdap ldap;
private VirtuosoIsql isql;

public KeyCloakSecurityManager(SSLContext ssl, RSAKey key,
        LdapProperties prop, IsqlProperties isqlprop)
        throws SEPASecurityException {
    super(ssl, key, false);
    ldap = new SyncLdap(prop);
    isql = new VirtuosoIsql(isqlprop, ldap.getEndpointUsersPassword());
    new UsersSync(ldap, isql);                     // Thread ogni 5 secondi
    Logging.log("oauth", "EndpointUsersPassword: "
        + ldap.getEndpointUsersPassword());
}
```

`SyncLdap` cercava un utente LDAP per ottenere la password condivisa dell'endpoint SPARQL. `VirtuosoIsql` eseguiva comandi `isql` per sincronizzare gli utenti LDAP con gli ACL di Virtuoso. `UsersSync` era un thread periodico (ogni 5 secondi) che manteneva la sincronizzazione.

Con Zitadel, **non esiste un LDAP aziendale** da interrogare. La password dell'endpoint SPARQL non può essere recuperata da LDAP, e la sincronizzazione utenti LDAP→Virtuoso non è applicabile.

**Dopo**, il costruttore è pulito:

```java
// KeyCloakSecurityManager.java — NUOVO
private static final String MOCK_PASSWORD = "MOCK_PASSWORD_123";

public KeyCloakSecurityManager(SSLContext ssl, RSAKey key,
        LdapProperties prop, IsqlProperties isqlprop)
        throws SEPASecurityException {
    super(ssl, key, false);
    // Nessun LDAP, nessun Virtuoso, nessun thread di sync
}
```

La firma del costruttore accetta ancora `LdapProperties` e `IsqlProperties` (per retrocompatibilità con `Dependability` e `EngineProperties`), ma li ignora completamente.

La password dell'endpoint SPARQL è ora una costante `MOCK_PASSWORD_123`. Questo è un **placeholder intenzionale**: in produzione, l'engine tipicamente usa Jena in-memory (che non richiede autenticazione), oppure un Virtuoso con password configurata staticamente. Il valore mock serve a non bloccare lo sviluppo e i test; chi deploya in produzione con un backend SPARQL autenticato deve sostituire questa costante con il meccanismo appropriato (es. variabile d'ambiente, file di configurazione).

### 4.3 Il fallback a cascata sui claim

Questo è il cuore della validazione Zitadel. L'Engine riceve un JWT firmato da Zitadel. Deve estrarre un'identità utente per:
1. Tracciare chi sta facendo la richiesta (log, audit)
2. Ottenere le credenziali dell'endpoint SPARQL (username + password) per inoltrare la query al backend

**Prima**, il claim cercato era `username` (hardcodato dal mapper Keycloak), con fallback a `preferred_username`:

```java
// KeyCloakSecurityManager.java — VECCHIO
uid = claimsSet.getStringClaim("username");
if (uid == null) {
    uid = claimsSet.getStringClaim("preferred_username");
    if (uid == null) {
        return new ClientAuthorization("invalid_grant",
            "Username claim not found");
    }
}
```

Con Zitadel e il flusso `client_credentials`, i service user **non hanno** `preferred_username` né `username`. Hanno invece `client_id` — l'identificatore del client OAuth che ha richiesto il token.

**Dopo**, la cascata è tripla, con priorità invertita per robustezza:

```java
// KeyCloakSecurityManager.java — NUOVO
uid = claimsSet.getStringClaim("preferred_username");   // 1. Standard OIDC
if (uid == null) {
    uid = claimsSet.getStringClaim("username");         // 2. Claim custom
    if (uid == null) {
        uid = claimsSet.getStringClaim("client_id");    // 3. Fallback Zitadel
        if (uid == null) {
            return new ClientAuthorization("invalid_grant",
                "User identity claim not found");
        }
    }
}
```

**Perché l'ordine è `preferred_username` → `username` → `client_id`**:

- `preferred_username` è lo standard OIDC per il nome utente human-readable. Se presente (es. in scenari ibridi dove alcuni utenti hanno username), è il più significativo.
- `username` è un claim custom che Keycloak inseriva tramite `oidc-hardcoded-claim-mapper`. Rimane per retrocompatibilità con vecchi token Keycloak.
- `client_id` è il fallback finale per i service user Zitadel. È garantito essere presente in ogni token `client_credentials`, perché Zitadel lo include sempre.

**La modifica è backward-compatible**: token Keycloak esistenti (con `username` o `preferred_username`) continuano a funzionare. Nuovi token Zitadel (con solo `client_id`) funzionano grazie al terzo livello di fallback.

### 4.4 `getEndpointCredentials()` con password fissa

**Prima**, la password veniva dal LDAP:

```java
// KeyCloakSecurityManager.java — VECCHIO
public Credentials getEndpointCredentials(String uid) throws SEPASecurityException {
    return new Credentials(uid, ldap.getEndpointUsersPassword());
}
```

**Dopo**, usa la costante:

```java
// KeyCloakSecurityManager.java — NUOVO (linea 202)
public Credentials getEndpointCredentials(String uid) throws SEPASecurityException {
    return new Credentials(uid, MOCK_PASSWORD);
}
```

Le `Credentials` restituite hanno `username = uid` (il claim estratto) e `password = "MOCK_PASSWORD_123"`. Queste credenziali vengono usate dal `ClientAuthorization` per costruire l'header `Authorization: Basic` verso il backend SPARQL.

### 4.5 L'aggancio in `EngineProperties`

**Prima**, `setSecurity()` gestiva solo i tipi `local`, `ldap` e `keycloak`:

```java
// EngineProperties.java — VECCHIO
if (isLocalEnabled()) {
    Dependability.enableLocalSecurity(ssl, jwt);
} else if (isLDAPEnabled()) {
    Dependability.enableLDAPSecurity(ssl, jwt, ldap);
} else if (isKeycìCloakEnabled()) {
    Dependability.enableKeyCloakSecurity(ssl, jwt, ldap, isql);
}
```

(Notare il typo `isKeycìCloakEnabled` — un `ì` al posto di `i`, presente nel codice originale.)

**Dopo**, è stato aggiunto il caso `zitadel`:

```java
// EngineProperties.java — NUOVO
} else if (isZitadelEnabled()) {
    Dependability.enableKeyCloakSecurity(ssl, jwt, ldap, isql);
}
```

```java
// EngineProperties.java — NUOVO (linea 846)
public boolean isZitadelEnabled() {
    return this.parameters.gates.security.type.equals("zitadel");
}
```

**Perché `enableKeyCloakSecurity` e non un nuovo `enableZitadelSecurity`**: la classe `KeyCloakSecurityManager` è ora **shared** tra Keycloak e Zitadel — gli stessi metodi `validateToken`, `getEndpointCredentials` funzionano per entrambi i provider grazie ai fallback multipli sui claim. Creare un nuovo metodo factory in `Dependability` sarebbe stato codice duplicato senza valore aggiunto. La separazione logica avviene a livello di `engine.jpar` (`"type": "zitadel"` vs `"type": "keycloak"`), non a livello di classe Java.

### 4.6 Configurazione esempio: `engine.jpar` per Zitadel

```json
{
  "parameters": {
    "gates": {
      "security": {
        "enabled": true,
        "type": "zitadel",
        "tls": false
      },
      "ports": { "http": 8000, "ws": 9000 },
      "paths": {
        "query": "/query",
        "update": "/update",
        "subscribe": "/subscribe",
        "register": "/oauth/register",
        "tokenRequest": "/oauth/token"
      }
    }
  }
}
```

Il campo `"type": "zitadel"` attiva il nuovo ramo in `setSecurity()` e istanzia `KeyCloakSecurityManager` con i fallback Zitadel-ready.

---

## Riepilogo del Flusso Completo

```
┌─────────────────────────────────────────────────────────────┐
│ 1. AVVIO                                                    │
│    Dashboard carica localhost.jsap                          │
│    Gson → JSAP.oauth = JsonObject (PRIMA: campo inesistente)│
│    isSecure() → true (PRIMA: false hardcodato)              │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ 2. LOGIN (UI)                                               │
│    JSAP.getAuthenticationProperties() → OAuthProperties     │
│    (PRIMA: null)                                            │
│    Login dialog modale + DO_NOTHING_ON_CLOSE                │
│    SwingWorker.doInBackground() → HTTP POST a Zitadel       │
│    (PRIMA: bloccante su EDT, UI freezata)                   │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ 3. RICHIESTA TOKEN                                          │
│    POST /oauth/v2/token                                     │
│    Body: grant_type=client_credentials&scope=openid profile │
│    Auth: Basic <URL-encoded(client_id:client_secret)>       │
│    (PRIMA: StringEntity senza scope, credential non encod.) │
│    Risposta → JWTResponse (campi null-safe)                 │
│    Token memorizzato in OAuthProperties.jwt                 │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ 4. PERSISTENZA                                              │
│    storeProperties() → Read-Merge-Write                     │
│    (PRIMA: propertiesFile=null, sovrascriveva tutto)        │
│    Legge JSAP da disco, sostituisce solo blocco "oauth",    │
│    riscrive file completo. Credenziali cifrate per          │
│    il riavvio successivo.                                   │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ 5. RICHIESTA SPARQL                                         │
│    Client → Engine: Authorization: Bearer <jwt>             │
│    SecureSPARQL11Handler estrae JWT                         │
│    Dependability.validateToken(jwt)                         │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ 6. VALIDAZIONE ENGINE                                       │
│    KeyCloakSecurityManager.validateToken()                  │
│    Verifica firma RS256 con nimbus-jose-jwt                 │
│    Estrae claim: preferred_username → username → client_id  │
│    (PRIMA: solo username → preferred_username)              │
│    getEndpointCredentials() → MOCK_PASSWORD_123              │
│    (PRIMA: ldap.getEndpointUsersPassword())                 │
│    Restituisce ClientAuthorization con credenziali SPARQL   │
└─────────────────────────────────────────────────────────────┘
```
