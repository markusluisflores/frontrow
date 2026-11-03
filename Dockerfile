# The local demo image (spec §2: local only). CI runs the tests, Spotless and SpotBugs; this build skips them, because
# a Windows checkout's CRLF files would fail Spotless inside Linux.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
# The wrapper needs unzip: without it, it fetches the tar.gz and fails the zip's distributionSha256Sum.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
RUN ./mvnw -B -q -DskipTests -Dspotless.check.skip=true -Dspotbugs.skip=true package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 frontrow
COPY --from=build /src/target/frontrow-*.jar app.jar
USER frontrow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
