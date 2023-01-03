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
ICE/TCP is configured in the `videobridge.ice.tcp` section in `jvb.conf`.

By default TCP support is disabled. When enabled, the default is to
use port 443 with fallback to port 4443. A fallback would occur in
case something else, like a web server, is already listening on
port 443. Note, however, that the very point of using TCP is to
simulate HTTP traffic in a number of environments where it is the
only allowed form of communication, so you may want to make sure that 
port 443 will be free on the machine where you are running the 
bridge. 

In order to avoid binding to port 443 directly:
* Redirect 443 to 4443 by external means
* Use `port=4443`
* Use `mapped-port=443`
See below for details.

```hocon
videobridge {
  ice {
    tcp {
      enabled = false

      // Configures the port number to be used by the TCP harvester. If this property is unset (and the TCP harvester
      // is enabled), jitsi-videobridge will first try to bind on port 443, and if this fails, it will try port 4443
      // instead. If the property is set, it will only try to bind to the specified port, with no fallback.
      port = 443

      // If this property is set, Jitsi Videobridge will use the given port in the candidates that it advertises, but
      // the actual port it listens on will not change.
      #mapped-port = 8443 // Defaults unset

      // Configures the use of "ssltcp" candidates.
      //
      // When enabled, Jitsi Videobridge will generate candidates with protocol "ssltcp", and the TCP harvester will
      // expect connecting clients to send a special pseudo-SSL ClientHello message right after they connect, before any
      // STUN messages. Chrome sends this message if a candidate in its SDP offer has the "ssltcp" protocol.
      //
      // When disabled, Jitsi Videobridge will generate candidates with protocol "tcp" candidates and will expect to
      // receive STUN messages right away.
      ssltcp = true
    }
  }
}
```

## Configuration of ice4j

Some of the networking-related behavior of jitsi-videobridge can be configured
through properties for the ICE library -- [ice4j](https://github.com/jitsi/ice4j).
These properties can also be set in the jitsi-videobridge properties file. See
[documentation](https://github.com/jitsi/ice4j/blob/master/doc/configuration.md) and
[reference.conf](https://github.com/jitsi/ice4j/blob/master/src/main/resources/reference.conf#L37) in ice4j for
details.

# Examples
## None of the TCP specific properties set, successful bind on port 443
Jitsi Videobridge will bind to port 443 and announce port 443.

## None of the TCP specific properties set, failure to bind on port 443 (lack of privileges, or web-server already bound on 443)
Jitsi Videobridge will bind to port 4443 and announce port 4443.
