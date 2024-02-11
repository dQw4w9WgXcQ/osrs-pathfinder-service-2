#use docker buildx build NOT docker build

FROM osrs-pathfinder-maven-local-published AS build
WORKDIR /workdir
COPY . .
RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

FROM --platform=$TARGETPLATFORM eclipse-temurin:17
WORKDIR /workdir
COPY --from=build /workdir/build/libs/osrs-pathfinder-service-2-*-all.jar /bindir/service.jar
EXPOSE 8080
CMD ["java", "-XX:+UseZGC", "-jar", "/bindir/service.jar"]