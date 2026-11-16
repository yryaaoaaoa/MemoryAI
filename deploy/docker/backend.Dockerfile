FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S memoryai && adduser -S memoryai -G memoryai
COPY --chown=memoryai:memoryai app.jar /app/app.jar
USER memoryai
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.config.location=file:/app/config/application.yml"]
