FROM maven:3.10.1-eclipse-temurin-21 AS build
WORKDIR /app

# copy maven wrapper and pom first to leverage layer caching
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn

# copy sources and build
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar /app/app.jar
EXPOSE 8080
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENTRYPOINT ["java","-jar","/app/app.jar"]
