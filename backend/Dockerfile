FROM maven:3.9.11-eclipse-temurin-21 AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
COPY *.sql ./
COPY sql ./sql
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=builder /build/target/citywalk-backend-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
