#!/bin/bash

if [[ "$1" == "--help"  || $# -lt 1 ]]; then
    echo -e "Usage:"
    echo -e "$0 [OPTIONS], where options can be:"
    echo -e "\t--secret=SECRET\t sets the shared secret used to authenticate to the XMPP server"
    echo -e "\t--domain=DOMAIN\t sets the XMPP domain (default: none)"
    echo -e "\t--host=HOST\t sets the hostname of the XMPP server (default: domain, if domain is set, \"localhost\" otherwise)"
    echo -e "\t--port=PORT\t sets the port of the XMPP server (default: 5275)"
    echo -e "\t--subdomain=SUBDOMAIN\t sets the sub-domain used to bind JVB XMPP component (default: jitsi-videobridge)"
    echo -e "\t--apis=APIS where APIS is a comma separated list of APIs to enable. Currently supported APIs are 'xmpp' and 'rest'. The default is 'xmpp'."
    echo
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

mainClass="org.jitsi.videobridge.Main"
cp=$SCRIPT_DIR/jitsi-videobridge.jar:$SCRIPT_DIR/lib/*
libs="$SCRIPT_DIR/lib/native/macosx"
logging_config="$SCRIPT_DIR/lib/logging.properties"
videobridge_rc="$SCRIPT_DIR/lib/videobridge.rc"

if [ -f $videobridge_rc  ]; then
        source $videobridge_rc
fi


exec java $VIDEOBRIDGE_DEBUG_OPTIONS -XX:+UseConcMarkSweepGC -Djava.library.path=$libs -Djava.util.logging.config.file=$logging_config -cp $cp $mainClass $@
