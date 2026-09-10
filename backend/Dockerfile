FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 careflow
COPY --from=build /build/target/knowledge-platform-0.1.0.jar app.jar
USER careflow
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
