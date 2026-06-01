FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive
ENV COURSIER_BIN_DIR=/root/.local/share/coursier/bin
ENV PATH=${COURSIER_BIN_DIR}:${PATH}

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl git gzip ca-certificates bash \
  && rm -rf /var/lib/apt/lists/*

RUN curl -fLo /usr/local/bin/cs https://git.io/coursier-cli-linux \
  && chmod +x /usr/local/bin/cs \
  && cs install sbt

# Publish Lorikeet rule in container
WORKDIR /scalafix-build
COPY scalafix/ /scalafix-build/
RUN sbt publishLocal

WORKDIR /workspace/server

EXPOSE 8080

CMD ["sbt", "~reStart"] 