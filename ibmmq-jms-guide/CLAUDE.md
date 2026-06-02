# ibmmq-jms-guide — Micronaut module (build & test)

Runnable Micronaut 4 / Java 25 module for the COA/COD end-to-end demo. `mvn test` = unit (surefire); `mvn verify` = Testcontainers IT (failsafe). Stack coordinates & MQ constants: `../research-output/phase-a-fact-sheet.md`. Test conventions: `src/test/CLAUDE.md`.

## Toolchain & environment quirks

- **JDK — Java 25 (production target).** Amazon Corretto **25** at `~/.local/jdk25` (`JAVA_HOME`), pinned via `mise.toml` (`java = "corretto-25"`). **Maven** 3.9.9 at `~/.local/maven-current`. Helper: `source ~/.local/ibmmq-env.sh`. `pom.xml` sets `maven.compiler.release=25` — supersedes the locked brief's P1 (Java 21) by user decision; rationale in `../docs/adr/0001-java-25-runtime.md`.
- **Native access.** Run/test with `--enable-native-access=ALL-UNNAMED` (silences the MQ client native-access warning on JDK 25; already wired into surefire/failsafe `argLine`). Avoid `TLS_RSA_*` ciphers (disabled in Java 25).
- **Testcontainers stack.** Core `org.testcontainers:testcontainers:2.0.5` + official IBM module `com.ibm.mq:mq-java-testcontainer:2.0.3` (class `com.ibm.mq.testcontainers.MQContainer`, extends `GenericContainer`; the module pulls core 2.0.3 transitively — core 2.0.5 is declared directly to override it). IT uses manual lifecycle (`@BeforeAll`/`@AfterAll`), so `org.testcontainers:junit-jupiter` is NOT needed.
- **Docker connectivity (root cause + real fix).** On Docker Desktop / engine 29.x (API 1.54, min 1.40), the docker-java bundled in Testcontainers 1.20.x is incompatible with the Docker Desktop socket proxy → daemon returns **HTTP 400** → "Could not find a valid Docker environment". `DOCKER_API_VERSION=1.44` did **NOT** fix this; the real fix was **migrating to Testcontainers 2.0.5** (modern docker-java 3.7.1). (`/var/run/docker.sock` is healthy — `curl /info` returns 200.) The `DOCKER_API_VERSION=1.44` env in the failsafe plugin is kept as harmless belt-and-suspenders.
- **commons-codec pin.** `docker-java-transport-zerodep:3.7.1` (via TC 2.0.5) references `org.apache.commons.codec.Charsets`, removed in commons-codec 1.17+. Pin **`commons-codec:commons-codec:1.16.1`** (test scope) or the container fails to start with `NoClassDefFoundError`.
- **Sandbox.** When running the IT through a Claude Bash tool, disable the sandbox so the forked JVM can reach `/var/run/docker.sock`.
- **IT runs as `admin`/`mqm`** (`DEV.ADMIN.SVRCONN`), which already holds all context authorities — so the IT never exercises the report-PUT `+passid` authority that the k3s deploy needs (see root `CLAUDE.md`).
