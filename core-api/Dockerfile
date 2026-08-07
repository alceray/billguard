FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine AS extract
WORKDIR /builder
COPY --from=build /workspace/target/core-api-*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /application

RUN addgroup -g 1001 -S billguard && adduser -u 1001 -S billguard -G billguard

COPY --from=extract --chown=billguard:billguard /builder/extracted/dependencies/ ./
COPY --from=extract --chown=billguard:billguard /builder/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=billguard:billguard /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=billguard:billguard /builder/extracted/application/ ./

USER billguard
EXPOSE 3001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseCompactObjectHeaders"

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:3001/health || exit 1

ENTRYPOINT ["java", "-jar", "application.jar"]
