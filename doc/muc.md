# Intro
When the XMPP API is enabled jitsi-videobridge uses an XMPP client connection and advertises presence in a Multi-User
Chat (MUC) room.

With this mode a jitsi-videobridge instance can connect to a set of XMPP servers, and new servers 
can be added [at runtime](https://github.com/jitsi/jitsi-videobridge/blob/master/doc/rest-muc-client.md).

# XMPP server configuration
The XMPP server can be provisioned with a single user account to be shared between all jitsi-videobridge instances that
connect to it. For prosody this can be done using:

```
prosodyctl register jvb $DOMAIN $PASSWORD
```

We recommend using a separate XMPP domain, not accessible by anonymous users.
See the [jitsi-meet-prosody postinst
script](https://github.com/jitsi/jitsi-meet/blob/master/debian/jitsi-meet-prosody.postinst)
for a full example. It is also possible to use a completely separate XMPP server accessible only to jitsi-videobridge
and jicofo (not publicly accessible at all).

# Jicofo configuration
To make use of the MUC mode in the bridge, jicofo needs to be configured to
join the same MUC. To do this set `jicofo.bridge.brewery-jid` to the MUC address in `jicofo.conf`.

# Videobridge configuration
To enable the MUC mode add the following to the `videobridge` section of the config file:
```
stats {
  # Enable broadcasting stats/presence in a MUC
  enabled = true
  transports = [
    { type = "muc" }
  ]
}

apis {
  xmpp-client {
    configs {
      # Connect to the first XMPP server
      xmpp-server-1 {
        hostname="example.net"
        domain = "auth.example.net"
        username = "jvb"
        password = "$PASSWORD"
        muc_jids = "JvbBrewery@internal.auth.example.net"
        # The muc_nickname must be unique across all jitsi-videobridge instances
        muc_nickname = "unique-instance-id"
        # disable_certificate_verification = true
      }
      # Connect to a second XMPP server
      xmpp-server-2 {
        hostname="another.example.net"
        domain = "auth.example.net"
        username = "jvb"
        password = "$PASSWORD"
        muc_jids = "JvbBrewery@internal.auth.example.net"
        # The muc_nickname must be unique across all jitsi-videobridge instances
        muc_nickname = "unique-instance-id2"
        # disable_certificate_verification = true
      }
    }
  }
}

```