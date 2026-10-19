FROM eclipse-temurin:17-jre-alpine-3.24

RUN addgroup -S nemo && adduser -S -G nemo -u 10001 nemo
WORKDIR /app
COPY --chown=nemo:nemo service.jar /app/service.jar
USER nemo
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
