# =============================================================================
# Dockerfile — single image, three roles (publisher / business-consumer / report-consumer)
#
# Multi-stage:
#   1) build   — Amazon Corretto 25 + Maven, compiles and packages the thin jar plus the
#                runtime classpath under target/dependency (copy-dependencies, see pom.xml).
#   2) runtime — Amazon Corretto 25, runs the app with an explicit classpath (no shade/uber-jar:
#                Micronaut DI needs the per-jar META-INF/* intact, which copy-dependencies preserves).
#
# Role selection is purely runtime config: set the HARNESS_ROLE env (mapped to harness.role) in the
# k8s manifests; the same image becomes a publisher, a business-consumer, or a report-consumer.
#
# Build + load into k3s (no registry):
#   docker build -t ibmmq-jms-harness:1.0.0 -f ibmmq-jms-guide/Dockerfile ibmmq-jms-guide
#   docker save ibmmq-jms-harness:1.0.0 | sudo k3s ctr images import -
# (see deploy/k3s/README.md for the full flow)
# =============================================================================

# ----------------------------- Stage 1: build -----------------------------
# Maven on a Corretto 25 base. The tag below pins Maven 3.9.x on Eclipse Temurin/Corretto-class JDK 25;
# if your registry lacks this exact tag, swap to amazoncorretto:25 + install Maven, or use a maven:3.9
# image whose JDK is 25. The build only needs a JDK 25 + Maven 3.9.x.
FROM maven:3.9-amazoncorretto-25 AS build

WORKDIR /workspace

# 1) Resolve dependencies first (better layer caching): copy only the POM, go offline.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# 2) Copy sources and package. Skip tests in the image build (the IT needs Docker-in-Docker;
#    unit tests run in CI / locally). copy-dependencies (bound to `package`) fills target/dependency.
COPY src ./src
RUN mvn -B -q -DskipTests clean package

# ----------------------------- Stage 2: runtime ---------------------------
FROM amazoncorretto:25 AS runtime

WORKDIR /app

# Run as a non-root user (defense in depth; k8s securityContext can pin this too).
# procps-ng provides `pgrep`, used by the Deployments' exec liveness probe.
RUN yum install -y shadow-utils procps-ng \
    && groupadd -r app && useradd -r -g app -d /app app \
    && yum clean all
USER app

# Bring over the compiled classes, the runtime classpath jars, and the resources.
COPY --from=build /workspace/target/classes        /app/classes
COPY --from=build /workspace/target/dependency      /app/dependency

# JVM flags:
#   --enable-native-access=ALL-UNNAMED  silences the MQ client native-access warning on Corretto 25.
# Classpath: compiled classes + every runtime jar copied by maven-dependency-plugin.
# Role/store/connection are selected by env (HARNESS_ROLE, CORRELATION_STORE, IBM_MQ_*, DATASOURCES_*).
ENV JAVA_OPTS="--enable-native-access=ALL-UNNAMED"

# exec form via sh -c so $JAVA_OPTS expands; PID 1 still receives SIGTERM (shell exec).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp \"/app/classes:/app/dependency/*\" com.example.ibmmq.Application"]
