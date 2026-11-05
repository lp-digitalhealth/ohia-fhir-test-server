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
# Tomcat answers "HTTP/1.1 200" with no reason phrase, so match the status code, not the text "200 OK"
# (an earlier version grepped for "200 OK" and reported a healthy app as unhealthy).
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=10 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /internal/health HTTP/1.0\r\n\r\n" >&3 && head -n1 <&3 | grep -q "^HTTP/1\.[01] 200"'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/facade.jar"]
