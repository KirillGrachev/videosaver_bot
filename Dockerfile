# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -B -T 1C package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# The extraction engine lives OUTSIDE the JVM on purpose: yt-dlp and gallery-dl are
# fast-moving projects whose whole value is being current, so the image installs them
# at build time (pin versions here if you want reproducible builds) and ffmpeg does
# the merging/transcoding. Missing tools degrade the bot to the built-in OpenGraph
# extractor instead of killing it - but a downloader image without them makes no sense.
# Pinned for reproducible builds; bump deliberately (yt-dlp breaks when sites redesign,
# so a stale pin shows up as extraction failures - update it like a dependency, not "whenever").
ARG YTDLP_VERSION=2026.08.19
ARG GALLERYDL_VERSION=1.32.14
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip ffmpeg \
    && pip3 install --no-cache-dir --break-system-packages \
         yt-dlp==${YTDLP_VERSION} gallery-dl==${GALLERYDL_VERSION} \
    && apt-get clean && rm -rf /var/lib/apt/lists/* \
    && yt-dlp --version && gallery-dl --version && ffmpeg -version | head -1

# dist = thin saver-bot.jar + saver-launcher.jar + libs/ + sha256 manifest
COPY --from=build /build/app/target/dist/ ./
RUN useradd --system --create-home bot && chown -R bot:bot /app
USER bot
ENV TZ=UTC
EXPOSE 8080
# Liveness without an open port: the bot touches data/bot.liveness on every heartbeat
# (status.heartbeat-interval, 5m by default). The first field of the file is the epoch
# in milliseconds; staler than 15 minutes means the JVM is alive but the bot is hung -
# exactly the failure /healthz cannot report when the web app is disabled.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD ["sh", "-c", "f=/app/data/bot.liveness; test -f \"$f\" && [ $(( $(date +%s%3N) - $(cut -d' ' -f1 \"$f\") )) -lt 900000 ]"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "saver-launcher.jar"]
