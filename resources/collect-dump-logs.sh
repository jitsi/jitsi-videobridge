#!/bin/bash

# script that creates an archive in current folder
# containing the heap and thread dump and the current log file

JVB_HEAPDUMP_PATH="/tmp/java_*.hprof"
STAMP=`date +%Y-%m-%d-%H%M`
PID_PATH="/var/run/jitsi-videobridge.pid"

[ -e $PID_PATH ] && PID=$(cat $PID_PATH)
if [ $PID ]; then
    echo "Jvb at pid $PID"
    THREADS_FILE="/tmp/stack-${STAMP}-${PID}.threads"
    HEAP_FILE="/tmp/heap-${STAMP}-${PID}.bin"
    sudo -u jvb jstack ${PID} > ${THREADS_FILE}
    sudo -u jvb jmap -dump:live,format=b,file=${HEAP_FILE} ${PID}
    tar zcvf jvb-dumps-${STAMP}-${PID}.tgz ${THREADS_FILE} ${HEAP_FILE} /var/log/jitsi/jvb.log
    rm ${HEAP_FILE} ${THREADS_FILE}
else
    if [ -e $JVB_HEAPDUMP_PATH ]; then
        echo "JVB not running, but previous heap dump found."
        tar zcvf jvb-dumps-${STAMP}-crash.tgz $JVB_HEAPDUMP_PATH /var/log/jitsi/jvb.log
        rm ${JVB_HEAPDUMP_PATH}
    else
        echo "JVB not running."
    fi
fi
