# Intro

Jitsi Videobridge is a WebRTC-compatible Selective Forwarding Unit (SFU), i.e. a
multimedia router. It is one of the backend components in the [Jitsi Meet](https://github.com/jitsi/jitsi-meet) stack.

You can find more documentation in the
[doc/ directory in the source tree](https://github.com/jitsi/jitsi-videobridge/tree/master/doc) and in the
[Jitsi Meet Handbook](https://jitsi.github.io/handbook/).

If you have questions about the project, please post on the [Jitsi Community Forum](https://community.jitsi.org/).
GitHub issues are only used to track actionable items.

# Packages

## Debian/Ubuntu
You can download binary packages for Debian/Ubuntu:
* [stable](https://download.jitsi.org/stable/) ([instructions](https://jitsi.org/downloads/ubuntu-debian-installations-instructions/))
* [testing](https://download.jitsi.org/testing/) ([instructions](https://jitsi.org/downloads/ubuntu-debian-installations-instructions-for-testing/))
* [nightly](https://download.jitsi.org/unstable/) ([instructions](https://jitsi.org/downloads/ubuntu-debian-installations-instructions-nightly/))

## Building your own package
You can build a custom package with just `mvn install` in the root directory of the repo. Look for the package in
`jvb/target/jitsi-videobridge-2.1-SNAPSHOT-archive.zip`.

# Running locally
You can run jitsi-videobridge locally with maven (or in your IDE). First create a `~/.jvb/jvb.conf` to configure the
environment to connect to and other options (see
[reference.conf](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf) for the
available options).

```sh
JVB_HOME="/path/to/the/cloned/repo"
JVB_CONFIG_DIR_LOCATION="~/"
JVB_CONFIG_DIR_NAME=".jvb"
JVB_CONFIG_FILE="$JVB_CONFIG_DIR_LOCATION/$JVB_JVB_CONFIG_DIR_NAME/jvb.conf"

mvn compile exec:exec -Dexec.executable=java -Dexec.args="-cp %classpath org.jitsi.videobridge.MainKt -Djava.library.path=$JVB_HOME/lib/native/linux-64 -Djava.util.logging.config.file=$JVB_HOME/lib/logging.properties -Dnet.java.sip.communicator.SC_HOME_DIR_LOCATION=$JVB_CONFIG_DIR_LOCATION -Dnet.java.sip.communicator.SC_HOME_DIR_NAME=$JVB_CONFIG_DIR_NAME -Dconfig.file=$JVB_CONFIG_FILE"
```

# Configuration
Application level configuration comes from a config file, usually installed in `/etc/jitsi/videobridge/jvb.conf`. The
values in that file override the defaults defined in
[reference.conf](https://github.com/jitsi/ice4j/blob/master/src/main/resources/reference.conf).

## Debian
On debian systems the `/etc/jitsi/videobridge/config` file can be used to set configuration for the Java virtual machine.
Notable examples:
```commandline
# Increase the java heap to 8GB
VIDEOBRIDGE_MAX_MEMORY=8192m
# Change the garbage collector (defaults to G1GC)
VIDEOBRIDGE_GC_TYPE=G1GC
```
