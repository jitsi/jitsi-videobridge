# General
Jitsi Videobridge supports multiplexing multiple
media streams using a single UDP port. 

# Requirements
The use of channel-bundle and rtcp-mux is required. For peers which do not
support these (such as (currently) jigasi), Jitsi Videobridge will
automatically fallback to using the dynamically allocated ports (configurable
using the --min-port and --max-port arguments to jvb.sh).

#Configuration
To enable the single port mode, the following property needs to be set to
the port number to be used:

### *org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT=1234*
Type: integer
Default: none



By default, Jitsi Videobridge will try to use this port on all IP addresses
across all (non-loopback) network interfaces. This can be fine-controlled using
the generic ice4j properties:


### *org.ice4j.ice.harvest.ALLOWED_INTERFACES*
Default: all interfaces are allowed.

This property can be used to specify a ";"-separated list of interfaces which are
allowed to be used for candidate allocations.

### *org.ice4j.ice.harvest.BLOCKED_INTERFACES*
Default: no interfaces are blocked.

This property can be used to specify a ";"-separated list of interfaces which are
not allowed to be used for candidate allocations.

### *org.ice4j.ice.harvest.ALLOWED_ADDRESSES*
Default: all addresses are allowed.

This property can be used to specify a ";"-separated list of IP addresses which
are allowed to be used for candidate allocations.

### *org.ice4j.ice.harvest.BLOCKED_ADDRESSES*
Default: no addresses are blocked.

This property can be used to specify a ";"-separated list of IP addresses which
are not allowed to be used for candidate allocations.

### *org.ice4j.ipv6.DISABLED*
Type: boolean

Default: false

This property can be used to disable binding on IPv6 addresses.
