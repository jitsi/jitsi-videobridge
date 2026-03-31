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

# Dynamically set max memory if not already defined
if [ -z "$VIDEOBRIDGE_MAX_MEMORY" ]; then
    TOTAL_MEM=$(free -m 2>/dev/null | awk '/Mem:/ {print $2}')

    if [ -n "$TOTAL_MEM" ]; then
        # Use 75% of total memory to avoid OOM on small setups
        VIDEOBRIDGE_MAX_MEMORY=$(($TOTAL_MEM * 75 / 100))m
    else
        # Fallback value
        VIDEOBRIDGE_MAX_MEMORY=1024m
    fi
fi
if [ -z "$VIDEOBRIDGE_GC_TYPE" ]; then 
   VIDEOBRIDGE_GC_TYPE=G1GC; 
fi


echo "Max memory that will be used: $VIDEOBRIDGE_MAX_MEMORY"

exec java -Xmx$VIDEOBRIDGE_MAX_MEMORY \
    $VIDEOBRIDGE_DEBUG_OPTIONS \
    -XX:+Use$VIDEOBRIDGE_GC_TYPE \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/tmp \
    -Djdk.tls.ephemeralDHKeySize=2048 \
    $LOGGING_CONFIG_PARAM \
    $JAVA_SYS_PROPS \
    -cp $cp \
    $mainClass "$@"
