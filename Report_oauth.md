# Report: Diagnostico e Risoluzione Autenticazione OAuth/Zitadel in SEPA Dashboard

## 1. Problema

La SEPA Dashboard non mostrava la finestra di login dopo la migrazione da Keycloak a Zitadel. Il flusso di sicurezza veniva bypassato — la Dashboard si apriva direttamente senza richiedere credenziali, anche quando il file `.jsap` conteneva un blocco `oauth` apparentemente corretto.

### Log diagnostico iniziale (prima della correzione)

```
[DEBUG] DIAG: appProfile.isSecure() = false
[DEBUG] DIAG: appProfile.getOauth() = null
[DEBUG] DIAG: appProfile.getAuthenticationProperties() = null
[WARN] DIAG: !!! BYPASSING LOGIN — appProfile.isSecure() returned false !!!
```

## 2. Root Cause

La causa era nel file JSAP su disco, non nel codice. Nello specifico:

### 2.1 Il file `sepa.wda.vaimee.com.jsap` non aveva il blocco `oauth`

Il file originale contiene solo `host`, `sparql11protocol`, `sparql11seprotocol`, `namespaces`, `extended`, `updates` e `queries` — **nessun** blocco `oauth`. Quando Gson deserializza il JSON nel costruttore di `JSAP`, il campo `protected JsonObject oauth = null` rimane `null` perché la chiave `"oauth"` non esiste nel JSON.

### 2.2 La catena di null

```
JSAP.java:221    oauth = jsap.oauth;                      // null — JSON senza "oauth"
JSAP.java:405    isSecure() → oauth == null → false        // by-pass incondizionato
Dashboard.java:427  else → onLogin("ვაიმეე")             // nessun login
```

### 2.3 Perché `localhost.jsap` funzionava e `sepa.wda.vaimee.com.jsap` no

| File | Blocco `oauth` | `isSecure()` | Login |
|------|---------------|-------------|-------|
| `localhost.jsap` | Presente con `"provider": "zitadel"` | `true` | Appare |
| `sepa.wda.vaimee.com.jsap` (originale) | Assente | `false` | Bypassato |
| `sepa.wda.vaimee.com.jsap` (corretto) | Presente con `"provider": "zitadel"` | `true` | Appare |

Il file `sepa.wda.vaimee.com.jsap` era un JSAP "non sicuro" pensato per ambienti senza autenticazione. Per abilitare Zitadel, serviva aggiungere il blocco `oauth`.

### 2.4 Anomalia secondaria: corruzione del file dopo `storeProperties()`

In un tentativo precedente, il file era stato modificato manualmente con il blocco `oauth`, ma dopo un tentativo di login (con "Remember me" spuntato), `OAuthProperties.storeProperties()` aveva riscritto il file con un JSON malformato — mancavano le parentesi di chiusura di `authentication` e `oauth`. Questo accadeva perché il file originale aveva un errore di sintassi JSON (mancava la `}` di chiusura del blocco `oauth`), e Gson produceva un output corrispondentemente corrotto.

La causa specifica: la prima versione del blocco `oauth` aggiunta manualmente ometteva:
1. La parentesi `}` di chiusura dell'oggetto `"authentication"`
2. La riga `"provider": "zitadel"`
3. La parentesi `}` di chiusura dell'oggetto `"oauth"`

Questo faceva sì che Gson interpretasse l'intero resto del JSON (`sparql11protocol`, `extended`, ecc.) come parte dell'oggetto `oauth`, rendendo il JSAP root privo dei campi necessari.

## 3. Strumenti di diagnostica implementati

Per identificare il problema sono stati aggiunti log diagnostici in 4 punti chiave della catena:

| Posizione | Marker | Informazione |
|-----------|--------|-------------|
| `JSAP.java:222-224` | `=== DIAG: JSAP constructor ===` | Valore di `jsap.oauth` appena deserializzato da Gson |
| `OAuthProperties.java:110-112` | `=== DIAG: OAuthProperties constructor ===` | `isSecure()` e `getOauth()` visti dal costruttore OAuth |
| `Dashboard.java:423-430` | `=== DIAG: Security check before login ===` | `isSecure()`, `getOauth()`, `jsapFiles` |
| `Client.java:61-64` | `=== DIAG: Client() constructor ===` | `isSecure()` e `getOauth()` nel costruttore Client |

È stato inoltre aggiunto `JSAP.invalidateAuth()` chiamato in `read()` e `merge()` per invalidare la cache di `OAuthProperties` dopo ogni modifica del JSAP.

## 4. Soluzione

### 4.1 Correzione del file JSAP

```json
"oauth": {
    "enable": true,
    "provider": "zitadel",
    "ssl": "TLS",
    "trustall": true,
    "authentication": {
        "endpoint": "https://sepa-engine-auth-ie5az2.eu1.zitadel.cloud/oauth/v2/token",
        "client_id": "INSERISCI_ID_QUI",
        "client_secret": "INSERISCI_SECRET_QUI"
    }
}
```

Ogni parentesi è al posto giusto, `"provider": "zitadel"` è incluso, e il blocco è un oggetto JSON autonomo e ben formato all'interno del JSAP.

### 4.2 Log diagnostico finale (post-correzione)

```
[DEBUG] === DIAG: Security check before login ===
[DEBUG] DIAG: appProfile.isSecure() = true
[DEBUG] DIAG: appProfile.getOauth() = {"enable":true,"provider":"zitadel",...}
[DEBUG] DIAG: appProfile.getAuthenticationProperties() = OAuthProperties@4db164e1
[DEBUG] DIAG: jsapFiles = [...sepa.wda.vaimee.com.jsap]
[DEBUG] ========================================
```

## 5. Flusso corretto attuale

```
JSAP constructor (Gson deserializza JSON)
  → this.oauth = jsap.oauth           // JsonObject popolato
  → isSecure() → true                  // oauth.has("enable")
  → getAuthenticationProperties()      // new OAuthProperties(this)
  → OAuthProperties constructor
      → jsap.isSecure() → true
      → oauthJsonObject = jsap.getOauth()
      → provider = OAUTH_PROVIDER.ZITADEL
  → Login dialog mostrato

Login dialog:
  → Utente inserisce client_id + client_secret
  → SwingWorker.doInBackground()
      → ClientSecurityManager(oauth)
      → ZitadelAuthenticationService.requestToken()
          → POST https://zitadel.../oauth/v2/token
          → grant_type=client_credentials
          → Authorization: Basic base64(client_id:client_secret)
  → Token ricevuto → onLogin(uid) → Dashboard operativa
```

## 6. Lesson learned

1. **Validare sempre il JSON su disco**, non solo il codice. Un JSAP senza `oauth` è perfettamente valido per Gson — semplicemente non ha il campo.
2. **I log diagnostici posizionati strategicamente** (Gson → JSAP → OAuthProperties → Dashboard) permettono di identificare esattamente dove la catena si rompe.
3. **`storeProperties()` va usato con cautela**: se il file sorgente ha un JSON malformato, la riscrittura perpetua la corruzione. Il fix di "Remember me" deselezionato al primo test evita questo scenario.
4. **La migrazione Keycloak → Zitadel è trasparente al livello JSAP** — l'unica differenza è `"provider": "zitadel"` e l'URL dell'endpoint token. Il resto del flusso (ZitadelAuthenticationService, ZitadelSecurityManager) è già stato adattato.
