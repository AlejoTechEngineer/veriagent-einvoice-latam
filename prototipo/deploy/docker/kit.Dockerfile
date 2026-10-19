# Kit de verificación: imagen mínima, usuario sin privilegios, solo JRE.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY verification-kit ./verification-kit
RUN mkdir -p out && javac -d out $(find verification-kit/src/main/java -name '*.java') \
 && printf 'Main-Class: org.tfg.kit.cli.Main\n' > m.mf && jar --create --file kit.jar --manifest m.mf -C out .

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S kit && adduser -S kit -G kit
WORKDIR /app
COPY --from=build /src/kit.jar /app/kit.jar
COPY specs ./specs
COPY catalogs ./catalogs
COPY schemas ./schemas
USER kit
EXPOSE 8085
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://localhost:8085/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/kit.jar", "serve", "--home", "/app", "--port", "8085"]
