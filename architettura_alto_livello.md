# Architettura ad Alto Livello — SEPA

Diagramma UML dei package con sintassi Mermaid.js. Mostra i package principali dei 4 moduli Maven e le loro dipendenze.

```mermaid
graph TB
    subgraph CLIENT["client-api"]
        direction TB
        API["api<br/>Interfacce Protocollo<br/><i>SPARQL11Protocol · SPARQL11SEProtocol</i>"]
        COMMONS["api.commons<br/>Tipi Condivisi<br/><i>exceptions · properties · request<br/>response · security · sparql</i>"]
        PATTERN["api.pattern<br/>Pattern SEPA<br/><i>Producer · Consumer · Aggregator · JSAP</i>"]
        WS["api.protocols.websocket<br/>Client WebSocket<br/><i>Sottoscrizioni in tempo reale</i>"]
    end

    subgraph ENGINE["engine"]
        direction TB
        CORE["engine.core<br/>Orchestratore<br/><i>Engine · EngineProperties</i>"]
        GATES["engine.gates<br/>Gate HTTP & WebSocket<br/><i>Ingresso richieste client</i>"]
        PROTO["engine.protocol<br/>Handler Protocollo<br/><i>SPARQL 1.1 · OAuth 2.0</i>"]
        SCHED["engine.scheduling<br/>Coda & Dispatch<br/><i>Scheduler · InternalRequest</i>"]
        PROC["engine.processing<br/>Esecuzione & Notifica<br/><i>Processor · Endpoint · SPU</i>"]
        DEP["engine.dependability<br/>Sicurezza & Autorizzazione<br/><i>SecurityManager · ACL · JWT</i>"]
    end

    subgraph DASH["tool-dashboard"]
        direction TB
        DASH_MAIN["dashboard<br/>GUI Orchestratore<br/><i>Dashboard · DashboadApp</i>"]
        EXPLORER["dashboard.explorer<br/>Explorer RDF"]
        BINDINGS["dashboard.bindings & utils<br/>Binding · Tabelle · Login"]
    end

    subgraph CHAT["example-chat"]
        CHATPKG["example-chat<br/><i>App demo Producer / Consumer</i>"]
    end

    %% ── Dipendenze cross-modulo ──
    PROC -->|"usa SPARQL11Protocol<br/>come client HTTP verso lo store"| API
    DASH_MAIN -->|"usa GenericClient, JSAP"| PATTERN
    DASH_MAIN -->|"usa response, sparql,<br/>security, properties"| COMMONS
    DASH_MAIN -->|"ISubscriptionHandler"| API
    CHATPKG -->|"usa Producer, Consumer"| PATTERN

    %% ── Engine — flusso principale ──
    CORE --> GATES
    CORE --> SCHED
    CORE --> PROC
    CORE --> DEP
    GATES -->|"richieste in coda"| SCHED
    GATES -->|"delega handler"| PROTO
    PROTO -->|"valida auth"| DEP
    PROTO -->|"richieste in coda"| SCHED
    PROC -->|"esegue richieste<br/>dallo scheduler"| SCHED
    PROC -->|"controlla ACL"| DEP

    %% ── client-api — layering ──
    PATTERN --> API
    PATTERN --> COMMONS
    PATTERN --> WS
    WS --> API
    WS --> COMMONS
    API --> COMMONS

    %% ── Dashboard — composizione GUI ──
    DASH_MAIN --> EXPLORER
    DASH_MAIN --> BINDINGS
    EXPLORER --> BINDINGS

    %% ── Dipendenza circolare ──
    COMMONS -.->|"⟲ OAuthProperties ↔ JSAP"| PATTERN

    style CLIENT fill:#e8f5e9,stroke:#388e3c
    style ENGINE fill:#e3f2fd,stroke:#1976d2
    style DASH fill:#fff3e0,stroke:#f57c00
    style CHAT fill:#f3e5f5,stroke:#7b1fa2
```

## Legenda

| Freccia | Significato |
|---------|------------|
| `──►` | Dipendenza diretta (importa classi dal package target) |
| `- -►` | Dipendenza circolare (vedi note sotto) |
| Colore verde | Modulo `client-api` |
| Colore blu | Modulo `engine` |
| Colore arancio | Modulo `tool-dashboard` |
| Colore viola | Modulo `example-chat` |

## Note architetturali

### Dipendenze circolari

- **`api.commons.security` ↔ `api.pattern`**: `OAuthProperties` (in security) importa `JSAP` (in pattern), mentre i client in `pattern` importano `ClientSecurityManager`. La freccia tratteggiata evidenzia il verso "controcorrente" rispetto al layering ideale.
- **`engine.processing` ↔ `engine.processing.subscriptions`**: `Processor` trattiene un riferimento a `SPUManager` e viceversa (non visibile a questo livello di aggregazione — i due sub-package sono collassati in `engine.processing`).

### Flusso dati principale

```
Client → gates (HTTP/WS) → scheduling (coda) → processing (esecuzione) → endpoint → SPARQL Store
                                                                      ↓
                                                        subscriptions (SPU) → notifica → gates → Client
```

1. Un client invia una richiesta SPARQL via HTTP o una sottoscrizione via WebSocket
2. I **gates** ricevono la richiesta e la incodano nello **scheduler**
3. Lo **scheduler** la dispatcha al **processor**
4. Il processor esegue la query/update sull'**endpoint** (Jena in-memory o remoto via `SPARQL11Protocol`)
5. Dopo un update, lo **SPUManager** rivaluta le sottoscrizioni attive e notifica i client tramite i loro gate

### Isolamento del dashboard

`tool-dashboard` non ha alcuna dipendenza compile-time verso `engine.*`. Comunica con l'engine esclusivamente tramite `client-api` (protocollo SPARQL 1.1 SE su HTTP/WebSocket).

### Punto di integrazione engine ↔ client-api

`engine.processing.endpoint.RemoteEndpoint` usa `api.SPARQL11Protocol` come client HTTP per inoltrare query/update allo store SPARQL remoto (es. Blazegraph). Questa è l'unica dipendenza diretta dell'engine verso il client-api a runtime.
