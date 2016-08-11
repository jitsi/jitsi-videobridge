Introduction
============
**Jitsi Videobridge implements reports for the following statistics:**

 * Number of threads used by the JVM.
 * Total and memory
 * CPU usage
 * Bitrate and packet rate
 * Loss rate
 * Number of audio and video channels
 * Number of conferences and participants
 * Estimated number of video streams
 * The size of the largest conference
 * The distribution of conference sizes
 * Aggregates of RTT and jitter across all users

Implementation
==============
**Jitsi Videobridge uses the following statistics names in the reports:**

 * **current_timestamp** - The value is the date and time when the statistics are
generated (in UTC).
 * **threads** - The number of Java threads that the video bridge is using.
 * **used_memory** - Total used memory on the machine (i.e. what 'free' would return) in megabytes (10^6 B).
 * **total_memory** - The total memory of the machine in megabytes.
 * **cpu_usage** - CPU usage for the machine. The value is between 0 and 1 and is the fraction of the last interval that the CPU spent in either user, nice, system or iowait state (what would appear in the 'cpu' line in 'top')
 * **bit_rate_download / bit_rate_upload** - the total incoming and outgoing (respectively) bitrate for the video bridge in kilobits per second.
 * **packet_rate_download / packet_rate_upload** - the total incoming and outgoing (respectively) packet rate for the video bridge in packets per second.
 * **loss_rate_download** - The fraction of lost incoming RTP packets. This is based on RTP sequence numbers and is relatively accurate.
 * **loss_rate_upload** - The fraction of lost outgoing RTP packets. This is based on incoming RTCP Receiver Reports, and an attempt to subtract the fraction of packets that were not sent (i.e. were lost before they reached the bridge). Further, this is averaged over all streams of all users as opposed to all packets, so it is not correctly weighted. This is not accurate, but may be a useful metric nonetheless.
 * **rtp_loss** - Deprecated. The sum of **loss_rate_download** and **loss_rate_upload**.
 * **jitter_aggregate** - Experimental. An average value (in milliseconds) of the jitter calculated for incoming and outgoing streams. This hasn't been tested and it is currently not known whether the values are correct or not.
 * **rtt_aggregate** - An average value (in milliseconds) of the RTT across all streams.
 * **largest_conference** - The number of participants in the largest conference hosted on the bridge.
 * **conference_sizes** - The distribution of conference sizes hosted on the bridge. It is an array of integers of size 15, and the value at (zero-based) index *i* is the number of conferences with *i* participants. The last element (index 14) also includes conferences with more than 14 participants.
 * **audiochannels** - Number of audio channels
 * **videochannels** - Number of video channels
 * **conferences** - Number of conferences
 * **participants** - Number of participants
 * **videostreams** - Number of video streams, there can be more than one per channel

If Jitsi Videobridge is using XMPP it sends the statistics reports by COLIBRI
protocol or by PubSub (XEP-0060).

This is an example COLIBRI packet of a statistics report:
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

If Jitsi Videobridge is using REST it will send the statistics report
in response to a HTTP GET request for http://[hostname]:8080/colibri/stats
with the following JSON object:
```javascript
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8
Content-Length: 251
Server: Jetty(9.1.5.v20140505)
{
"cpu_usage":0.03015,
"used_memory":3732,
"rtp_loss":0.025,
"bit_rate_download":25000,
"audiochannels":12,
"bit_rate_upload":120000,
"conferences":3,
"participants":12,
"current_timestamp":"2014-08-14 23:26:14.782",
"threads":117,
"total_memory":4051,
"videochannels":12,
"packet_rate_download": 500,
"packet_rate_upload": 1500,
"loss_rate_download": 0.005,
"loss_rate_upload": 0.02,
"jitter_aggregate": 9,
"rtt_aggregate": 50,
"videostreams": 80,
"largest_conference": 7,
"conference_sizes": [0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0]
}
```

**Connecting An XMPP Component To The Publisher For PubSub Method**

Upon start of the Jitsi Videobridge, it will send the following stanza to the component
```xml
<iq id="FBPU2-0" to="pubsub.jitsi-videobridge.myhost.com" from="jitsi-videobridge.myhost.com" type="set">
   <pubsub xmlns="http://jabber.org/protocol/pubsub">
      <create node="videobridge/stats" />
   </pubsub>
</iq>
```
The component should respond with
```xml
<iq id="FBPU2-0" to="jitsi-videobridge.myhost.com" from="pubsub.jitsi-videobridge.myhost.com" type="result"/>
```

The Jitsi Videobridge will then send a configure stanza to the component
```xml
<iq id="FBPU2-1" to="pubsub.jitsi-videobridge.myhost.com" from="jitsi-videobridge.myhost.com" type="set">
   <pubsub xmlns="http://jabber.org/protocol/pubsub">
      <configure node="videobridge/stats">
         <x xmlns="jabber:x:data" type="submit">
            <field var="pubsub#access_model" type="list-single">
               <value>open</value>
            </field>
            <field var="pubsub#persist_items" type="boolean">
               <value>0</value>
            </field>
            <field var="pubsub#publish_model" type="list-single">
               <value>open</value>
            </field>
         </x>
      </configure>
   </pubsub>
</iq>
```
The component should respond with
```xml
<iq id="FBPU2-1" to="jitsi-videobridge.myhost.com" from="pubsub.jitsi-videobridge.myhost.com" type="result"/>
```
Stats will now be published to the component on the interval set below
```xml
<iq id="FBPU2-3" to="pubsub.jitsi-videobridge.myhost.com" from="jitsi-videobridge.myhost.com" type="set">
   <pubsub xmlns="http://jabber.org/protocol/pubsub">
      <publish node="videobridge/stats">
         <item id="jitsi-videobridge.inin.com">
            <stats xmlns="http://jitsi.org/protocol/colibri">
               <stat name="cpu_usage" value="0" />
               <stat name="used_memory" value="0" />
               <stat name="audiochannels" value="0" />
               <stat name="bit_rate_download" value="0" />
               <stat name="rtp_loss" value="0" />
               <stat name="bit_rate_upload" value="0" />
               <stat name="conferences" value="0" />
               <stat name="participants" value="0" />
               <stat name="current_timestamp" value="2015-12-04 15:34:33.387" />
               <stat name="threads" value="0" />
               <stat name="total_memory" value="0" />
               <stat name="videochannels" value="0" />
               <stat name="videostreams" value="0" />
            </stats>
         </item>
      </publish>
   </pubsub>
</iq>
```

The component should respond to each stats stanza with
```xml
<iq id="FBPU2-3" to="jitsi-videobridge.myhost.com" from="pubsub.jitsi-videobridge.myhost.com" type="result"/>
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
