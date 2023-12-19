#!/bin/bash
#
# 1. The script issues shutdown command to the bridge over REST API.
#    If HTTP status code other than 200 is returned then it exits with 1.
# 2. If the code is ok then it checks if the bridge has exited.
# 3. If not then it polls bridge statistics until participant count drops to 0.
# 4. Gives some time for the bridge to shutdown. If it does not quit after that
#    time then it kills the process. If the process was successfully killed 0 is
#    returned and 1 otherwise.
#
#   Arguments:
#   "-h"("http://localhost:8080" by default) REST requests host URI part
#   "-s"(disabled by default) enable silent mode - no info output
#
#   NOTE: script depends on the tool jq, used to parse json
#

# Initialize arguments
hostUrl="http://localhost:8080"
verbose=1

# Parse arguments
OPTIND=1
while getopts "p:h:t:s" opt; do
    case "$opt" in
    h)
        hostUrl=$OPTARG
        ;;
    s)
        verbose=0
        ;;
    esac
done
shift "$((OPTIND-1))"

# Returns local participant count by calling JVB REST statistics API and extracting
# participant count from JSON stats text returned.
function getParticipantCount {
    # Total number of participants minus the remote (octo) participants
    curl -s "$hostUrl/colibri/stats"| jq '.participants - .octo_endpoints'
}

# Prints info messages
function printInfo {
	if [ "$verbose" == "1" ]
	then
		echo "$@"
	fi
}

# Prints errors
function printError {
	echo "$@" 1>&2
}

function waitForPid {
    while [ -d /proc/$1 ] ;do
        echo "PID $1 still exists"
	sleep 10
    done
    echo "PID $1 is done"
}

JVB_PID=$(ps aux | grep java | grep jitsi-videobridge.jar | awk '{print $2}')

shutdownStatus=`curl -s -o /dev/null -H "Content-Type: application/json" -d '{ "graceful-shutdown": "true" }' -w "%{http_code}" "$hostUrl/colibri/shutdown"`
if [ "$shutdownStatus" == "200" ]
then
	printInfo "Graceful shutdown started"
	participantCount=`getParticipantCount`
	while [[ $participantCount -gt 0 ]] ; do
		printInfo "There are still $participantCount participants"
		sleep 10
		participantCount=`getParticipantCount`
		if [[ $? -gt 0 ]] ; then
			printInfo "Failed to get participant count, bridge may be already shutdown, waiting on pid $JVB_PID"
			waitForPid $JVB_PID
			exit 0
		fi
	done

	echo "Waiting for bridge pid $JVB_PID to finish shutting down"
	waitForPid $JVB_PID
	printInfo "Bridge shutdown OK"
	exit 0
else
	printError "Invalid HTTP status for shutdown request: $shutdownStatus"
	exit 1
fi


