# Focus Fence accountability server. Build from the REPO ROOT (the server compiles the phone's pure dns/ code):
#   docker build -f server/Dockerfile -t focusfence-server .
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew ./
COPY server server
COPY app/src/main/java/app/focusfence/dns app/src/main/java/app/focusfence/dns
RUN ./gradlew -p server installDist --no-daemon -x test

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S ff && adduser -S -G ff -h /data ff && mkdir -p /data && chown ff:ff /data
COPY --from=build /src/server/build/install/focusfence-server /opt/focusfence-server
USER ff
# 0.0.0.0 INSIDE the container; publish it on 127.0.0.1 or behind a TLS proxy (docker-compose.yml does).
ENV FF_DATA_DIR=/data FF_HOST=0.0.0.0 FF_PORT=8080 JAVA_OPTS="-Xmx256m -Xss512k"
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/opt/focusfence-server/bin/focusfence-server"]
