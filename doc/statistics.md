Introduction
============
**Jitsi Videobridge implements reports for the following statistics (and more):**

 * Number of threads used by the JVM.
 * Current bitrate, packet rate, and packet loss rate.
 * Current number of audio and video channels, and conferences.
 * Current estimated number of video streams.
 * The size of the largest conference in progress.
 * The distribution of the sizes of the conferences currently in progress.
 * Aggregates of RTT and jitter across all users.
 * The total number of created, completed, failed and partially failed conferences.
 * The total number of messages sent and received through WebRTC data channels and COLIBRI web sockets.
 * The total duration of all completed conferences.
 * The number of ICE sessions established over UDP or TCP.

Implementation
==============
**Jitsi Videobridge uses the following statistics names in the reports:**

 * **current_timestamp** - The value is the date and time when the statistics are
generated (in UTC).
 * **threads** - The number of Java threads that the video bridge is using.
 * **bit_rate_download / bit_rate_upload** - the total incoming and outgoing (respectively) bitrate for the video bridge in kilobits per second.
 * **packet_rate_download / packet_rate_upload** - the total incoming and outgoing (respectively) packet rate for the video bridge in packets per second.
 * **loss_rate_download** - The fraction of lost incoming RTP packets. This is based on RTP sequence numbers and is relatively accurate.
 * **loss_rate_upload** - The fraction of lost outgoing RTP packets. This is based on incoming RTCP Receiver Reports, and an attempt to subtract the fraction of packets that were not sent (i.e. were lost before they reached the bridge). Further, this is averaged over all streams of all users as opposed to all packets, so it is not correctly weighted. This is not accurate, but may be a useful metric nonetheless.
 * **rtp_loss** - Deprecated. The sum of **loss_rate_download** and **loss_rate_upload**.
 * **jitter_aggregate** - Experimental. An average value (in milliseconds) of the jitter calculated for incoming and outgoing streams. This hasn't been tested and it is currently not known whether the values are correct or not.
 * **rtt_aggregate** - An average value (in milliseconds) of the RTT across all streams.
 * **largest_conference** - The number of participants in the largest conference currently hosted on the bridge.
 * **conference_sizes** - The distribution of conference sizes hosted on the bridge. It is an array of integers of size 22, and the value at (zero-based) index *i* is the number of conferences with *i* participants. The last element (index 21) also includes conferences with more than 21 participants.
 * **audiochannels** - The current number of audio channels.
 * **videochannels** - The current number of video channels.
 * **conferences** - The current number of conferences.
 * **participants** - The current number of participants.
 * **videostreams** - An estimation of the number of current video streams forwarded by the bridge.
 * **total_loss_controlled_participant_seconds** -- The total number of participant-seconds that are loss-controlled.
 * **total_loss_limited_participant_seconds** -- The total number of participant-seconds that are loss-limited.
 * **total_loss_degraded_participant_seconds** -- The total number of participant-seconds that are loss-degraded.
 * **total_conference_seconds** - The sum of the lengths of all completed conferences, in seconds.
 * **total_conferences_created** - The total number of conferences created on the bridge.
 * **total_failed_conferences** - The total number of failed conferences on the bridge. A conference is marked as failed when all of its channels have failed. A channel is marked as failed if it had no payload activity.
 * **total_partially_failed_conferences** - The total number of partially failed conferences on the bridge. A conference is marked as partially failed when some of its channels has failed. A channel is marked as failed if it had no payload activity.
 * **total_data_channel_messages_received / total_data_channel_messages_sent** - The total number messages received and sent through data channels.
 * **total_colibri_web_socket_messages_received / total_colibri_web_socket_messages_sent** - The total number messages received and sent through COLIBRI web sockets.

The statistics are available through the `/colibri/stats` endpoint on the [private REST interface](rest.md) (if it has been enabled) in JSON format:
```json
{
  "audiochannels": 0,
  "bit_rate_download": 0,
  "bit_rate_upload": 0,
  "conference_sizes": [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ],
  "conferences": 0,
  "current_timestamp": "2019-03-14 11:02:15.184",
  "graceful_shutdown": false,
  "jitter_aggregate": 0,
  "largest_conference": 0,
  "loss_rate_download": 0,
  "loss_rate_upload": 0,
  "packet_rate_download": 0,
  "packet_rate_upload": 0,
  "participants": 0,
  "region": "eu-west-1",
  "relay_id": "10.0.0.5:4096",
  "rtp_loss": 0,
  "rtt_aggregate": 0,
  "threads": 59,
  "total_bytes_received": 257628359,
  "total_bytes_received_octo": 0,
  "total_bytes_sent": 257754048,
  "total_bytes_sent_octo": 0,
  "total_colibri_web_socket_messages_received": 0,
  "total_colibri_web_socket_messages_sent": 0,
  "total_conference_seconds": 470,
  "total_conferences_completed": 1,
  "total_conferences_created": 1,
  "total_data_channel_messages_received": 602,
  "total_data_channel_messages_sent": 600,
  "total_failed_conferences": 0,
  "total_ice_failed": 0,
  "total_ice_succeeded": 2,
  "total_ice_succeeded_tcp": 0,
  "total_loss_controlled_participant_seconds": 847,
  "total_loss_degraded_participant_seconds": 1,
  "total_loss_limited_participant_seconds": 0,
  "total_packets_dropped_octo": 0,
  "total_packets_received": 266644,
  "total_packets_received_octo": 0,
  "total_packets_sent": 266556,
  "total_packets_sent_octo": 0,
  "total_partially_failed_conferences": 0,
  "total_participants": 2,
  "videochannels": 0,
  "videostreams": 0
}
```

The statistics can also be published periodically via XMPP (which allows jicofo to monitor a set of bridges and perform load balancing). In this case the statistics are represented in XML format with a `stats` element like this:
```xml
<stats xmlns=' http://jitsi.org/protocol/colibri'>
	<stat value='2014-07-30 10:13:11.595' name='current_timestamp'/>
	<stat value='229' name='threads'/>
	<stat value='689.0096' name='bit_rate_download'/>
	<stat value='0.00299' name='rtp_loss'/>
	<stat value='4' name='audiochannels'/>
	<stat value='700.9024' name='bit_rate_upload'/>
	<stat value='2' name='conferences'/>
	<stat value='4' name='videochannels'/>
	<stat value='4' name='participants'/>
	<stat value='1' name='total_failed_conferences'/>
	<stat value='1' name='total_partially_failed_conferences'/>
	<stat value='1' name='total_no_payload_channels'/>
	<stat value='2' name='total_no_transport_channels'/>
	<stat value='8' name='total_channels'/>
</stats>
```

The statistics reporting functionality can be configured with the following properties:

 * **org.jitsi.videobridge.ENABLE_STATISTICS** - boolean property.
The default value is `false`
 * **org.jitsi.videobridge.STATISTICS_TRANSPORT** - string property.
A comma-separated list of transports. The supported transports are "muc"
and "callstats.io".
 * **org.jitsi.videobridge.STATISTICS_INTERVAL** - integer property.
This property specifies the reporting time in milliseconds between generation of the
statistics. By default the interval is 1000 milliseconds.

With the `muc` transport the `stats` element is added to the Presence in the MUCs that have been configured
(TODO document how).
