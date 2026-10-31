FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/*.jar app.jar
ENV SERVER_ADDRESS=0.0.0.0
ENV SERVER_PORT=8084
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
