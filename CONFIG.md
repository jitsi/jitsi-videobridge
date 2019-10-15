# Configuration

##### 10/14/19
We've begun the migration to a new configuration framework.  The 'old' method (the `sip-communicator.properties` file and program arguments) will be supported throughout the transition and the bridge will be fully backwards-compatible with the old config.  No changes are necessary until we pull the plug on the old config completely, and we'll give ample warning before that time.

Moving forward, we'll be using lightbend's [config library]([https://github.com/lightbend/config](https://github.com/lightbend/config)).  Their GitHub page gives lots of details, but below are the changes most relevant to configuring the JVB.

### Configuration sources
During the transition, we'll continue to read from the original locations (`sip-communicator.properties` and program arguments) for all configuration properties, falling back to the new `reference.conf` file for defaults.  The `reference.conf` file holds defaults for all configuration properties, and shouldn't be modified in a deployment.  Instead, users can put a file named `application.conf` in the classpath: values can be put there to override ones in `reference.conf`.  The lightbend docs do a good job of describing [how configuration is loaded]([https://github.com/lightbend/config#standard-behavior](https://github.com/lightbend/config#standard-behavior)).  Here's a more concrete example of overriding a configuration value for JVB:

### Overriding default values
Say we want to override some of the default values in `reference.conf`, namely: the XMPP API server values, the health check interval, and whether or not onstage video suspension is enabled.  We'll create an `application.conf` files with the values we want to change:

```
videobridge {
    enabled-apis=[
        {
            // Here we'll customize
            name="xmpp"
            host="my.xmpp-host.com"
            domain="my-xmpp-domain"
            secret="my-xmpp-secret"
            port=4242
        }
    ]
    health {
        // Override the health check interval
        interval=60 seconds
    }
    cc {
        // Override the onstage video suspension setting
        onstage-video-suspension-enabled=true
    }
}
```
You can override just the values you want to change, all others will fallback to the defaults set in `reference.conf`

### Migrating from old config
Below are the mappings from old property values to new ones.  Note: for new property names, the 'flat paths' are given, i.e. a value of `videobridge.health.interval` corresponds to the setting:
```
videobridge {
  health {
    interval
  }
}
```
This list will be updated as properties are migrated:

| Old property name | New property name |
| -------- | ------- |
| org.jitsi.videobridge.health.INTERVAL | videobridge.health.interval |
