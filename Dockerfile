# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
RUN apt-get update -q && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B -q
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -B -q

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system pivot && useradd --system --gid pivot pivot
COPY --from=builder /workspace/target/*.jar app.jar
USER pivot
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
