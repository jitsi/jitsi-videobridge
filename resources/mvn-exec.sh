#!/bin/sh -e

readonly CONFIG=$1
if [ -z ${CONFIG+x} -o ! -f $1 ]; then
   echo 'Config file missing.'
   exit 1
fi

. $CONFIG

exec mvn compile exec:exec -Dexec.executable=java -Dexec.args="-cp %classpath ${JAVA_SYS_PROPS} org.jitsi.videobridge.Main --domain=\"${JVB_HOSTNAME}\" --host=\"${JVB_HOST}\" --port=\"${JVB_PORT}\" --secret=\"${JVB_SECRET}\" --opts=${JVB_OPTS}"
