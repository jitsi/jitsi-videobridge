# Intro

Jitsi Videobridge is an XMPP server component that allows for multiuser video
communication. Unlike the expensive dedicated hardware videobridges, Jitsi
Videobridge does not mix the video channels into a composite video stream, but
only relays the received video channels to all call participants. Therefore,
while it does need to run on a server with good network bandwidth, CPU
horsepower is not that critical for performance.

You can find documentation in the doc/ directory in the source tree.

# Running it

You can download binary packages for Debian/Ubuntu:
* [stable](https://download.jitsi.org/stable/) ([instructions](https://jitsi.org/Main/InstallJitsiVideobridgeDebianStableRepository))
* [testing](https://download.jitsi.org/testing/) ([instructions](https://jitsi.org/Main/InstallJitsiVideobridgeDebianTestingRepository))
* [nightly](https://download.jitsi.org/unstable/) ([instructions](https://jitsi.org/Main/InstallJitsiVideobridgeDebianNightlyRepository))

Maven assembly binaries:
* [assemblies](https://download.jitsi.org/jitsi-videobridge/)

Or you can clone the Git repo and run the JVB from source using maven.

```sh
HOST="Your XMPP server hostname/IP address goes here."
DOMAIN="Yhe JVB component name goes here."
PORT="the component port of your XMPP server goes here."
SECRET="The secret or password for the JVB component."
JVB_HOME="The path to your JVB clone."

mvn compile exec:java -Dexec.args="--host=$HOST --domain=$DOMAIN --port=$PORT --secret=$SECRET" -Djava.library.path=$JVB_HOME/lib/native/linux-64 -Djava.util.logging.config.file=$JVB_HOME/lib/logging.properties -Dnet.java.sip.communicator.SC_HOME_DIR_NAME=.jitsi-videobridge
```
