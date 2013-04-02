#!/bin/bash

if [[ "$1" == "--help"  || $# -lt 1 ]]; then
    echo -e "Usage:"
    echo -e "$0 [OPTIONS], where options can be:"
    echo -e "\t--secret=SECRET\t sets the shared secret used to authenticate to the XMPP server"
    echo -e "\t--domain=DOMAIN\t sets the XMPP domain (default: host, if host is set, none otherwise)"
    echo -e "\t--min-port=MP\t sets the min port used for media (default: 10000)"
    echo -e "\t--max-port=MP\t sets the max port used for media (default: 20000)"
    echo -e "\t--host=HOST\t sets the hostname of the XMPP server (default: localhost)"
    echo -e "\t--port=PORT\t sets the port of the XMPP server (default: 5275)"
    echo
    exit 1
fi

mainClass="org.jitsi.videobridge.Main"
cp=$(JARS=(jitsi-videobridge.jar lib/*.jar); IFS=:; echo "${JARS[*]}")
libs="lib/native/linux"

java -Djava.library.path=$libs -cp $cp $mainClass $@
