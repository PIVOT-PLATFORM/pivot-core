# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw dependency:go-offline -B -q
COPY src/ src/
# EN04.2 — git-commit-id-maven-plugin (pom.xml) needs an actual .git directory at build time
# to populate git.properties (real commit SHA in /actuator/info) — without it the plugin
# silently no-ops (failOnNoGitDirectory=false) and the shipped image's /actuator/info would
# carry no git data at all. Discarded with the rest of this builder stage; never copied into
# the final runtime image below.
COPY .git/ .git/
RUN --mount=type=cache,target=/root/.m2 ./mvnw package -DskipTests -B -q

# Runtime Alpine : surface OS minimale, CVE réduits. Builder jeté à la fin.
FROM eclipse-temurin:25-jre-alpine
RUN apk upgrade --no-cache
# curl est absent de l'image Alpine JRE de base (surface OS minimale) — ajouté uniquement pour
# le HEALTHCHECK ci-dessous, seul appelant sur cette image. Doit rester avant `USER pivot` :
# apk a besoin de privilèges root pour écrire son log/verrou (sinon "Permission denied").
RUN apk add --no-cache curl
WORKDIR /app
RUN addgroup -S pivot && adduser -S -G pivot pivot
COPY --from=builder /workspace/target/*.jar app.jar
USER pivot
EXPOSE 8080
# EN04.2 — port de management Actuator, séparé du port applicatif (:8080), non routé par
# nginx (docker-compose.prod.yml, EN07.1). Isolation réseau (pas de publication host, réseau
# Docker interne uniquement) appliquée côté compose — EXPOSE ici documente le port, ne
# l'ouvre pas au host à lui seul.
EXPOSE 8081
# EN04.4 — timing pinned to this Enabler's AC exactly (interval 10s, timeout 5s, start-period
# 30s, retries 3). docker-compose.prod.yml's own `healthcheck:` block (when the container runs
# via that compose file) overrides this directive with the identical values — this one is what
# applies when the image runs standalone (docker run, no compose), so both must stay in sync.
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
