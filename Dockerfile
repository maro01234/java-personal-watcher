FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -Pproduction package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN mkdir -p /app/data && chown -R 10001:10001 /app
COPY --from=build /build/target/java-personal-watcher-0.1.0.jar /app/app.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0 \
    PORT=10000 \
    WATCHER_DB=/app/data/watcher.db \
    WATCHER_AUTH_REQUIRED=true
EXPOSE 10000
CMD ["java", "-Xms64m", "-Xmx256m", "-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=48m", "-XX:ActiveProcessorCount=2", "-jar", "/app/app.jar"]
