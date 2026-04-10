# SEPA Architecture

1. SEPA = SPARQL Event Processing Architecture: a pub-sub broker over SPARQL 1.1 protocol.
2. **Engine** is the core broker: it receives SPARQL queries/updates via HTTP (`:8000`) and subscription requests via WebSocket (`:9000`).
3. The engine forwards queries/updates to a SPARQL store (Jena in-memory by default, or Blazegraph at `:9999`).
4. When data changes, the engine notifies subscribers whose SPARQL queries match the new triples (SPU = Subscription Processing Unit).
5. **Client-API** is the Java library that implements the SEPA Design Pattern: `Producer` (publish), `Consumer` (subscribe), `Aggregator` (both).
6. All client-server communication uses SPARQL 1.1 SE Protocol (HTTP REST + WebSocket) — no MQTT, no gRPC.
7. **JSAP files** (`.jsap`) are the single source of truth for client configuration: host, ports, paths, SPARQL templates, and forced bindings.
8. **JPAR files** (`.jpar`) configure the engine: gate ports, security, scheduler, and which SPARQL endpoint to use.
9. **Dashboard** is a Swing GUI that loads a JSAP to connect to the engine, then exposes Explorer/Query/Update/Subscribe tabs.
10. **Security** layer: OAuth 2.0 client-credential flow (local in-memory, LDAP, or Keycloak) with JWT signing and TLS via JKS keystores.

# CURRENT MISSION

**1. Fix the dashboard silent-crash.** Workaround fixes applied (no code changes):

- `dashboard.properties` now points to `localhost.jsap` (has connection config) instead of `explorer.jsap` (only had dashboard queries).
- `localhost.jsap` query/update paths corrected from `/sparql` to `/query` and `/update` to match the engine.

Known bug (not yet fixed in code): `SPARQL11Properties.java:154` assigns `jsap.sparql11protocol` directly from Gson deserialization. When a JSAP file lacks the `sparql11protocol` key, Gson returns `null`, and the safe-defaults branch at line 163 is skipped because `uri != null`. Same issue at `SPARQL11SEProperties.java:111` for `sparql11seprotocol`. This causes NullPointerExceptions in every JSAP getter method that accesses protocol configuration.

**2. Prep Docker.** No `docker-compose.yml` exists yet. The `Dockerfile` builds only the engine. A compose stack is needed: Blazegraph (port 9999) → Engine (8000/9000) → Dashboard.

# NEXT OBJECTIVE

1. Fix the boot sequence — add null guards in `SPARQL11Properties.java` and `SPARQL11SEProperties.java` so missing JSAP keys fall back to safe defaults instead of null.
2. Create a `docker-compose.yml` that orchestrates the SEPA engine + Blazegraph + Dashboard as a reproducible local stack.
3. Document the existing Auth flow (OAuth 2.0 client-credential flow with local in-memory `SecurityManager`, JKS-based JWT signing, TLS) to prepare for the Zitadel transition.
