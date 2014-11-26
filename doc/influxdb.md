Logging events to an InfluxDB can be controlled with the following properties,
all of which are required:

###org.jitsi.videobridge.log.INFLUX\_DB\_ENABLED=true
Default: false

###org.jitsi.videobridge.log.INFLUX\_URL\_BASE=http://influxdb.example.com:8086
Default: none

Set the protocol (http or https), host and port to use.

###org.jitsi.videobridge.log.INFLUX\_DATABASE=jitsi_database
Default: none

Set the name of the InfluxDB database.

###org.jitsi.videobridge.log.INFLUX\_USER=user
Default: none

###org.jitsi.videobridge.log.INFLUX\_PASS=pass
Default: none

