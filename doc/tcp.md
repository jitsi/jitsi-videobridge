# General
Jitsi Videobridge can accept and route RTP traffic over TCP. 
The feature is on by default and TCP addresses will 
automatically be returned as ICE candidates via 
COLIBRI.

# Requirements
The use of channel-bundle and rtcp-mux is required for TCP 
candidates to be generated.

#Configuration
By default TCP support is enabled on port 443 with fallback 
to port 4443. A fallback would occur in case something else, 
like a web server is already listening on port 443. Note 
however, that the very point of using TCP is to simulate http
traffic in a number of environments where it is the only allowed 
form of communication, so you may want to make sure that 
port 443 will be free on the machine where you are running the 
bridge. 

In order to avoid binding to port 443 directly:
* Redirect 443 to 4443 by external means
* Use TCP_HARVESTER_PORT=4443
* Use TCP_HARVESTER_MAPPED_PORT=443
See below for details.



The following properties can be set in 
$HOME/.sip-communicator/sip-communicator.properties to control 
the TCP-related functionality.


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
Jitsi Videobridge will generate candidates with protocol "ssltcp", and the TCP
harvester will expect connecting clients to send a special pseudo-SSL
ClientHello message right after they connect, before any STUN messages. Chrome
sends this message if a candidate in its SDP offer has the "ssltcp"
protocol.

If the option is disabled (the property is set to false), jitsi-videobridge
will generate regular "tcp" candidates and will expect to receive STUN messages
right away.


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


### *org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT*
Default: none
Type: integer between 1 and 65535

If this property is set, Jitsi Videobridge will use the given port
in the candidates that it advertises, but the actual port it listens on
will not change.


### *org.jitsi.videobridge.NAT_HARVESTER_LOCAL_ADDRESS*
### *org.jitsi.videobridge.NAT_HARVESTER_PUBLIC_ADDRESS*
Default: none

If these to properties are configured, Jitsi Videobridge will
generate additional srflx candidates for each candidate with
the local address configured.

This is useful if Jitsi Videobridge is running behind a NAT with
the necessary ports forwarded. For example, if Jitsi Videobridge
is running on 10.0.0.1 behind a NAT, and the public address is 2.2.2.2,
NAT_HARVESTER_LOCAL_ADDRESS=10.0.0.1 and
NAT_HARVESTER_PUBLIC_ADDRESS=2.2.2.2
should be configured.


# Examples
## None of the TCP specific properties set, successful bind on port 443
Jitsi Videobridge will bind to port 443 and announce port 443.

## None of the TCP specific properties set, failure to bind on port 443 (lack of privileges, or web-server already bound on 443)
Jitsi Videobridge will bind to port 4443 and announce port 4443.

## To bind on port 4443 and announce port 443 set the following
org.jitsi.videobridge.TCP_HARVESTER_PORT=4443

org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT=443

In order for this to work, port forwarding needs to be set up by
external means (e.g. with iptables), so that port 443 is forwarded
to port 4443.
