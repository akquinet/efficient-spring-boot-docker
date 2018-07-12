FROM openjdk:8u171-jdk-alpine3.7

CMD ["/usr/bin/java", "-jar", "/app.jar"]

COPY target/dependency /lib

COPY target/app.jar /app.jar
