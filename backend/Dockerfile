ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG JAVA_IMAGE=eclipse-temurin:21-jre
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
COPY pom.xml .
COPY LICENSE NOTICE ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests
FROM ${JAVA_IMAGE}
WORKDIR /app
RUN useradd --system --uid 10001 careflow
COPY --from=build /build/target/knowledge-platform-0.1.0.jar app.jar
USER careflow
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
