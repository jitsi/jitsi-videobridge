#!/bin/bash
pushd .
cd /c/Users/hase/.jitsi-meet-cfg
rm -rf /c/Users/hase/.jitsi-meet-cfg/myjvb
unzip /c/Home/hase/debian/jitsi-videobridge/jvb/target/jitsi-videobridge-2.1-SNAPSHOT-archive.zip
mv jitsi-videobridge-2.1-SNAPSHOT myjvb
echo このフォルダがdockerで動くjvb。debian/jitsi-videobridgeが上書きする。> myjvb/readme.txt
cp /c/Home/hase/debian/jitsi-videobridge/jvb/resources/jvb.sh .
popd
