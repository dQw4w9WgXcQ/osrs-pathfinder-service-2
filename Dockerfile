#use docker buildx build NOT docker build

FROM osrs-pathfinder-maven-local-published AS build
WORKDIR /workdir
COPY . .
RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

FROM --platform=$TARGETPLATFORM eclipse-temurin:17-alpine as jlink
COPY --from=build /workdir/build/libs/osrs-pathfinder-service-2-*-all.jar /service.jar
RUN apk add --no-cache binutils
RUN unzip /service.jar -q -d temp
RUN jdeps  \
      --print-module-deps \
      --ignore-missing-deps \
      --recursive \
      --multi-release 17 \
      --class-path="./temp/BOOT-INF/lib/*" \
      --module-path="./temp/BOOT-INF/lib/*" \
      /service.jar > /modules.txt
RUN jlink \
         --verbose \
         --add-modules "$(cat /modules.txt),jdk.unsupported" \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=2 \
         --output /bindir/jre

FROM --platform=$TARGETPLATFORM alpine
WORKDIR /workdir
EXPOSE 8080
COPY --from=jlink /bindir/jre /bindir/jre
COPY --from=build /workdir/build/libs/osrs-pathfinder-service-2-*-all.jar /bindir/service.jar
ENV JAVA_HOME=/bindir/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"
CMD ["java", "-jar", "/bindir/service.jar"]