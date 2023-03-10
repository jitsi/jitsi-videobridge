# Setting up relays (cascaded bridges)

### Relays
Relays (aka secure octo) use ICE and DTLS/SRTP between each pair of bridges, so a secure
network is not required. It uses and requires either SCTP or colibri websockets for the
bridge-bridge connections.

## Jitsi Videobridge configuration

### Relay configuration
Relays can be configured with the following properties in `/etc/jitsi/videobridge/jvb.conf` (also see
[reference.conf](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L132)).
```
  videobridge {
    relay {
      enabled=true
      region="region1"
      relay-id="unique-id-of-the-jitsi-videobridge-instance"
    }
  }
```

Legacy configuration is detected and automatically adapted, so no configuration changes are necessary when upgrading.

Relays require colibri websockets for the bridge-to-bridge connections, which can be enabled as described in
[this document](https://github.com/jitsi/jitsi-videobridge/blob/master/doc/web-sockets.md). Note that the `colibri-relay-ws`
endpoints also need to be proxied.

## Jicofo configuration
Octo needs to be enabled, and a suitable bridge selection strategy needs to be configured in `/etc/jitsi/jicofo/jicofo.conf`:

```
jicofo {
  bridge {
    selection-strategy = RegionBasedBridgeSelectionStrategy
  }
  octo {
    enabled = true
  }
}
```

The `RegionBasedBridgeSelectionStrategy` matches the region of the clients to
the region of the Jitsi Videobridge instances. That is, it always attempts to select a bridge
in the region of the client.

The `SplitBridgeSelectionStrategy` can be used for testing. It tries to select a new bridge 
for each client, regardless of the regions. This is useful while testing, because you can 
verify that Octo works before setting up the region configuration for the clients.

*Important note*: Jicofo will not mix bridges with different versions in the same conference. Even with
`SplitBridgeSelectionStrategy` if the bridges have different versions only bridges with the same version will be used.
To prevent this behavior (for testing purposes only) set `jicofo.ocfo.allow-mixed-versions=true` in `jicofo.conf`.

## Debugging
Jicofo's debug interface can be used for troubleshooting.

To list the bridges available to jicofo (with their versions, regions, etc):
```commandline
curl "http://localhost:8888/debug" | jq .bridge_selector
```

To list currently running conferences:
```commandline
curl "http://localhost:8888/debug" | jq .focus_manager
```

To list the full state of a conference (get actual conference ID with the command above, escaping the '@' sign):
```commandline
curl "http://localhost:8888/debug/conference/test\@conference.example.com" | jq .
```

Look under `colibri_session_manager` for the different bridge sessions.


## Configuring client regions
Clients learn about the region that they are in via the value of
`config.deploymentInfo.userRegion`. Thus, in order for relays to actually work
for geolocation, `config.js` must be served with the correct values. The best
way to implement this is outside the scope of this document, and it depends on
the environment in which Jitsi Meet is installed.

One easy solution can be to set the variable based on the domain name, and then
access the conference through the desired domain name, e.g. use
`us-east.jitsi.example.com`, `us-west.jitsi.example.com`,
`eu.jitsi.example.com` and have them serve custom versions of `config.js`.
