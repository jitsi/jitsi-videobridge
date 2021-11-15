#!/bin/bash

SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"

mainClass="org.jitsi.videobridge.MainKt"
cp=$SCRIPT_DIR/jitsi-videobridge.jar:$SCRIPT_DIR/lib/*
logging_config="$SCRIPT_DIR/lib/logging.properties"
videobridge_rc="$SCRIPT_DIR/lib/videobridge.rc"

# if there is a logging config file in lib folder use it (running from source)
if [ -f $logging_config ]; then
    LOGGING_CONFIG_PARAM="-Djava.util.logging.config.file=$logging_config"
fi

if [ -f $videobridge_rc  ]; then
        source $videobridge_rc
fi

if [ -z "$VIDEOBRIDGE_MAX_MEMORY" ]; then VIDEOBRIDGE_MAX_MEMORY=3072m; fi
if [ -z "$VIDEOBRIDGE_GC_TYPE" ]; then
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    # Experiments indicate ConcMarkSweepGC is better for JVM in Java 1.8;
    # G1GC is better in later Java versions.  (This only considers currently-supported versions.)
    case $JAVA_VERSION in
        1.8*)
            VIDEOBRIDGE_GC_TYPE=ConcMarkSweepGC;
            ;;
        *)
            VIDEOBRIDGE_GC_TYPE=G1GC;
            ;;
    esac
fi

exec java -Xmx$VIDEOBRIDGE_MAX_MEMORY $VIDEOBRIDGE_DEBUG_OPTIONS -XX:+Use$VIDEOBRIDGE_GC_TYPE -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -Djdk.tls.ephemeralDHKeySize=2048 $LOGGING_CONFIG_PARAM $JAVA_SYS_PROPS -cp $cp $mainClass $@
