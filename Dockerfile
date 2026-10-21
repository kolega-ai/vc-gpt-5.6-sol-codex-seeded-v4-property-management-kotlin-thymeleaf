FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean bootJar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 harbour && mkdir -p /app/data/uploads && chown -R harbour /app
USER harbour
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
