FROM openjdk:17.0.2-jdk-slim
LABEL authors="etu"
WORKDIR /app
ARG JAR_FILE=target/user-service-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]