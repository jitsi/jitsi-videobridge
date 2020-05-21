ARG JITSI_REPO=jitsi

FROM debian:stretch as builder

ADD https://mirrors.supportex.net/apache/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.tar.gz /tmp/maven.tar.gz
ADD . /src
ADD docker/build.sh /tmp

RUN \
	apt-get update && \
	apt-get install --no-install-recommends -y openjdk-8-jdk

RUN \
	mkdir /opt/maven && \
	tar xvf /tmp/maven.tar.gz --strip 1 -C /opt/maven

RUN \
	/tmp/build.sh

FROM ${JITSI_REPO}/base-java

COPY resources/jvb.sh /usr/share/jitsi-videobridge/
COPY docker/rootfs/ /
COPY --from=builder /src/target/jitsi-videobridge-*-jar-with-dependencies.jar /usr/share/jitsi-videobridge/jitsi-videobridge.jar

VOLUME /config
