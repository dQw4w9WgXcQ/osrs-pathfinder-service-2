FROM openjdk:17 AS build
WORKDIR /workdir
COPY . .
RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

FROM openjdk:17
WORKDIR /workdir
COPY --from=build /workdir/build/libs/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]