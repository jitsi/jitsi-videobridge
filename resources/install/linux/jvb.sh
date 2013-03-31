#!/bin/bash

if [[ "$1" == "--help"  || $# -lt 1 ]]; then
    echo "Usage:"
    echo "$0 secret [domain] [minPort] [maxPort] [host] [port]"
    echo
    echo domain is the XMPP domain name to use
    echo '{min,max}Port are ports which the videobridge'
    echo will allocate for media sessions
    echo host is the hostname of the XMPP server to connect to
    echo port is the port of the XMPP server to connect to
    echo
    echo "Default values are:"
    echo domain=none
    echo minPort="10000"
    echo maxPort="20000"
    echo host=domain, if domain is set, otherwise host=localhost
    echo port="5275"
    exit 1
fi

secret="$1"
domain="$2"
minPort="$3"
maxPort="$4"
host="$5"
port="$6"

[ $minPort ] || minPort="10000"
[ $maxPort ] || maxPort="20000"

mainClass="org.jitsi.videobridge.Main"
cp=$(JARS=(jitsi-videobridge.jar lib/*.jar); IFS=:; echo "${JARS[*]}")
libs="lib/native/linux"

line="java -Djava.library.path=$libs -cp $cp $mainClass"
line="$line --secret=$secret --min-port=$minPort --max-port=$maxPort"
if [[ -n $domain ]] ;then
    line="$line --domain=$domain"
fi
if [[ -n $host ]] ;then
    line="$line --host=$host"
fi
if [[ -n $port ]] ;then
    line="$line --port=$port"
fi

$line
