# Two-stage build: compile with a full JDK, then run on a slim JRE image.
# You don't need Java installed on your machine to build this image.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# Copy build files first so dependency downloads are cached between builds.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER app
EXPOSE 8080
# Use container memory limits sensibly (important on small free-tier hosts).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
