# AGENTS.md

## Build Commands

```bash
mvn compile                                    # Compile all modules
mvn package -DskipTests                         # Build fat JARs (engine, dashboard, chat)
mvn package -DskipTests -pl engine -am          # Build engine + its dependencies only
mvn package -DskipTests -pl tool-dashboard -am  # Build dashboard + dependencies only
mvn clean deploy -DskipTests -Drevision=X.Y.Z  # Publish to Maven repo (needs settings.xml)
```

## Test Commands

```bash
mvn test                                        # Unit tests only (surefire; excludes IT* and Stress*)
mvn verify                                      # Unit + integration tests (requires running engine for IT)
mvn test -Dtest=SEPAAclTest -pl engine           # Run a single unit test in a specific module
mvn verify -Dit.test=ITPattern -pl client-api    # Run a single integration test
mvn test -Dtest=StressUsingSPARQLProtocol -pl client-api  # Run a stress test manually
```

Surefire excludes: `**/IT*.java`, `**/Stress*.java`. Failsafe picks up `IT*.java`.
Tests require JUnit Jupiter 5.11.3 (declared in root pom.xml).

## Project Structure

```
SEPA-internships/
├── client-api/          # Java library: Producer, Consumer, Aggregator, JSAP parser, OAuth client
├── engine/              # SEPA broker: HTTP/WS gates, SPU manager, scheduler, security
├── example-chat/        # Demo Swing chat app using client-api
├── tool-dashboard/      # Swing GUI: Explorer, Query, Update, Subscribe tabs
└── pom.xml              # Parent POM (Java 21, CI-friendly ${revision} versioning)
```

Key packages:
- `com.vaimee.sepa.api.pattern` — Producer, Consumer, Aggregator, Client, GenericClient
- `com.vaimee.sepa.api.commons.properties` — JSAP, SPARQL11Properties, SPARQL11SEProperties
- `com.vaimee.sepa.api.commons.security` — ClientSecurityManager, OAuthProperties, SSLManager
- `com.vaimee.sepa.engine.core` — Engine entry point, EngineProperties
- `com.vaimee.sepa.engine.gates` — HTTP/WebSocket protocol adapters
- `com.vaimee.sepa.engine.processing` — Request processors, SPU manager
- `com.vaimee.sepa.engine.dependability` — Security, ACL, authorization (local/LDAP/Keycloak)

## Code Style

- **Indentation**: Tabs (1 tab per level)
- **Brace style**: K&R (opening brace on same line)
- **No enforced style**: No checkstyle, spotless, editorconfig, or PMD
- **Line length**: No enforced limit

### Naming Conventions

| Element | Convention | Example |
|---|---|---|
| Interfaces | `I` prefix | `IProducer`, `IConsumer`, `ISecurityManager` |
| Classes | PascalCase | `Engine`, `SPARQL11Properties` |
| Methods | camelCase | `syncSubscribe()`, `setParameter()` |
| Constants | SCREAMING_SNAKE_CASE | `SPARQL_ID`, `TIMEOUT` |
| Exceptions | `SEPA` prefix + `Exception` suffix | `SEPAPropertiesException` |
| MBean interfaces | Class + `MBean` suffix | `EngineMBean` |
| Enums | PascalCase type, SCREAMING_SNAKE values | `ProtocolScheme.http` |

### Error Handling

- All business exceptions are checked, prefixed with `SEPA`: `SEPAPropertiesException`, `SEPAProtocolException`, `SEPASecurityException`, `SEPABindingsException`
- Dual constructors: `(String message)` and `(String message, Throwable cause)`
- Always include `serialVersionUID`
- Multi-catch pattern common: `catch (SEPAProtocolException | SEPASecurityException | IOException e)`
- Engine main uses `System.err.println + System.exit(1)` for fatal errors

### Logging

- Static facade: `com.vaimee.sepa.logging.Logging` wraps Log4j2
- Usage: `Logging.error(...)`, `Logging.info(...)`, `Logging.trace(...)`
- Some files use `LogManager.getLogger()` directly
- Custom log levels: `SPUManager`, `spu`, `timing`, `subscriptions`, `http`, `oauth`, `ldap`, `ping`
- Default level: `error` (console), `off` (file)
- Config: `log4j2.xml` in each module's `src/main/resources/`

### Imports

No strict ordering enforced. General pattern: `java.*`, then `com.google.*`/`org.apache.*`, then `com.vaimee.*`. No unused import cleanup enforced.

## Configuration Files

- **JSAP** (`.jsap`): Client-side config — host, ports, paths, SPARQL templates, forced bindings, OAuth settings
- **JPAR** (`.jpar`): Engine config — gate ports, security mode, scheduler, SPARQL endpoint
- **`dashboard.properties`**: Points to the JSAP file the dashboard loads at startup
- **`engine.jpar`** (`engine/src/main/resources/config/`): Default engine ports (HTTP 8000, WS 9000), paths (`/query`, `/update`, `/subscribe`)
- **`endpoint.jpar`** (`engine/src/main/resources/endpoints/`): SPARQL store connection (default: Jena in-memory)
- **`sepa.jks`**: JKS keystore for JWT signing + TLS (password: `sepa2020`)

## Running Locally

```bash
# 1. Start engine (uses Jena in-memory by default)
cd engine/target
java -Dlog4j.configurationFile=./log4j2.xml -jar engine-1.0.0-SNAPSHOT.jar

# 2. Start dashboard (working dir must be tool-dashboard/ for dashboard.properties)
cd tool-dashboard
java -jar target/dashboard-1.0.0-SNAPSHOT-shaded.jar

# 3. Or with Blazegraph instead of Jena in-memory
#    Copy endpoint-blazegraph.jpar over endpoint.jpar, then start engine
```

## CI/CD

- GitHub Actions (`main.yml`): manual `workflow_dispatch` only
- Build: `mvn --batch-mode --update-snapshots compile`
- Test: `mvn package`, start engine, then `mvn verify -e`
- Docker: builds `vaimee/sepa:<tag>`, exposes 8000 (HTTP), 9000 (WS), 7090 (JMX)
