# syntax=docker/dockerfile:1

# ---- Build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Resolve dependencies from the POM alone so a source-only change reuses this
# layer. The cache mount keeps ~/.m2 across builds without baking it into an image.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

# ---- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN useradd --system --uid 1001 --user-group --no-create-home skytrack \
 && chown skytrack:skytrack /app

# spring-boot-maven-plugin repackages to a single executable *.jar; the thin
# original is left as *.jar.original, so this glob matches exactly one file.
COPY --from=build --chown=skytrack:skytrack /build/target/*.jar /app/app.jar

USER skytrack
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
