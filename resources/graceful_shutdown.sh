#!/bin/bash
#
# 1. The script issues shutdown command to the bridge over REST API.
#    If HTTP status code other than 200 is returned then it exits with 1.
# 2. If the code is ok then it checks if the bridge has exited.
# 3. If not then it polls bridge statistics until conference count drops to 0.
# 4. Gives some time for the bridge to shutdown. If it does not quit after that
#    time then it kills the process. If the process was successfully killed 0 is
#    returned and 1 otherwise.
#
#   Arguments:
#   "-p"(mandatory) the PID of jitsi Videobridge process
#   "-h"("http://localhost:8080" by default) REST requests host URI part
#   "-t"("25" by default) number of second we we for the bridge to shutdown
#       gracefully after conference count drops to 0
#   "-s"(disabled by default) enable silent mode - no info output
#
#   NOTE: script depends on the tool jq, used to parse json
#

# Initialize arguments
hostUrl="http://localhost:8080"
timeout=25
verbose=1

# Parse arguments
OPTIND=1
while getopts "p:h:t:s" opt; do
    case "$opt" in
    p)
        pid=$OPTARG
        ;;
    h)
        hostUrl=$OPTARG
        ;;
    t)
        timeout=$OPTARG
        ;;
    s)
        verbose=0
        ;;
    esac
done
shift "$((OPTIND-1))"

# Try the pid file, if no pid was provided as an argument.
# for systemd we use different pid file in a subfolder
if [ "$pid" = "" ] ;then
    if [ -f /var/run/jitsi-videobridge.pid ]; then
        pid=`cat /var/run/jitsi-videobridge.pid`
    else
        pid=`cat /var/run/jitsi-videobridge/jitsi-videobridge.pid`
    fi
fi

#Check if PID is a number
re='^[0-9]+$'
if ! [[ $pid =~ $re ]] ; then
   echo "error: PID is not a number" >&2; exit 1
fi

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

shutdownStatus=`curl -s -o /dev/null -H "Content-Type: application/json" -d '{ "graceful-shutdown": "true" }' -w "%{http_code}" "$hostUrl/colibri/shutdown"`
if [ "$shutdownStatus" == "200" ]
then
	printInfo "Graceful shutdown started"
	participantCount=`getParticipantCount`
	while [[ $participantCount -gt 0 ]] ; do
		printInfo "There are still $participantCount participants"
		sleep 10
		participantCount=`getParticipantCount`
	done

	sleep 5

	if ps -p $pid > /dev/null 2>&1
	then
		printInfo "It is still running, lets give it $timeout seconds"
		sleep $timeout
		if ps -p $pid > /dev/null 2>&1
		then
			printError "Bridge did not exit after $timeout sec - killing $pid"
			kill $pid
		fi
	fi
	# check for 3 seconds if we managed to kill
	for I in 1 2 3
	do
		if ps -p $pid > /dev/null 2>&1
		then
			sleep 1
		fi
	done
	if ps -p $pid > /dev/null 2>&1
	then
		printError "Failed to kill $pid"
		printError "Sending force kill to $pid"
		kill -9 $pid
		if ps -p $pid > /dev/null 2>&1
		then
			printError "Failed to force kill $pid, giving up."
			exit 1
		fi
	fi
    rm -f /var/run/jitsi-videobridge.pid
    rm -f /var/run/jitsi-videobridge/jitsi-videobridge.pid
	printInfo "Bridge shutdown OK"
	exit 0
else
	printError "Invalid HTTP status for shutdown request: $shutdownStatus"
	exit 1
fi


