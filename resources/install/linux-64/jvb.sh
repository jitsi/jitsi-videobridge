#!/bin/bash

if [[ "$1" == "--help"  || $# -lt 1 ]]; then
    echo "Usage:"
    echo "$0 secret [port] [host] [minPort] [maxPort]"
    echo
    echo "Default values are:"
    echo port="5275"
    echo host="localhost"
    echo minPort="10000"
    echo maxPort="20000"
    exit 1
fi

secret="$1"
port="$2"
host="$3"
minPort="$4"
maxPort="$5"

[ $port ] || port="5275"
[ $host ] || host="localhost"
[ $minPort ] || minPort="10000"
[ $maxPort ] || maxPort="20000"

mainClass="org.jitsi.videobridge.Main"

cp=$(JARS=(jitsi-videobridge.jar lib/*.jar); IFS=:; echo "${JARS[*]}")

java -Djava.library.path=lib/native/linux-64 -cp $cp $mainClass --secret="$secret" --port="$port" --host="$host" --min-port="$minPort" --max-port="$maxPort"

