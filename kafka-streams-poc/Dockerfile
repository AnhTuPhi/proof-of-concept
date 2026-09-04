FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app && \
    mkdir -p /data/kafka-streams && chown -R app:app /data /app
COPY --from=build /workspace/target/*.jar /app/app.jar
USER app
ENV KAFKA_STATE_DIR=/data/kafka-streams \
    JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -Xss512k"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
