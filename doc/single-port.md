# General
Jitsi Videobridge supports multiplexing multiple
media streams using a single UDP port. 

# Requirements
The use of channel-bundle and rtcp-mux is required. For peers which do not
support these (such as (currently) jigasi), Jitsi Videobridge will
automatically fallback to using the dynamically allocated ports (configurable
using the --min-port and --max-port arguments to jvb.sh).

# Configuration
Single port mode is enabled by default, with the port number being 10000. To
change the port, set the following property (set to -1 to disable):

### ```org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT=1234```
Type: integer
Default: 10000

By default, Jitsi Videobridge will try to use this port on all IP addresses
across all (non-loopback) network interfaces. This can be controlled using
the ice4j properties described [here](https://github.com/jitsi/ice4j/blob/master/doc/configuration.md)

### ```org.ice4j.ice.harvest.AbstractUdpHarvester.SO_RCVBUF```

Configures the receive buffer size for the sockets used for the single-port mode.
See the [ice4j documentation](https://github.com/jitsi/ice4j/blob/master/doc/configuration.md)
