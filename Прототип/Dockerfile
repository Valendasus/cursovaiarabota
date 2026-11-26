FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY schema.sql demo.sql ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -ntp verify

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 retail \
    && useradd --uid 10001 --gid 10001 --no-create-home retail \
    && install -d -o retail -g retail /data
COPY --from=build /build/target/store-inventory.jar /app/store-inventory.jar
USER 10001:10001
ENV INVENTORY_DATABASE=/data/inventory.sqlite3
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/store-inventory.jar"]
