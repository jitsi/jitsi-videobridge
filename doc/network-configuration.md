# Network Configuration

## IPv6 Configuration

JVB supports IPv6 in dual-stack (IPv4 + IPv6) environments.
To enable it, add the following to your `jvb.conf`:
```hocon
ice4j {
  harvest {
    use-ipv6 = true
    use-link-local-addresses = false
    mapping {
      stun {
        addresses = [ "your-stun-server:3478" ]
      }
      static-mappings = [
        {
          local-address = "YOUR_PRIVATE_IPV4"
          public-address = "YOUR_PUBLIC_IPV4"
        }
      ]
    }
  }
}
```

### Notes
- Jitsi Web and the Videobridge can have different IPv6 addresses
- When IPv6 is enabled, a UDP6 listener will open on port 10000

### Verifying ICE Candidates
To confirm correct candidates are advertised, enable verbose logging in `/etc/jitsi/videobridge/logging.properties`:
```
org.jitsi.videobridge.xmpp.XmppConnection.level=ALL
```

Then grep the logs for `SENT` to inspect the ICE candidates being sent.

### Related Issues
- [#1722](https://github.com/jitsi/jitsi-videobridge/issues/1722)

