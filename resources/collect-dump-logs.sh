#!/bin/bash

# script that creates an archive in current folder
# containing the heap and thread dump and the current log file

PID=$(cat /var/run/jitsi-videobridge.pid)
if [ $PID ]; then
    echo "Jvb at pid $PID"
    STAMP=`date +%Y-%m-%d-%H%M`
    THREADS_FILE="/tmp/stack-${STAMP}-${PID}.threads"
    HEAP_FILE="/tmp/heap-${STAMP}-${PID}.bin"
    sudo -u jvb jstack ${PID} > ${THREADS_FILE}
    sudo -u jvb jmap -dump:live,format=b,file=${HEAP_FILE} ${PID}
    tar zcvf jvb-dumps-${STAMP}-${PID}.tgz ${THREADS_FILE} ${HEAP_FILE} /var/log/jitsi/jvb.log
    rm ${HEAP_FILE} ${THREADS_FILE}
else
    echo "JVB not running."
fi
