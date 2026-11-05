# Multi-stage build. Both base images publish linux/amd64 and linux/arm64 variants, so this
# builds natively on the Oracle Arm VM (not yet verified there).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /data && chown app:app /data
WORKDIR /app
COPY --from=build /src/target/ohia-facade-*.jar /app/facade.jar
USER app
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=10 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /internal/health HTTP/1.0\r\n\r\n" >&3 && grep -q "200 OK" <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/facade.jar"]
