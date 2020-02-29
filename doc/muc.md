# Intro
Using an XMPP client connection and advertising presence in a Multi User Chat
(MUC) is the now preferred alternative to an XMPP component connection.

With this mode a jitsi-videobrige instance can connect to a set of XMPP servers, and new servers 
can be added [at runtime](https://github.com/jitsi/jitsi-videobridge/blob/master/doc/rest-muc-client.md).

The default installation on debian now uses an XMPP client
connection. For a real-world example see the jitsi-videobridge [postinst
script](https://github.com/jitsi/jitsi-videobridge/blob/master/debian/postinst#L104).

# XMPP server configuration
The XMPP server needs to be provisioned with a single user account to be shared between all 
jitsi-videobridge instances that connect to it. For prosody this can be done using:

```
prosodyctl register jvb $DOMAIN $PASSWORD
```

We recommend using a separate XMPP domain, not accessible by anonymous users.
See the [jitsi-meet-prosody postinst
script](https://github.com/jitsi/jitsi-meet/blob/master/debian/jitsi-meet-prosody.postinst#L130)
for a full example.

# Jicofo configuration
To make use of the MUC mode in the bridge, jicofo needs to be configured to
join the same MUC. To do this add the following to jicofo's `sip-communicator.properties` file:
```
org.jitsi.jicofo.BRIDGE_MUC=JvbBrewery@internal.auth.example.net
```

# Videobridge configuration
Using the new videobrige configuration, MUC mode can be enabled by adding the following to the `videobridge` section of the config file:
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

# Legacy videobridge configuration
To enable the MUC mode using the legacy `sip-communicator.properties` file, add the following:
```
# Enable broadcasting stats/presence in a MUC
org.jitsi.videobridge.ENABLE_STATISTICS=true
org.jitsi.videobridge.STATISTICS_TRANSPORT=muc

# Connect to the first XMPP server
org.jitsi.videobridge.xmpp.user.xmppserver1.HOSTNAME=example.net
org.jitsi.videobridge.xmpp.user.xmppserver1.DOMAIN=auth.example.net
org.jitsi.videobridge.xmpp.user.xmppserver1.USERNAME=jvb
org.jitsi.videobridge.xmpp.user.xmppserver1.PASSWORD=$PASSWORD
org.jitsi.videobridge.xmpp.user.xmppserver1.MUC_JIDS=JvbBrewery@internal.auth.example.net
org.jitsi.videobridge.xmpp.user.xmppserver1.MUC=JvbBrewery@internal.auth.boris2.jitsi.net
org.jitsi.videobridge.xmpp.user.xmppserver1.MUC_NICKNAME=unique-instance-id
#org.jitsi.videobridge.xmpp.user.xmppserver1.DISABLE_CERTIFICATE_VERIFICATION=true

# Connect to a second XMPP server
org.jitsi.videobridge.xmpp.user.xmppserver2.HOSTNAME=another.example.net
org.jitsi.videobridge.xmpp.user.xmppserver2.DOMAIN=auth.example.net
org.jitsi.videobridge.xmpp.user.xmppserver2.USERNAME=jvb
org.jitsi.videobridge.xmpp.user.xmppserver2.PASSWORD=$PASSWORD
org.jitsi.videobridge.xmpp.user.xmppserver2.MUC_JIDS=JvbBrewery@internal.auth.example.net
org.jitsi.videobridge.xmpp.user.xmppserver2.MUC=JvbBrewery@internal.auth.boris2.jitsi.net
org.jitsi.videobridge.xmpp.user.xmppserver2.MUC_NICKNAME=unique-instance-id2
#org.jitsi.videobridge.xmpp.user.xmppserver2.DISABLE_CERTIFICATE_VERIFICATION=true
```
