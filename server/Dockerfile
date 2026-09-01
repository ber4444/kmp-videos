# Build the fat-ish distribution with Gradle, then run it on a JRE-only image so
# the Soniox key never sits in a layer alongside a toolchain that could print it.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# Copy the wrapper and build scripts first so dependency resolution caches across
# source-only changes.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY server/build.gradle.kts ./server/

# `settings.gradle.kts` includes the Android/KMP modules, whose plugins are not
# needed (and not resolvable) here. -PserverOnly prunes them; see settings.gradle.kts.
RUN ./gradlew --no-daemon -PserverOnly=true :server:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY server/src ./server/src
RUN ./gradlew --no-daemon -PserverOnly=true :server:installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server ./
ENV PORT=8080
EXPOSE 8080
CMD ["./bin/server"]
