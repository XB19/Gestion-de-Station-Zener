# API ZENER Station Manager : construction Maven puis image d'exécution Java 17
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/station-manager-*.jar app.jar
ENV APP_TIMEZONE=Africa/Lome UPLOAD_DIR=/app/uploads
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
