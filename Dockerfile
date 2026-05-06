FROM maven:3.9-eclipse-temurin-25-alpine AS build

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache ca-certificates && update-ca-certificates

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-Xmx320m", \
  "-XX:MaxMetaspaceSize=128m", \
  "-XX:MaxDirectMemorySize=32m", \
  "-XX:+UseSerialGC", \
  "-jar", "app.jar"]
