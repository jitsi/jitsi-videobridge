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

## Expiration of relays and relayed endpoints

Jicofo expires relays and relayed endpoints with colibri2. The bridge does not expire them on its own, with two
exceptions. These exceptions protect the bridge when jicofo loses its state (a crash, a hang, or a restart). Without
them, a conference that has only relayed endpoints lives until the bridge restarts. Each such conference keeps its
relays, their ICE agents, their sockets and their threads.

1. The bridge expires a conference that has no local endpoints. It waits for the entity expiration timeout
   (`videobridge.entity-expiration.timeout`, 1 minute by default) after the last local endpoint left. The
   expiration of the conference expires its relays and its relayed endpoints. A relayed endpoint has no activity of
   its own, so the bridge does not expire it directly.
2. The bridge expires a relay with no incoming activity. ICE consent checks from the peer bridge count as activity,
   so this applies only when the peer bridge is gone. The bridge also expires a relay whose ICE did not connect
   in 2 minutes.

**Known gap.** In some topologies a bridge sits between two meshes and has no local endpoints of its own. The bridge
recognizes this case when its conference has relays in more than one mesh. It does not apply exception 1 to such a
conference, because a wrong expiration silently breaks the conference. If jicofo loses track of such a conference,
the conference leaks until the bridge restarts. This is the preferred failure mode for now.

A better fix needs a reliable signal that a relay still serves participants, for example from jicofo. With that
signal, the bridge can apply the same backstop to a bridge between meshes.

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
