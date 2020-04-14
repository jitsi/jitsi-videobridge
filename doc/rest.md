Introduction
==============
Jitsi-videobridge supports two HTTP(S) interfaces, a _public_ and a _private_ one. 
The two interfaces use different ports. The _private_ interface exposes
HTTP endpoints which are not meant to be publicly accessible (but might be used
by other components of the infrastructure, e.g. a signaling server), such as:

* The [COLIBRI control interface](rest-colibri.md) (```/colibri/```)
* The health-check interface (```/about/health```)
* The version interface (```/about/version```)

The _public_ interface is used for Colibri Web Sockets that clients connect to.

**For any of the HTTP interfaces to be enabled, jitsi-videobridge needs to be started with the ```--apis=rest```
parameter (or ```--apis=rest,xmpp``` to also enable the XMPP interface to COLIBRI).** This is enough to enable the
private interface, but for the public interface additional properties are required (see below).

Configuration
==============

The following configuration properties can be added to the jitsi-videobridge configuration file to control the behaviour of the HTTP interfaces

For the _private_ interface:

 * ```org.jitsi.videobridge.rest.private.jetty.port``` - 
 Specifies the port for the private HTTP interface (or -1 to disable it). The default value is ```8080```.
 * ```org.jitsi.videobridge.rest.private.jetty.tls.port``` - 
 Specifies the port for the private HTTP interface if TLS is to be used (or -1 to disable). The default value is ```8443```.
 * ```org.jitsi.videobridge.rest.private.jetty.sslContextFactory.keyStorePath``` - 
 Specifies the path to the keystore to be used with HTTPS for the private interface. If this is not specified,
 HTTPS is disabled for the private interface.
 * ```org.jitsi.videobridge.rest.private.jetty.sslContextFactory.keyStorePassword``` - 
 Specifies the password for the keystore of the private HTTP interface.
 * ```org.jitsi.videobridge.rest.private.jetty.sslContextFactory.needClientAuth``` - 
 Specifies whether client certificate authentication is to be required when HTTPS is enabled. The default value is ```false```.
 * ```org.jitsi.videobridge.rest.private.jetty.host``` - 
 Specifies the server host.

For the _public_ interface:
 * ```org.jitsi.videobridge.rest.jetty.port``` - 
 Specifies the port for the public HTTP interface (or -1 to disable it). The default value is ```-1```.
 * ```org.jitsi.videobridge.rest.jetty.tls.port``` - 
 Specifies the port for the public HTTP interface if TLS is to be used (or -1 to disable). The default value is ```-1```.
 * ```org.jitsi.videobridge.rest.jetty.sslContextFactory.keyStorePath``` - 
 Specifies the path to the keystore to be used with HTTPS for the public interface. If this is not specified, 
 HTTPS is disabled for the public interface..
 * ```org.jitsi.videobridge.rest.jetty.sslContextFactory.keyStorePassword``` - 
 Specifies the password for the keystore of the public HTTP interface.
 * ```org.jitsi.videobridge.rest.jetty.sslContextFactory.needClientAuth``` - 
 Specifies whether client certificate authentication is to be required when HTTPS is enabled. The default value is ```false```.
 * ```org.jitsi.videobridge.rest.jetty.host``` - 
 Specifies the server host.

Examples
==============

### Example 1
Enable only the private interface with HTTP on the default port (8080): no configuration properties are required.

### Example 2
Enable only the private interface with HTTPS on port 4443:
```
org.jitsi.videobridge.rest.private.jetty.sslContextFactory.keyStorePath=/path/to/keystore
org.jitsi.videobridge.rest.private.jetty.sslContextFactory.keyStorePassword=changeme
org.jitsi.videobridge.rest.private.jetty.tls.port=4443
```

### Example 3
Enable only the public interface with HTTP on port 80:
```
org.jitsi.videobridge.rest.private.jetty.port=-1 #disable the private interface
org.jitsi.videobridge.rest.jetty.port=80
```

### Example 4
Enable both the public and private interfaces with HTTPS (with the same certificate) 
on ports 443 (public) and 8443 (private):
```
org.jitsi.videobridge.rest.private.jetty.sslContextFactory.keyStorePath=/path/to/keystore
org.jitsi.videobridge.rest.private.jetty.sslContextFactory.keyStorePassword=changeme

org.jitsi.videobridge.rest.jetty.tls.port=443
org.jitsi.videobridge.rest.jetty.sslContextFactory.keyStorePath=/path/to/keystore
org.jitsi.videobridge.rest.jetty.sslContextFactory.keyStorePassword=changeme
```
