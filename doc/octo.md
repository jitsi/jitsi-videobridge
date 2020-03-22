# Setting up Octo (cascaded bridges)

## Jitsi Videobridge
Add these properties to `/etc/jitsi/videobridge/sip-communicator.properties` to
enable support for Octo:
```
org.jitsi.videobridge.octo.BIND_ADDRESS=10.0.0.1 # the address to bind to locally
org.jitsi.videobridge.octo.PUBLIC_ADDRESS=1.2.3.4 # the address to advertise (in case BIND_ADDRESS is not accessible)
org.jitsi.videobridge.octo.BIND_PORT=4096 # the port to bind to
org.jitsi.videobridge.REGION=region1 # the region that the jitsi-videobridge instance is in
```

You need to make sure that all of the bridges can communicate via the socket
addresses described in the properties above, and that the network is secure.

## Jicofo configuration
To enable the use of Octo in jicofo, you need to set the "selection strategy" by
setting this property in `/etc/jitsi/jicofo/sip-communicator.properties`:
```
org.jitsi.jicofo.BridgeSelector.BRIDGE_SELECTION_STRATEGY=RegionBasedBridgeSelectionStrategy
```

The `RegionBasedBridgeSelectionStrategy` matches the region of the clients to
the region of the Jitsi Videobridge instances. That is, it always attempts to select a bridge
in the region of the client.

The `SplitBridgeSelectionStrategy` can be used for testing. It tries to select a new bridge 
for each client, regardless of the regions. This is useful while testing, because you can 
verify that Octo works before setting up the region configuration for the clients.


## Jitsi Meet configuration
The last step in enabling bridge cascading is enabling the feature in the
clients (in `config.js` (/etc/jitsi/meet/)):
```$xslt
testing: {
    octo: {
        probability: 1
    }
}
```

Values other than 1 can be used for an A/B test (e.g. use 0.5 for a 50% probability).


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
