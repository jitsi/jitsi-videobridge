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

Configuration
==============

The following configuration properties can be added to the jitsi-videobridge configuration file (whichever file is 
passed to `-Dconfig.file` when running the bridge) to control the behavior of the HTTP interfaces

For the _private_ interface, use the following block and values:

```hocon
videobridge {
    http-servers {
        private {
            // The port for the private HTTP interface (or -1 to disable it).  Defaults to 8080.
            port = <Number>
            // The port for the private HTTP interface if TLS is to be used (or -1 to disable). Defaults to 8443.
            tls-port = <Number>
            // The address on which the server will listen
            host = <String>
            // The file path to the keystore to be used with HTTPS for the private interface.  If this is not specified,
            // HTTPS is disabled for the private interface
            key-store-path = <String (path to key store)>
            // The password to be used by the SslContextFactory when HTTPS is used
            key-store-password = <String>
            // Whether or not client certificate authentication is to be required when
            // HTTPS is used
            need-client-auth: <Boolean>
        }
    }
}
```

The _public_ interface uses the same values, but under a different scope:

```hocon
videobridge {
    http-servers {
        public {
            // Same values as above
        }
    }
}
```
