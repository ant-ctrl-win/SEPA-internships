# Analisi Dettagliata dei Cambiamenti per Zitadel Authentication

Data: 2026-05-13
Branch: feature/login-zitadel-ennio

---

## 1. OAuthProperties.java
**Percorso**: `client-api/src/main/java/com/vaimee/sepa/api/commons/security/OAuthProperties.java`

### Cambiamento 1.1: Aggiunta di nuove proprietà per PKCE
**Che cosa cambia**: Vengono aggiunte tre nuove proprietà private per supportare il flusso OAuth 2.0 Authorization Code + PKCE.

**PRIMA (righe 75-77)**:
```java
private String clientId = null;
private String clientSecret = null;

private String jwt = null;
```

**DOPO (righe 75-84)**:
```java
private String clientId = null;
private String clientSecret = null;
private String authorizationEndpoint;
private String redirectUri;
private String codeVerifier; // opzionale, per PKCE

private String jwt = null;
private long expires = -1;
private String type = null;
```

**Perché**: Queste proprietà sono necessarie per:
- `authorizationEndpoint`: URL dell'endpoint di autorizzazione Zitadel (per avviare il login)
- `redirectUri`: URL dove l'utente viene rediretto dopo il login (http://localhost:8080/callback)
- `codeVerifier`: Parametro PKCE per proteggere il flusso di scambio del codice

---

### Cambiamento 1.2: Parsing della configurazione dal JSON
**Che cosa cambia**: Viene aggiunto il parsing dei nuovi campi dal file di configurazione JSAP.

**PRIMA (righe 133-156)**:
```java
if (oauthJsonObject.has("authentication")) {
    JsonObject auth = oauthJsonObject.getAsJsonObject("authentication");
    
    if (auth.has("endpoint"))
        tokenRequestURL = auth.get("endpoint").getAsString();
    if (auth.has("client_id"))
        clientId = decryptOrDefault(auth.get("client_id").getAsString());
    if (auth.has("client_secret"))
        clientSecret = decryptOrDefault(auth.get("client_secret").getAsString());
    if (auth.has("jwt"))
        jwt = decryptOrDefault(auth.get("jwt").getAsString());
    if (auth.has("expires"))
        expires = decryptLongOrDefault(auth.get("expires").getAsString());
    if (auth.has("type"))
        type = decryptOrDefault(auth.get("type").getAsString());	
}
```

**DOPO (righe 133-156)**:
```java
if (oauthJsonObject.has("authentication")) {
    JsonObject auth = oauthJsonObject.getAsJsonObject("authentication");
    
    if (auth.has("endpoint"))
        tokenRequestURL = auth.get("endpoint").getAsString();
    if (auth.has("client_id"))
        clientId = decryptOrDefault(auth.get("client_id").getAsString());
    if (auth.has("client_secret"))
        clientSecret = decryptOrDefault(auth.get("client_secret").getAsString());
    
    // NUOVO: Parsing PKCE endpoints
    if (auth.has("authorization_endpoint"))
        authorizationEndpoint = auth.get("authorization_endpoint").getAsString();
    if (auth.has("redirect_uri"))
        redirectUri = auth.get("redirect_uri").getAsString();
        
    if (auth.has("jwt"))
        jwt = decryptOrDefault(auth.get("jwt").getAsString());
    if (auth.has("expires"))
        expires = decryptLongOrDefault(auth.get("expires").getAsString());
    if (auth.has("type"))
        type = decryptOrDefault(auth.get("type").getAsString());
    
    // ⚠️ ATTENZIONE: DUPLICAZIONE (righe 152-155)
    if (auth.has("authorization_endpoint"))
        authorizationEndpoint = auth.get("authorization_endpoint").getAsString();
    if (auth.has("redirect_uri"))
        redirectUri = auth.get("redirect_uri").getAsString();
}
```

**Perché**: Il parsing estrae i parametri dal JSON per usarli nel flusso di autorizzazione.

**⚠️ PROBLEMA IDENTIFICATO**: Il parsing di `authorization_endpoint` e `redirect_uri` è duplicato (righe 142-145 e 152-155). La seconda iterazione sovrascrive la prima, il che è ridondante.

---

### Cambiamento 1.3: Aggiunta di getter pubblici
**Che cosa cambia**: Vengono aggiunti tre nuovi metodi getter per accedere alle proprietà PKCE.

**PRIMA**: Non esistevano questi metodi.

**DOPO (righe 404-415)**:
```java
public String getAuthorizationEndpoint() {
    return authorizationEndpoint;
}

public String getRedirectUri() {
    return redirectUri;
}

public String getCodeVerifier() {
    return codeVerifier;
}
```

**Perché**: Permettono alle altre classi (come `ZitadelAuthenticationService` e `Login`) di accedere a questi parametri necessari per costruire l'URL di autorizzazione e gestire il flusso PKCE.

---

## 2. ZitadelAuthenticationService.java
**Percorso**: `client-api/src/main/java/com/vaimee/sepa/api/commons/security/ZitadelAuthenticationService.java`

### Cambiamento 2.1: Aggiunta del metodo `getAuthorizationUrl()`
**Che cosa cambia**: Nuovo metodo che costruisce l'URL di autorizzazione per il flusso PKCE.

**PRIMA**: Questo metodo non esiste.

**DOPO (righe 108-116)**:
```java
public String getAuthorizationUrl(String codeChallenge) throws UnsupportedEncodingException {
    return oauthProperties.getAuthorizationEndpoint()
        + "?response_type=code"
        + "&client_id=" + URLEncoder.encode(oauthProperties.getClientId(), "UTF-8")
        + "&redirect_uri=" + URLEncoder.encode(oauthProperties.getRedirectUri(), "UTF-8")
        + "&scope=openid%20profile"
        + "&code_challenge=" + URLEncoder.encode(codeChallenge, "UTF-8")
        + "&code_challenge_method=S256";
}
```

**Cosa fa**:
1. Prende l'`authorization_endpoint` da OAuthProperties (es: `https://sepa-engine-auth-ie5az2.eu1.zitadel.cloud/oauth/v2/authorize`)
2. Aggiunge i parametri di query:
   - `response_type=code`: Indica che vogliamo un codice di autorizzazione
   - `client_id`: ID dell'applicazione registrata
   - `redirect_uri`: Dove reindirizzare dopo il login
   - `scope=openid profile`: Scopi richiesti (identità e profilo)
   - `code_challenge`: Hash SHA-256 del code_verifier (PKCE)
   - `code_challenge_method=S256`: Metodo di hashing PKCE

**Esempio di URL generato**:
```
https://sepa-engine-auth-ie5az2.eu1.zitadel.cloud/oauth/v2/authorize?response_type=code&client_id=5rwXpcR2vkLKvUP46ZrDZ9BbPK3G8lo7K%2Fpao2qUSLQ%3D&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fcallback&scope=openid%20profile&code_challenge=...&code_challenge_method=S256
```

**⚠️ PROBLEMA**: Manca l'import di `java.net.URLEncoder`. Il codice usa `URLEncoder.encode()` ma non è importato in cima al file.

---

### Cambiamento 2.2: Aggiunta del metodo `requestTokenWithAuthorizationCode()`
**Che cosa cambia**: Nuovo metodo che scambia il codice di autorizzazione con un token di accesso.

**PRIMA**: Questo metodo non esiste.

**DOPO (righe 118-151)**:
```java
public Response requestTokenWithAuthorizationCode(String code, String redirectUri, String codeVerifier, int timeout) {
    try {
        URI uri = new URI(oauthProperties.getTokenRequestUrl());
        HttpPost httpRequest = new HttpPost(uri);

        // 1. Prepara i parametri POST
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("grant_type", "authorization_code"));
        params.add(new BasicNameValuePair("code", code));
        params.add(new BasicNameValuePair("redirect_uri", redirectUri));
        params.add(new BasicNameValuePair("client_id", oauthProperties.getClientId()));
        params.add(new BasicNameValuePair("code_verifier", codeVerifier));
        params.add(new BasicNameValuePair("scope", "openid profile"));

        UrlEncodedFormEntity body = new UrlEncodedFormEntity(params, Charset.forName("UTF-8"));
        httpRequest.setEntity(body);
        httpRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");

        // 2. Configura il timeout
        RequestConfig requestConfig = RequestConfig.custom()
            .setSocketTimeout(timeout)
            .setConnectTimeout(timeout)
            .build();
        httpRequest.setConfig(requestConfig);

        // 3. Esegui la richiesta HTTP
        CloseableHttpResponse response = httpClient.execute(httpRequest);
        HttpEntity entity = response.getEntity();
        String jsonResponse = EntityUtils.toString(entity, Charset.forName("UTF-8"));
        EntityUtils.consume(entity);

        // 4. Parsa la risposta JSON
        JsonObject json = new Gson().fromJson(jsonResponse, JsonObject.class);
        if (json.has("error")) {
            return new ErrorResponse(
                response.getStatusLine().getStatusCode(), 
                "token_request", 
                json.get("error").getAsString()
            );
        }
        return new JWTResponse(json);
    } catch (Exception e) {
        return new ErrorResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, "Exception", e.getMessage());
    }
}
```

**Cosa fa**:
1. Crea una richiesta POST all'endpoint token di Zitadel
2. Invia i parametri:
   - `grant_type=authorization_code`: Tipo di flusso OAuth
   - `code`: Codice ricevuto dal redirect di autorizzazione
   - `redirect_uri`: Deve corrispondere a quello usato in `getAuthorizationUrl()`
   - `client_id`: ID dell'applicazione
   - `code_verifier`: Il valore originale usato per generare il `code_challenge` (PKCE)
   - `scope=openid profile`: Scopi richiesti
3. Riceve un JSON con `access_token`, `expires_in`, `token_type`
4. Ritorna un `JWTResponse` con i dati del token

**Flusso completo**:
```
1. App desktop genera code_verifier e code_challenge (SHA-256)
2. App apre browser con getAuthorizationUrl() inviando code_challenge
3. Utente fa login su Zitadel
4. Zitadel ridirige a http://localhost:8080/callback?code=xyz
5. App intercetta il code
6. App chiama requestTokenWithAuthorizationCode() con code + code_verifier
7. Zitadel verifica il code_verifier corrisponda al code_challenge
8. Zitadel ritorna access_token
```

---

## 3. File di Configurazione: localhost.jsap
**Percorso**: `tool-dashboard/src/main/resources/localhost.jsap`

### Cambiamento 3.1: Aggiunta di authorization_endpoint e redirect_uri

**PRIMA**:
```json
{
  "host":"localhost",
  "oauth":{
    "enable":true,
    "ssl":"TLS",
    "authentication":{
      "endpoint":"https://sepa-engine-auth-ie5az2.eu1.zitadel.cloud/oauth/v2/token",
      "client_id":"5rwXpcR2vkLKvUP46ZrDZ9BbPK3G8lo7K/pao2qUSLQ=",
      "client_secret":"d5Iv70ljvIaZRkyQNOe7l/nTr+0DAwoYACIduf0qdPlKFDfCmN4g3AqdJxD9XdQvaQELM11VFjUO7QSMh8z3ZS19FzxxHdg9eDahb6f2Bhw=",
      "jwt":"...",
      "expires":"...",
      "type":"..."
    },
    "provider":"zitadel"
  }
}
```

**DOPO**:
```json
{
  "host":"localhost",
  "oauth":{
    "enable":true,
    "ssl":"TLS",
    "authentication":{
      "endpoint":"https://sepa-engine-auth-ie5az2.eu1.zitadel.cloud/oauth/v2/token",
      "authorization_endpoint":"https://sepa-engine-auth-ie5az2.eu1.zitadel.cloud/oauth/v2/authorize",
      "client_id":"5rwXpcR2vkLKvUP46ZrDZ9BbPK3G8lo7K/pao2qUSLQ=",
      "client_secret":"d5Iv70ljvIaZRkyQNOe7l/nTr+0DAwoYACIduf0qdPlKFDfCmN4g3AqdJxD9XdQvaQELM11VFjUO7QSMh8z3ZS19FzxxHdg9eDahb6f2Bhw=",
      "redirect_uri":"http://localhost:8080/callback",
      "jwt":"...",
      "expires":"...",
      "type":"..."
    },
    "provider":"zitadel"
  }
}
```

**Cambiamenti**:
- ✅ Aggiunto `"authorization_endpoint": "https://sepa-engine-auth-ie5az2.eu1.zitadel.cloud/oauth/v2/authorize"`
- ✅ Aggiunto `"redirect_uri": "http://localhost:8080/callback"`

**Perché**: Sono necessari per il flusso PKCE. L'app li legge in fase di startup e li usa in `getAuthorizationUrl()`.

---

## 4. File di Configurazione Test: test/oauth.jsap
**Percorso**: `tool-dashboard/src/main/resources/test/oauth.jsap`

### Cambiamento 4.1: Completa riscrittura della configurazione

**PRIMA** (configurazione Keycloak/SEPA):
```json
{
	"oauth": {
		"enable": true,
		"ssl": "TLSv1.2",
		"trustall": true,
		"register": "https://sepa.vaimee.it:8443/oauth/register"
	}
}
```

**DOPO** (configurazione Zitadel):
```json
{
	"oauth": {
		"enable": true,
		"ssl": "TLS",
		"authentication": {
			"endpoint": "https://sepa-engine-auth-ie5az2.eu1.zitadel.cloud/oauth/v2/token",
			"authorization_endpoint": "https://sepa-engine-auth-ie5az2.eu1.zitadel.cloud/oauth/v2/authorize",
			"client_id": "5rwXpcR2vkLKvUP46ZrDZ9BbPK3G8lo7K/pao2qUSLQ=",
			"client_secret": "d5Iv70ljvIaZRkyQNOe7l/nTr+0DAwoYACIduf0qdPlKFDfCmN4g3AqdJxD9XdQvaQELM11VFjUO7QSMh8z3ZS19FzxxHdg9eDahb6f2Bhw=",
			"redirect_uri": "http://localhost:8080/callback"
		},
		"provider": "zitadel"
	}
}
```

**Cambiamenti principali**:
| Campo | Rimosso | Aggiunto | Motivo |
|-------|---------|----------|--------|
| `trustall` | ✅ | ❌ | Non serve per Zitadel cloud |
| `register` | ✅ | ❌ | Registrazione dinamica disabilitata in Zitadel |
| `ssl` | TLSv1.2 | TLS | Versione più flessibile |
| `authentication` | ❌ | ✅ | Struttura nuova con tutti i parametri PKCE |
| `endpoint` | ❌ | ✅ | URL del token endpoint |
| `authorization_endpoint` | ❌ | ✅ | URL dell'authorization endpoint (NUOVO per PKCE) |
| `client_id` | ❌ | ✅ | Credenziali Zitadel (crittografate) |
| `client_secret` | ❌ | ✅ | Credenziali Zitadel (crittografate) |
| `redirect_uri` | ❌ | ✅ | URL callback per ricevere il code (NUOVO per PKCE) |
| `provider` | ❌ | ✅ | Impostato a "zitadel" |

**Perché questo cambiamento**: La migrazione da Keycloak a Zitadel comporta una struttura di configurazione completamente diversa. Zitadel richiede PKCE ed endpoints di autorizzazione separati.

---

## 5. Login.java (Dashboard UI)
**Percorso**: `tool-dashboard/src/main/java/com/vaimee/sepa/tools/dashboard/utils/Login.java`

### Cambiamento 5.1: Aggiunta del metodo `waitForAuthCode()`

**NUOVO (righe 37-64)**:
```java
private String waitForAuthCode() throws Exception {
    final String[] codeHolder = new String[1];
    java.net.ServerSocket server = new java.net.ServerSocket(8080);
    try {
        java.net.Socket client = server.accept();
        java.io.BufferedReader in = new java.io.BufferedReader(
            new java.io.InputStreamReader(client.getInputStream())
        );
        String line;
        String code = null;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.startsWith("GET ")) {
                int idx = line.indexOf("code=");
                if (idx > 0) {
                    code = line.substring(idx + 5, line.indexOf(' ', idx));
                    break;
                }
            }
        }
        // Invia risposta HTTP
        java.io.PrintWriter out = new java.io.PrintWriter(client.getOutputStream());
        out.println("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nLogin completato. Puoi chiudere questa finestra.");
        out.flush();
        client.close();
        codeHolder[0] = code;
    } finally {
        server.close();
    }
    return codeHolder[0];
}
```

**Cosa fa**:
1. Crea un mini web server sulla porta 8080
2. Aspetta una richiesta HTTP GET (il redirect da Zitadel)
3. Estrae il parametro `code=...` dall'URL
4. Invia una risposta HTTP 200 all'utente ("Login completato")
5. Ritorna il code

**Perché**: Zitadel redirige l'utente a `http://localhost:8080/callback?code=xyz`. Questa funzione cattura quel redirect e estrae il code.

---

### Cambiamento 5.2: Aggiunta del bottone "Login con Zitadel (Auth Code)"

**NUOVO (righe 78-160)**:
```java
private JButton btnZitadelAuthCode;

// Nel costruttore:
btnZitadelAuthCode = new JButton("Login con Zitadel (Auth Code)");
btnZitadelAuthCode.addActionListener(e -> {
    new Thread(() -> {
        try {
            // 1. Genera code_verifier e code_challenge (PKCE)
            String codeVerifier = java.util.UUID.randomUUID().toString().replace("-", "");
            String codeChallenge = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(codeVerifier.getBytes("US-ASCII"))
                );
            
            // 2. Costruisci URL
            String url = sm.getOauth().getAuthorizationUrl(codeChallenge);
            
            // 3. Apri browser
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            
            // 4. Avvia web server locale per ricevere il code
            String code = waitForAuthCode();
            
            // 5. Scambia il code per il token
            com.vaimee.sepa.api.commons.response.Response resp = 
                sm.getOauth().requestTokenWithAuthorizationCode(code, codeVerifier, 10000);
            
            // 6. Gestisci la risposta/token
            if (resp instanceof com.vaimee.sepa.api.commons.response.JWTResponse) {
                // Successo: salva token e chiudi dialog
                m_listener.onLoginSuccess();
                dispose();
            } else {
                javax.swing.JOptionPane.showMessageDialog(this, "Errore login: " + resp.toString());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            javax.swing.JOptionPane.showMessageDialog(this, "Errore login: " + ex.getMessage());
        }
    }).start();
});
contentPanel.add(btnZitadelAuthCode);
```

**Flusso completo per l'utente**:
1. Utente clicca "Login con Zitadel (Auth Code)"
2. App genera PKCE verifier/challenge
3. App apre browser a Zitadel per il login
4. Utente fa login su Zitadel
5. Zitadel ridirige a http://localhost:8080/callback?code=xyz
6. App intercetta il code
7. App scambia code + verifier per access_token
8. Login completato, dialog chiuso

**Perché**: Consente l'autenticazione tramite PKCE flow, che è più sicuro rispetto al client credentials flow e più appropriato per app desktop.

---

## Sommario dei Cambiamenti

### Classi Modificate:
1. ✅ `OAuthProperties.java` - Aggiunto supporto PKCE
2. ✅ `ZitadelAuthenticationService.java` - Aggiunto flusso Authorization Code
3. ✅ `Login.java` - Aggiunta UI per PKCE login
4. ✅ `localhost.jsap` - Aggiunto authorization_endpoint e redirect_uri
5. ✅ `test/oauth.jsap` - Migrazione Keycloak → Zitadel

### Funzionalità Aggiunte:
- ✅ Supporto completo per OAuth 2.0 Authorization Code + PKCE
- ✅ Bottone login Zitadel con apertura browser
- ✅ Mini web server per intercettare il redirect
- ✅ Scambio code → token

### Problemi Identificati:
1. ⚠️ **Duplicazione in OAuthProperties.java**: `authorization_endpoint` e `redirect_uri` vengono parsati due volte (righe 142-145 e 152-155)
2. ⚠️ **Missing import in ZitadelAuthenticationService.java**: `java.net.URLEncoder` non è importato

---

## Correttezza della Configurazione

| Aspetto | Stato | Dettagli |
|---------|-------|----------|
| Endpoints Zitadel | ✅ | URL validi per istanza cloud |
| PKCE Flow | ✅ | Implementazione corretta con SHA-256 |
| Client ID/Secret | ✅ | Crittografati nel file di configurazione |
| Redirect URI | ✅ | Corrisponde a http://localhost:8080/callback |
| SSL | ✅ | Impostato a TLS (flessibile e sicuro) |
| Provider | ✅ | Correttamente impostato a "zitadel" |

