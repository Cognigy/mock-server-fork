#
# MockServer Dockerfile — standard image (built for BOTH releases and snapshots).
#
# This is the buildx context the release pipeline (scripts/release/components/docker.sh) and the
# per-merge snapshot step (.buildkite/scripts/steps/java-docker-push-snapshot.sh) push as
# mockserver/mockserver:<ver> / :snapshot. It bakes an Application Class Data Sharing (AppCDS)
# archive over the MockServer + library classes via a training run at build time, cutting container
# time-to-ready by roughly a third versus an untuned JVM while remaining the real HotSpot JVM
# (100% feature parity).
#
# Notes:
# - An AppCDS archive is tied to the exact JDK build it was trained with (same coupling as the
#   JDK 25 AOT cache in docker/aot/Dockerfile), so the trimmed JDK runtime is baked into this image
#   alongside the archive. Multi-arch builds each train + bake their own archive.
# - There is no distroless/java17 base that ships a matching CDS-capable runtime for a baked
#   archive, so a jlink-trimmed JDK 25 runtime (same module set as the binary bundle, see
#   scripts/build-binary-bundle.sh and docs/code/cli.md) is copied onto distroless/java-base — the
#   same base+digest as docker/aot/Dockerfile.
# - The JVM inside this image is JDK 25; the MockServer library itself is still compiled to the
#   Java 17 bytecode floor (AGENTS.md Java Compatibility Policy). Running that jar on a newer JVM is
#   fully supported — the JDK bump here is a runtime-only choice, independent of the compile target.
# - Runtime uses -XX:SharedArchiveFile with the default -Xshare:auto semantics, so a missing or
#   unusable archive logs a warning and starts normally (safe for the DEFAULT image — it never
#   hard-fails on the archive, unlike the AOT variant's strict modes).
# - netty-tcnative is intentionally omitted here (as in the prior docker/local image): TLS uses the
#   JDK provider. docker/Dockerfile (the public download-mode reference) still installs tcnative.
#
# https://github.com/mock-server/mockserver-monorepo
# https://www.mock-server.com
#

# AppCDS build stage — jlink-trimmed JDK 25 runtime + training run producing the AppCDS archive
FROM eclipse-temurin:25-jdk-noble AS appcds-build

COPY mockserver-netty-jar-with-dependencies.jar /mockserver-netty-jar-with-dependencies.jar

# Same module set validated end-to-end for the jlink binary bundle (scripts/build-binary-bundle.sh,
# docs/code/cli.md): java.se aggregator + Netty Unsafe + TLS crypto + DNS + zipfs. On JDK 25 jlink
# uses the --compress=zip-<level> form (zip-6 = the default ZIP level, same spelling docker/aot uses
# on JDK 25); the legacy numeric --compress=2 form was deprecated after JDK 17 and is removed on
# JDK 25. A jlink image does not carry the JDK's default CDS base archive, so -Xshare:dump
# regenerates it from the bundled lib/classlist — the dynamic AppCDS archive below is layered on
# that base, and the base is carried into the runtime image with /runtime.
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.naming.dns,jdk.zipfs \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /runtime \
 && /runtime/bin/java -Xshare:dump

# Training run (dynamic AppCDS, JEP 350): start the server with -XX:ArchiveClassesAtExit, wait until
# the status endpoint answers via the bundled HealthCheck (which also drives the post-bind warmup so
# the first-request class burst is archived too), then stop cleanly so the JVM writes the archive at
# exit. The `ls` fails the build if the archive was not produced.
RUN /runtime/bin/java -XX:ArchiveClassesAtExit=/mockserver.jsa \
      -jar /mockserver-netty-jar-with-dependencies.jar -p 1080 & \
    SERVER_PID=$!; \
    for i in $(seq 1 240); do \
      /runtime/bin/java -cp /mockserver-netty-jar-with-dependencies.jar org.mockserver.cli.HealthCheck && break; \
      sleep 0.5; \
    done; \
    kill -TERM "$SERVER_PID" && wait "$SERVER_PID" || true; \
    ls -l /mockserver.jsa

# download GraalJS JARs — enables the JAVASCRIPT template engine at runtime. FORK CHANGE: upstream
# docker/local ships without GraalJS (JS templating lives only in docker/graaljs); this fork bakes it
# into the standard perf image because the WebSocket mocking use case authors matcher-response
# templates in JavaScript. Pinned to 25.1.3 to match the org.graalvm.polyglot version the code is
# compiled against (mockserver/pom.xml) — do NOT let this drift from the pom. The jars land in /libs,
# which the entrypoint classpath already includes; MockServer detects polyglot on the classpath and
# enables the JS engine. GraalJS runs in Truffle interpreted mode on this stock (non-Graal) JDK.
FROM alpine:3.24 AS graaljs
# Harden Maven Central downloads against transient DNS/connection blips (GNU wget honours /etc/wgetrc;
# BusyBox wget ignores it, so this is a safe no-op there).
RUN printf 'tries=5\ntimeout=60\nwaitretry=10\nretry_on_host_error=on\nretry_connrefused=on\n' >> /etc/wgetrc \
    && apk add --update ca-certificates wget
ARG GRAALJS_VERSION=25.1.3
# GraalVM 25.x coordinates: icu4j is under org.graalvm.shadowed; jniutils is a separate sdk artifact
# required by truffle-runtime; the JSR-223 js-scriptengine jar no longer exists.
RUN mkdir -p /graaljs && \
    wget -O /graaljs/polyglot.jar "https://repo1.maven.org/maven2/org/graalvm/polyglot/polyglot/$GRAALJS_VERSION/polyglot-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/js-language.jar "https://repo1.maven.org/maven2/org/graalvm/js/js-language/$GRAALJS_VERSION/js-language-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/truffle-api.jar "https://repo1.maven.org/maven2/org/graalvm/truffle/truffle-api/$GRAALJS_VERSION/truffle-api-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/truffle-runtime.jar "https://repo1.maven.org/maven2/org/graalvm/truffle/truffle-runtime/$GRAALJS_VERSION/truffle-runtime-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/truffle-compiler.jar "https://repo1.maven.org/maven2/org/graalvm/truffle/truffle-compiler/$GRAALJS_VERSION/truffle-compiler-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/collections.jar "https://repo1.maven.org/maven2/org/graalvm/sdk/collections/$GRAALJS_VERSION/collections-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/nativeimage.jar "https://repo1.maven.org/maven2/org/graalvm/sdk/nativeimage/$GRAALJS_VERSION/nativeimage-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/word.jar "https://repo1.maven.org/maven2/org/graalvm/sdk/word/$GRAALJS_VERSION/word-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/jniutils.jar "https://repo1.maven.org/maven2/org/graalvm/sdk/jniutils/$GRAALJS_VERSION/jniutils-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/regex.jar "https://repo1.maven.org/maven2/org/graalvm/regex/regex/$GRAALJS_VERSION/regex-$GRAALJS_VERSION.jar" && \
    wget -O /graaljs/icu4j.jar "https://repo1.maven.org/maven2/org/graalvm/shadowed/icu4j/$GRAALJS_VERSION/icu4j-$GRAALJS_VERSION.jar"

# runtime image — distroless base + trimmed JDK 25 runtime + jar + AppCDS archive
# (digest-pinned per repo convention; update the digest together with the Temurin 25 build, since
# the baked AppCDS archive is specific to the JDK build it was trained with — same base+digest as
# docker/aot/Dockerfile)
FROM gcr.io/distroless/java-base-debian12:nonroot@sha256:a9930cad62d02853d7f3dede7281c4b916cbf74493c2d8d38564121aad92bf6c

# maintainer details
LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

# MCP registry ownership annotation — proves this image backs the com.mock-server/mockserver
# entry on the official MCP registry (https://registry.modelcontextprotocol.io). REQUIRED for the
# server.json OCI package publish (scripts/release/components/mcp.sh): the registry rejects the
# publish with a 400 "missing required annotation" if the published image lacks this label. This is
# the Dockerfile the release actually builds + pushes as mockserver/mockserver:<ver> (docker/local
# is the buildx context in docker.sh), so the label MUST live here — not only in docker/Dockerfile.
# The namespace is DNS-verified against mock-server.com. See docs/operations/mcp-registry-publishing.md.
LABEL io.modelcontextprotocol.server.name="com.mock-server/mockserver"

# expose ports.
EXPOSE 1080

COPY --from=appcds-build /runtime /usr/lib/jvm/temurin25-trimmed
COPY --from=appcds-build /mockserver-netty-jar-with-dependencies.jar /
COPY --from=appcds-build /mockserver.jsa /

# GraalJS jars → /libs (entrypoint classpath already includes /libs/*). FORK CHANGE, see the graaljs
# stage above. The baked AppCDS archive was trained WITHOUT graal on the classpath; -Xshare:auto
# degrades gracefully (the graal classes simply load normally, uncovered by the archive).
COPY --from=graaljs /graaljs/*.jar /libs/

# don't run MockServer as root
USER nonroot

# -XX:MaxRAMPercentage=75.0 caps the JVM heap at 75% of the container memory limit so the
# in-memory request/expectation rings size off a bounded heap. It only applies when the user has
# NOT set an explicit heap, so it is a safe default. To change the cap, set an explicit -Xmx (via
# JAVA_TOOL_OPTIONS or -Xmx) — that disables MaxRAMPercentage. Setting a different MaxRAMPercentage
# via JAVA_TOOL_OPTIONS does NOT take effect: JAVA_TOOL_OPTIONS is prepended before these args, so
# this entrypoint flag is applied last and wins.
# -XX:SharedArchiveFile loads the baked AppCDS archive. -Xshare:auto (the JVM default) means a
# missing or unusable archive (e.g. bind-mounted away, corrupt, or an arch mismatch) logs a warning
# and starts normally rather than failing — the graceful-degradation guarantee this DEFAULT image
# relies on (unlike the AOT variant, this image cannot soft-fail at release time).
ENTRYPOINT ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-Dfile.encoding=UTF-8", "-XX:MaxRAMPercentage=75.0", "-XX:SharedArchiveFile=/mockserver.jsa", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT=1080

# JS sandbox hardening (FORK CHANGE, pairs with the bundled GraalJS above). GraalJS guest scripts run
# under HostAccess.ALL, gated ONLY by mockserver.javascriptAllowedClasses. Upstream default is empty
# = unrestricted, so a template could reach java.lang.Runtime / ProcessBuilder via Java.type(...).
# A non-empty allow-list activates allow-list mode and refuses every class not on it; "none" can never
# equal a real class name, so this denies ALL Java.type(...) reach-through while the host-bound
# jsonPath / xPath helpers and JS built-ins (JSON, etc.) — which are NOT resolved via Java.type — keep
# working. WebSocket/response templates that only echo request/frame data (the intended use) are
# unaffected. Override at runtime (-e MOCKSERVER_JAVASCRIPT_ALLOWED_CLASSES=...) if a template
# legitimately needs specific Java classes.
ENV MOCKSERVER_JAVASCRIPT_ALLOWED_CLASSES=none

# Dashboard usage analytics (PostHog Cloud EU, cookieless). EMPTY by default so a plain
# `docker build` — i.e. forks / self-hosters building this Dockerfile directly — ships analytics
# INERT (the dashboard's activation gate requires a non-empty endpoint AND key). Only the
# project's official release build injects the real values via --build-arg
# (scripts/release/components/docker.sh). The key is a write-only public ingest key, safe to
# expose. A user can always override at runtime with `-e MOCKSERVER_DASHBOARD_ANALYTICS_*`.
ARG DASHBOARD_ANALYTICS_ENDPOINT=
ARG DASHBOARD_ANALYTICS_KEY=
ENV MOCKSERVER_DASHBOARD_ANALYTICS_ENDPOINT=${DASHBOARD_ANALYTICS_ENDPOINT}
ENV MOCKSERVER_DASHBOARD_ANALYTICS_KEY=${DASHBOARD_ANALYTICS_KEY}
# Labels analytics events by the artefact they came from. A plain, non-secret constant — safe to
# commit directly: it is only ever sent when analytics is already active (which still requires the
# injected endpoint+key above), so forks building this Dockerfile directly stay inert.
ENV MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION=docker-standard

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-cp", "/mockserver-netty-jar-with-dependencies.jar", "org.mockserver.cli.HealthCheck"]

CMD []
