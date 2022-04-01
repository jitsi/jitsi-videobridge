# Setting up Octo (cascaded bridges)

## Versions
Jitsi-videobridge supports two completely different implementations of bridge-to-bridge interconnection.

### Octo
We refer to the legacy implementation as simply "octo". It uses raw UDP on pre-configured ports, and does not protect
the media traffic (it assumes a secure (virtual) network between the bridges).

This implementation is only usable with version 1 of the colibri protocol over XMPP. It is deprecated and will be
removed in future releases.

### Secure-octo
We refer to the new implementation as "secure-octo". It uses ICE and DTLS/SRTP between each pair of bridges, so a secure
network is not required. It uses and requires colibri websockets for the bridge-bridge connections (endpoints can still
use SCTP).

This implementation is only usable with version 2 of the colibri protocol, which is currently only available over XMPP.

## Jitsi Videobridge configuration

### Octo (legacy)
Octo can be configured with the following properties in `/etc/jitsi/videobridge/jvb.conf` (also see 
[reference.conf](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L132)).
```
videobridge {
  octo {
    # the address to bind to locally
    bind-address=10.0.0.1
    # the address to advertise (in case BIND_ADDRESS is not accessible)
    public-address=1.2.3.4
    # the port to bind to
    bind-port=4096
    # the region that the jitsi-videobridge instance is in
    region="region1"
  }
}
```

You need to make sure that all bridges can communicate via the socket
addresses described in the properties above, and that the network is secure.

### Secure-octo
Secure-octo can be configured with the following properties in `/etc/jitsi/videobridge/jvb.conf` (also see
[reference.conf](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L132)).
```
  videobridge {
    octo {
      enabled=true
      region="region1"
      relay-id="unique-id-of-the-jitsi-videobridge-instance"
    }
  }
```

Legacy configuration is detected and automatically adapted, so no configuration changes are necessary when upgrading.

Secure-octo requires colibri websockets for the bridge-to-bridge connections, which can be enabled as described in
[this document](https://github.com/jitsi/jitsi-videobridge/blob/master/doc/web-sockets.md).

## Jicofo configuration
The latest versions of jicofo support only colibri v2/secure-octo.

The only configuration parameter that needs to be changed is the bridge selection strategy (in 
`/etc/jitsi/jicofo/jicofo.conf`):

```
jicofo {
  bridge {
    selection-strategy = RegionBasedBridgeSelectionStrategy
  }
}
```

The `RegionBasedBridgeSelectionStrategy` matches the region of the clients to
the region of the Jitsi Videobridge instances. That is, it always attempts to select a bridge
in the region of the client.

The `SplitBridgeSelectionStrategy` can be used for testing. It tries to select a new bridge 
for each client, regardless of the regions. This is useful while testing, because you can 
verify that Octo works before setting up the region configuration for the clients.


## Configuring client regions
Clients learn about the region that they are in via the value of
`config.deploymentInfo.userRegion`. Thus, in order for Octo to actually work
for geolocation, `config.js` must be served with the correct values. The best
way to implement this is outside the scope of this document, and it depends on
the environment in which Jitsi Meet is installed.

One easy solution can be to set the variable based on the domain name, and then
access the conference through the desired domain name, e.g. use
`us-east.jitsi.example.com`, `us-west.jitsi.example.com`,
`eu.jitsi.example.com` and have them serve custom versions of `config.js`.
