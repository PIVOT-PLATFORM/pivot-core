# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
RUN apt-get update -q && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B -q
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -B -q

# Runtime sur Alpine (musl) : surface OS minimale (~quelques paquets vs ~106 sur Ubuntu)
# -> bien moins de CVE OS. Seule l'image runtime est livrée et scannée (le builder est jeté).
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# Busybox/Alpine : addgroup/adduser (pas groupadd/useradd).
RUN addgroup -S pivot && adduser -S -G pivot pivot
COPY --from=builder /workspace/target/*.jar app.jar
USER pivot
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
