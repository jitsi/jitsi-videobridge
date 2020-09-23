#!/bin/bash
echo Copy ./target/classes to .jitsi-meet-cfg/myjvb/classes.
rm -rf /c/Users/hase/.jitsi-meet-cfg/myjvb/classes
cp -a /c/Home/hase/debian/jitsi-videobridge/jvb/target/classes /c/Users/hase/.jitsi-meet-cfg/myjvb/
