# AGENTS.md

## Build

Maven multi-module (Java 21, `maven.compiler.release=21`). Modules must build in dependency order:

```bash
mvn install -DskipTests -pl client-api          # 1. client-api first (others depend on it)
mvn install -pl engine -am                       # 2. engine (+ transitive deps)
mvn install -pl tool-dashboard -am               # 3. dashboard
```

**Why `-DskipTests` on client-api**: its tests are integration tests (`IT*.java`) that require a running SEPA engine.

### GitHub Packages auth

The project depends on `com.vaimee:sjenar-*` from GitHub Packages. A `settings.xml` with a valid GitHub PAT must exist at repo root (gitignored). The CI and Docker builds pass it as a BuildKit secret.

### Version mismatch

`engine/pom.xml` hardcodes `client-api` version `1.0.1` while the parent uses `${revision}` = `1.0.0-SNAPSHOT`. Other modules use `${project.parent.version}`. If you get client-api resolution errors, check this.

## Test

- **Unit tests**: `mvn test` — surefire excludes `IT*.java` and `Stress*.java`
- **Integration tests**: `mvn verify` — failsafe runs `IT*` classes; requires a running engine + SPARQL store
- **CI flow**: starts engine from `engine/target/engine-*.jar`, polls `http://localhost:8000/echo`, then `mvn verify`

## Architecture

```
client-api/      Java library: Producer, Consumer, Aggregator patterns
  └─ JSAP (.jsap) loaded by Gson → SPARQL11Properties → SPARQL11SEProperties → JSAP
engine/          Core broker (HTTP :8000 query/update, WS :9000 subscribe)
  main class: com.vaimee.sepa.engine.core.Engine
  config: engine.jpar (ports, security), endpoint.jpar (SPARQL store)
tool-dashboard/  Swing GUI
  main class: com.vaimee.sepa.tools.dashboard.Dashboard
example-chat/    Demo app using client-api
```

Data flow: Client → Engine (SPARQL 1.1 SE Protocol) → SPARQL Store (Jena in-memory or Blazegraph :9999).

## Key config files

| File | Purpose | Loaded by |
|------|---------|-----------|
| `*.jsap` | Client: host, ports, paths, SPARQL templates, forced bindings | client-api / dashboard |
| `*.jpar` | Engine: gates ports, security, scheduler, endpoint config | Engine at startup |
| `dashboard.properties` | Stores path to the JSAP the dashboard loads | Dashboard (gitignored) |

## Known bugs

**Null-pointer on missing JSAP keys** — `SPARQL11Properties.java:154` and `SPARQL11SEProperties.java:111`:
When Gson deserializes a JSAP that lacks `sparql11protocol` (or `sparql11seprotocol`), the field is set to `null`. The `else` branch that creates safe defaults (line 163 / line 123) is skipped because `uri != null`. Every getter on that protocol field then NPEs. This is why `explorer.jsap` (no protocol keys) crashes the dashboard but `localhost.jsap` (has them) works.

## Engine paths

Engine uses `/query` and `/update` — **not** `/sparql`. JSAP files must match. The default `endpoint.jpar` uses `/sparql` because that's the backend SPARQL store path, not the engine gate path.

## Security

OAuth 2.0 client-credential flow. Three backends: `local` (in-memory), `ldap`, `keycloak`. Default JKS password: `sepa2017`. Configured in `engine.jpar` → `gates.security.type`.

## Docker

`Dockerfile` builds only the engine. Build requires `--secret` flags for `maven_settings`, `github_actor`, `github_token`. Use `build-docker.sh` or see the Dockerfile for the exact secret IDs. No `docker-compose.yml` exists yet.
