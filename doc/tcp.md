# General
Jitsi Videobridge can accept and route RTP traffic over TCP. 
The feature is on by default and TCP addresses will 
automatically be returned as ICE candidates via 
COLIBRI.

# Requirements
The use of channel-bundle and rtcp-mux is required for TCP candidates
to be generated.

#Configuration
By default TCP support is enabled on port 443 with fallback to port 4443. The
following properties can be set in $HOME/.sip-communicator/sip-communicator.properties
to control the TCP-related functionality.


### *org.jitsi.videobridge.DISABLE_TCP_HARVESTER*
Type: boolean

Default: false

Disables TCP support.

### *org.jitsi.videobridge.TCP_HARVESTER_PORT*
Type: integer between 1 and 65535

Default: 443 with fallback to 4443.

Configures the port number to be used by the TCP harvester. If this property is
not set (and the TCP harvester is enabled), jitsi-videobridge will first try to
bind on port 443, and if this fails, it will try port 4443 instead. If the
property is set, it will only try to bind to the specified port, with no
fallback.

### *org.jitsi.videobridge.TCP_HARVESTER_SSLTCP*
Type: boolean

Default: true

Configures the use of "ssltcp" candidates. If this option is enabled,
jitsi-videobridge will generate candidates with protocol "ssltcp", and the TCP
harvester will expect connecting clients to send a special pseudo-SSL
ClientHello message right after they connect, before any STUN messages. Chrome
sends this message if a candidate in its SDP offer has the "ssltcp"
protocol.

If the option is disabled (the property is set to false), jitsi-videobridge
will generate regular "tcp" candidates and will expect to receive STUN messages
right away.


### *org.ice4j.ice.harvest.ALLOWED_INTERFACES*
Default: all interfaces are allowed.

This property can be used to specify a ";"-separated list of interfaces which will
be used to bind on. All IP addresses on the specified interfaces will be used.

### *org.ice4j.ipv6.DISABLED*
Type: boolean

Default: false

This property can be used to disable binding on IPv6 addresses.
