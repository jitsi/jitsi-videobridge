Introduction
============
**Jitsi Videobridge implements reports for the following statistics:**

 * Number of threads started by the video bridge
 * Used memory
 * Total memory
 * Cpu usage
 * Bit rate
 * RTP loss
 * Number of audio channels
 * Number of video channels
 * Number of conferences
 * Number of participants

Implementation
==============
**Jitsi Videobridge uses the following statistics names in the reports:**

 * **current_timestamp** - The value is the date and time when the statistics are 
generated.
 * **threads** - The value is integer with the number of threads that the video bridge 
is using. 
 * **used_memory** - the number of MB that are used on the machine that runs the video 
bridge. 
 * **total_memory** - The total memory of the machine.
cpu_usage - The value represents the CPU usage for the machine. The value is 
between 0 and 1.
 * **bit_rate_download / bit_rate_upload** -  bit rate for the video bridge in Kb/s
 * **rtp_loss** - The value is between 0 and 1 and represents the RTP packet loss for 
the video bridge.
 * **audiochannels** - Number of audio channels
 * **videochannels** - Number of video channels
 * **conferences** - Number of conferences
 * **participants** - Number of participants

If Jitsi Videobridge is using XMPP it sends the statistics reports by COLIBRI 
protocol or by PubSub (XEP-0060).

This is an example COLIBRI packet of statistics report:
```xml
<iq type='result' to='38d17cb9-0d3a-498e-b3ea-05b377845c07@ƒ/4533b58e-409f-4f6b-9268-f335b4430ba6' from='jitsi-videobridge.jitsi.net' id='u4Fc8-16' xmlns='jabber:client'>
	<stats xmlns=' http://jitsi.org/protocol/colibri'>
		<stat value='2014-07-30 10:13:11.595' name='current_timestamp'/>
		<stat value='229' name='threads'/>
		<stat value='702' name='used_memory'/>
		<stat value='0.1506' name='cpu_usage'/>
		<stat value='689.0096' name='bit_rate_download'/>
		<stat value='0.00299' name='rtp_loss'/>
		<stat value='4' name='audiochannels'/>
		<stat value='1042' name='total_memory'/>
		<stat value='700.9024' name='bit_rate_upload'/>
		<stat value='2' name='conferences'/>
		<stat value='4' name='videochannels'/>
		<stat value='4' name='participants'/>
	</stats>
</iq>
```

The reports will be received by all active focuses for the video bridge.

The same report will be sent to already created Pubsub node with the following 
packet:
```xml
<iq type="set" id="0z5p5-90" from="jitsi-videobridge.jitsi.net" to="pubsub.jitsi.net">
	<pubsub xmlns=" http://jabber.org/protocol/pubsub">
		<publish node="videobridge_stats">
			<item>
				<stats xmlns=" http://jitsi.org/protocol/colibri">
					<stat value='2014-07-30 10:13:11.595' name='current_timestamp'/>
					<stat value='229' name='threads'/>
					<stat value='702' name='used_memory'/>
					<stat value='0.1506' name='cpu_usage'/>
					<stat value='689.0096' name='bit_rate_download'/>
					<stat value='0.00299' name='rtp_loss'/>
					<stat value='4' name='audiochannels'/>
					<stat value='1042' name='total_memory'/>
					<stat value='700.9024' name='bit_rate_upload'/>
					<stat value='2' name='conferences'/>
					<stat value='4' name='videochannels'/>
					<stat value='4' name='participants'/>
				</stats>
			</item>
		</publish>
	</pubsub>
</iq>
```

When the Pubsub node receives the report it will resend it to all subscribers of
the Pubsub node with the following packet:
```xml
<message from='pubsub.jitsi.net' to='subscriber@É' id='foo'>
	<event xmlns=' http://jabber.org/protocol/pubsub#event'>
		<items node='videobridge_stats'>
			<item id='ae890ac52d0df67ed7cfdf51b644e901'>
				<stats xmlns=" http://jitsi.org/protocol/colibri">
					<stat value='2014-07-30 10:13:11.595' name='current_timestamp'/>
					<stat value='229' name='threads'/>
					<stat value='702' name='used_memory'/>
					<stat value='0.1506' name='cpu_usage'/>
					<stat value='689.0096' name='bit_rate_download'/>
					<stat value='0.00299' name='rtp_loss'/>
					<stat value='4' name='audiochannels'/>
					<stat value='1042' name='total_memory'/>
					<stat value='700.9024' name='bit_rate_upload'/>
					<stat value='2' name='conferences'/>
					<stat value='4' name='videochannels'/>
					<stat value='4' name='participants'/>
				</stats>
			</item>
		</items>
	</event>
</message>
```

If Jitsi Videobridge is using REST it will sent the statistics report 
in response to a HTTP GET request for http://[hostname]:8080/colibri/stats 
with the following JSON object: 
```javascript
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8
Content-Length: 251
Server: Jetty(9.1.5.v20140505)
{
"cpu_usage":"0.03015",
"used_memory":3732,
"rtp_loss":"0",
"bit_rate_download":"0",
"audiochannels":0,
"bit_rate_upload":"0",
"conferences":0,
"participants":0,
"current_timestamp":"2014-08-14 23:26:14.782",
"threads":17,
"total_memory":4051,
"videochannels":0
}
```

Configuration
==============
**The following configuration properties can be added in the Jitsi Videobridge configuration file(HOME/.sip-communicator/sip-communicator.properties):**

 * **org.jitsi.videobridge.ENABLE_STATISTICS** - boolean property. 
If you set this property to true the statistics will be generated and sent. By 
default(if you haven't set this property) they are disabled.

 * **org.jitsi.videobridge.STATISTICS_TRANSPORT** - string property. 
The possible values for this property are "colibri" and "pubsub".
 * **org.jitsi.videobridge.STATISTICS_INTERVAL** - integer property. 
This property specifies the time in milliseconds between generation of the 
statistics. By default the interval is 1000 milliseconds.
 * **org.jitsi.videobridge.PUBSUB_SERVICE** - string property. 
This property is required if the statistics will be sent trough PubSub service. 
It specifies the name of the PubSub service.
 * **org.jitsi.videobridge.PUBSUB_NODE** - string property. 
This property is required if the statistics will be sent trough PubSub service. 
It specifies the name of the PubSub node.
