FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY protocol protocol
COPY src src
RUN chmod 0755 gradlew && ./gradlew --no-daemon clean bootJar && \
    find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp '{}' /workspace/persona-editor.jar \;

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S persona && adduser -S -G persona persona
WORKDIR /app
COPY --from=build /workspace/persona-editor.jar /app/persona-editor.jar
USER persona:persona
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/persona-editor.jar"]
