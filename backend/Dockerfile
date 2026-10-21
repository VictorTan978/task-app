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
# MaxRAMPercentage=75: Render's free instance has 512 MB.
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
