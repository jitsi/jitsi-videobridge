# Introduction
WebSockets can be used instead of WebRTC Data Channels for transport of Colibri
client-to-bridge messages. This needs support from the bridge as well as the
client.

When this is enabled, a bridge will advertise a Colibri WebSocket URL together with
its ICE candidates. The URL will be specific to an endpoint (in fact the ICE username
fragment is reused, encoded as a URL parameter, for authentication), and connections
to it will be routed to the Endpoint representation in the bridge.

The URL has the following format:
```
wss://example.com/colibri-ws/server-id/conf-id/endpoint-id?pwd=123
```

# Bridge configuration
The following properties configure the bridge.

To enable the HTTP interface without TLS:
```
org.jitsi.videobridge.rest.jetty.port=9090
```

To enable the HTTP interface with TLS:
```
org.jitsi.videobridge.rest.jetty.tls.port=443
org.jitsi.videobridge.rest.jetty.sslContextFactory.keyStorePath=/etc/jitsi/videobridge/ssl.store
org.jitsi.videobridge.rest.jetty.sslContextFactory.keyStorePassword=KEY_STORE_PASSWORD
```

To specify that the bridge should advertise the web socket protocol as "wss"
even if it is locally using plain HTTP. This is useful if TLS is terminated
elsewhere:
```
org.jitsi.videobridge.rest.COLIBRI_WS_TLS=true
```

To specify the domain and port number to advertise:
```
org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN=example.com:443
```

To specify a specific server ID to be advertised as path of the HTTP request path. This is useful
when a set of jitsi-videobridge instances are fronted by an HTTP proxy, and they advertise the same domain:
```
org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID=jvb2
```

# Proxy configuration
If you are using an HTTP proxy, it needs to support WebSocket. The following is
an example `nginx` configuration which fronts two bridges. The two bridges use
ports `9090` and `9091` without TLS, and are configured with
`COLIBRI_WS_SERVER_ID=jvb1` and `jvb2` (see above) respectively.

```
   # colibri (JVB) websockets for jvb1
   location ~ ^/colibri-ws/jvb1/(.*) {
       proxy_pass http://127.0.0.1:9090/colibri-ws/jvb1/$1$is_args$args;
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection "upgrade";
       tcp_nodelay on;
   }
   location ~ ^/colibri-ws/jvb2/(.*) {
       proxy_pass http://127.0.0.1:9091/colibri-ws/jvb2/$1$is_args$args;
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection "upgrade";
       proxy_set_header Host alpha.jitsi.net;
       tcp_nodelay on;
   }
```

This configuration allows two jitsi-videobridge instances to run on the same
machine, which is useful while testing Octo.

# Client confiruation
With the above configuration the bridge will advertise a Colibri WebSocket URL,
and will be ready to accept connections on it, but whether this is used instead of
WebRTC DataChannels depends on the client.

To enable Colibri WebSocket in the jitsi-meet client, set the following in `config.js`:
```
openBridgeChannel: 'websocket'
```

# Troubleshooting
To verify that WebSockets are configured and used, first check that the Colibri
WebSocket URL is advertised to the clients. Open a conference and look for
"session-initiate" in the javascript console logs. Extend the XML and look for
`description -> content -> transport`. You should see a `web-socket` element
like this (you can verify this on meet.jit.si):

```xml
<web-socket xmlns="http://jitsi.org/protocol/colibri" url="wss://meet-jit-si-eu-west-2b-s5-jvb-51.jitsi.net:443/colibri-ws/default-id/4f9cb343985d4779/c814b6a6?pwd=23btmrjol5i83thk1t9s78bnkk"/>
```

Make sure that the URL is correct, and that your infrastructure routes it to
the correct jitsi-videobridge instance. Finally, check the `Network` tab in the
Chrome dev console and look for requests to this URL. You should see some requests
every few seconds, and they should be successful.
