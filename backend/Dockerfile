# Production image for the backend (used by Render, see render.yaml and "Deploy to Render" in README.md).
# Image tags are pinned to exact versions (like docker-compose.yml) so builds are reproducible.
# Bump both together; the JDK and JRE tags should be the same Java release.

# ---- build stage ----
FROM eclipse-temurin:21.0.8_9-jdk-noble AS build
WORKDIR /build

# Dependencies first: this layer is cached until pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
# Make sure the wrapper is executable even if the file mode was lost (e.g. checkout on Windows).
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# Tests are skipped here: they need Testcontainers/Docker, which the build environment doesn't have.
# Run ./mvnw test in CI or locally.
RUN ./mvnw -B -DskipTests package

# ---- run stage ----
FROM eclipse-temurin:21.0.8_9-jre-noble
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app
EXPOSE 8080
# Render's free instance has 512 MB. Heap is capped at 256 MB to leave room for metaspace,
# threads and the code cache (a bigger heap risks Render killing the process).
# C1-only compiler + SerialGC: faster startup and low overhead on a fraction of a CPU.
ENTRYPOINT ["java","-Xmx256m","-Xss512k","-XX:+UseSerialGC","-XX:TieredStopAtLevel=1","-XX:ReservedCodeCacheSize=64m","-jar","app.jar"]
