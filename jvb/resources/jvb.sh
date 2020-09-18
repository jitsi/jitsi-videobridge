#!/bin/bash

if [[ "$1" == "--help"  || $# -lt 1 ]]; then
    echo -e "Usage:"
    echo -e "$0 [OPTIONS], where options can be:"
    echo -e "\t--apis=APIS where APIS is a comma separated list of APIs to enable. Currently the only supported API is 'rest'. The default is none."
    echo
    exit 1
fi

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
if [ -z "$VIDEOBRIDGE_GC_TYPE" ]; then VIDEOBRIDGE_GC_TYPE=ConcMarkSweepGC; fi

exec java -Xmx$VIDEOBRIDGE_MAX_MEMORY $VIDEOBRIDGE_DEBUG_OPTIONS -XX:+Use$VIDEOBRIDGE_GC_TYPE -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -Djdk.tls.ephemeralDHKeySize=2048 $LOGGING_CONFIG_PARAM $JAVA_SYS_PROPS -cp $cp $mainClass $@
