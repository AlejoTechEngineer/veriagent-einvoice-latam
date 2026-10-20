# Plataforma de integración (aplicación Boot única, perfil full): imagen mínima con JRE y usuario sin privilegios.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY verification-kit ./verification-kit
COPY data-generator ./data-generator
COPY integration-plane ./integration-plane
RUN mvn -B -q -f integration-plane/pom.xml package -DskipTests -pl platform-app -am

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S veriagent && adduser -S veriagent -G veriagent && mkdir -p /shared && chown veriagent /shared
WORKDIR /app
COPY --from=build /src/integration-plane/platform-app/target/platform-app-0.1.0.jar /app/platform.jar
COPY specs ./specs
COPY catalogs ./catalogs
COPY schemas ./schemas
COPY data/routing ./data/routing
COPY data/numeracion ./data/numeracion
COPY data/mappings ./data/mappings
COPY data/fixtures ./data/fixtures
COPY agentic-plane/golden ./agentic-plane/golden
ENV VERIAGENT_HOME=/app SPRING_PROFILES_ACTIVE=full
USER veriagent
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/platform.jar"]
