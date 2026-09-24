# syntax=docker/dockerfile:1

# Build: the same Maven wrapper and JDK 21 the enforcer requires locally.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B package -DskipTests \
    && cp target/wms-core-*.jar /app.jar

FROM eclipse-temurin:21-jre
# AWS Lambda Web Adapter (ADR 0029): runs this ordinary Spring Boot server inside Lambda by turning Lambda
# events into HTTP requests on the port below. Outside Lambda it does nothing, so the image also runs as-is.
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0 /lambda-adapter /opt/extensions/lambda-adapter
ENV AWS_LWA_PORT=8080 \
    AWS_LWA_READINESS_CHECK_PATH=/actuator/health \
    SPRING_PROFILES_ACTIVE=demo
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
# Serial GC and C1-only JIT start faster, which matters more than peak throughput for a Lambda cold start.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
