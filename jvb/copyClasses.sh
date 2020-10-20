#!/bin/bash
echo Copy ./target/classes to docker-jitsi-meet-cfg/myjvb/classes.
rm -rf ../../docker-jitsi-meet-cfg/myjvb/classes
cp -a ./target/classes ../../docker-jitsi-meet-cfg/myjvb/
