# General
Jitsi Videobridge can accept and route RTP traffic over ICE/TCP. 
The feature is off by default. When turned on, the bridge will listen
on a TCP port and advertise ICE candidates of type TCP via COLIBRI.

# Warning
ICE/TCP is not the recommended way to deal with clients connecting
from networks where UDP traffic is restricted. The recommended way
is to use jitsi-videobridge in conjunction with a TURN server. The 
main reason is that using TURN/TLS uses a real TLS handshake, while 
ICE/TCP uses a hard-coded handshake which is known to be recognized
by some firewalls.

# Configuration
By default TCP support is enabled on port 443 with fallback 
to port 4443. A fallback would occur in case something else, 
like a web server is already listening on port 443. Note,
however, that the very point of using TCP is to simulate HTTP
traffic in a number of environments where it is the only allowed 
form of communication, so you may want to make sure that 
port 443 will be free on the machine where you are running the 
bridge. 

In order to avoid binding to port 443 directly:
* Redirect 443 to 4443 by external means
* Use TCP_HARVESTER_PORT=4443
* Use TCP_HARVESTER_MAPPED_PORT=443
See below for details.



The following properties can be set in the jitsi-videobridge 
properties file to control the TCP-related functionality.
The file is usually located at
```/etc/jitsi/videobridge/sip-communicator.properties``` or
```$HOME/.sip-communicator/sip-communicator.properties```


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

### *org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT*
Default: none
Type: integer between 1 and 65535

If this property is set, Jitsi Videobridge will use the given port
in the candidates that it advertises, but the actual port it listens on
will not change.


### *org.ice4j.ice.harvest.NAT_HARVESTER_LOCAL_ADDRESS*
### *org.ice4j.ice.harvest.NAT_HARVESTER_PUBLIC_ADDRESS*
Default: none

If these to properties are configured, Jitsi Videobridge will
generate additional srflx candidates for each candidate with
the local address configured. See the full documentation in
[ice4j](https://github.com/jitsi/ice4j/blob/master/doc/configuration.md#mapping-harvesters).

## Configuration of ice4j

Some of the networking-related behavior of jitsi-videobridge can be configured 
through properties for the ICE library -- [ice4j](https://github.com/jitsi/ice4j).
These properties can also be set in the jitsi-videobridge properties file. See 
[the documentation of ice4j](https://github.com/jitsi/ice4j/blob/master/doc/configuration.md)
for details.



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
