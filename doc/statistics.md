Introduction
============
Jitsi Videobridge exports statistics/metrics as key-value pairs in two ways: via a REST interface which can be
queried on demand and as periodic reports published in an XMPP MUC.

# REST
The statistics are available through the `/colibri/stats` endpoint on the [private REST interface](rest.md) 
(if it has been enabled) in JSON format:
```json
{
  "key": "value"
}
```

Note that the report itself is generated periodically and a cached version is returned. The period defaults to 5 seconds
and can be configured with the `videobridge.stats.interval` property in `jvb.conf`.

# XMPP MUC
The statistics can also be published periodically via XMPP (which allows jicofo to monitor a set of bridges and perform
load balancing, or allows an application to monitor the MUC and collect metrics from multiple bridges). In this case the
key-vlue pairrs are represented in XML format with a `stats` element like this:
```xml
<stats xmlns=' http://jitsi.org/protocol/colibri'>
	<stat value='value' name='key'/>
</stats>
```

By default, statistics are pushed every 5 seconds and this can be configured in `jvb.conf` with the
[`videobridge.apis.xmpp-client.presence-interval`](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L65) property.


# Supported metrics
Below is a non-exhaustive list of currently supported metrics. In the descriptions "current" means an instantaneous 
value (when the report was generated), and "total" means a cumulative value since the application was started.

* `bit_rate_download` - the current incoming bitrate (RTP) in kilobits per second.
* `bit_rate_upload` - the current outgoing bitrate (RTP) in kilobits per second.
* `conference_sizes` - the current distribution of conference sizes (counting all endpoints, including `octo` endpoints
which are connected to a different jitsi-videobridge instance). The value is an array of integers of size 22,
and the value at (zero-based) index `i` is the number of conferences with `i` participants. The last element (index 21)
also includes conferences with more than 21 participants.
* `conferences` - The current number of conferences.
* `conferences_by_audio_senders` - the current distribution of the number of endpoints which are sending (non-silence)
in all conferences. The semantics are similar to `conference_sizes`, e.g. a value of `v` at index `i` means that there
are exactly `v` conferences in which `i` endpoints are sending audio.
* `conferences_by_video_senders` - like `conferences_by_audio_senders`, but for video senders.
* `current_timestamp` - the UTC time at which the report was generated.
* `dtls_failed_endpoints` - the total number of endpoints which failed to establish a DTLS connection.
* `endpoints` - the current number of endpoints, including `octo` endpoints.
* `endpoints_sending_audio` - current number of endpoints sending (non-silence) audio.
* `endpoints_sending_video` - current number of endpoints sending video.
* `endpoints_with_spurious_remb` - total number of endpoints which have sent an RTCP REMB packet when REMB was not
signaled.
* `graceful_shutdown` - whether jitsi-videobridge is currently in graceful shutdown mode (hosting existing conferences,
but not accepting new ones).
* `inactive_conferences` - current number of conferences in which no endpoints are sending audio nor video. Note that
this includes conferences which are currently using a peer-to-peer transport.
* `inactive_endpoints` - current number of endpoints in inactive conferences (see `inactive_conferences`).
* `largest_conference` - the size of the current largest conference (counting all endpoints, including `octo` 
endpoints which are connected to a different jitsi-videobridge instance)
* `local_active_endpoints` - the current number of local endpoints (not `octo`) which are in an active conference. This
includes endpoints which are not sending audio or video, but are in an active conference (i.e. they are receive-only).
* `local_endpoints` - the current number of local (non-`octo`) endpoints.
* `num_eps_oversending` - current number of endpoints to which we are oversending.
* `octo_conferences` - current number of conferences in which `octo` is enabled.
* `octo_endpoints` - current number of `octo` endpoints (connected to remove jitsi-videobridge instances).
* `octo_receive_bitrate` - current incoming bitrate on the `octo` channel (combined for all conferences) in bits per 
second.
* `octo_receive_packet_rate` - current incoming packet rate on the `octo` channel (combined for all conferences) in
packets per second.
* `octo_send_bitrate` - current outgoing bitrate on the `octo` channel (combined for all conferences) in bits per
second.
* `octo_send_packet_rate` - current outgoing packet rate on the `octo` channel (combined for all conferences) in
packets per second.
* `p2p_conferences` - current number of peer-to-peer conferences. These are conferences of size 2 in which no endpoint
is sending audio not video. Presumably the endpoints are using a peer-to-peer transport at this time.
* `packet_rate_download` - current RTP incoming packet rate in packets per second.
* `packet_rate_upload` - current RTP outgoing packet rate in packets per second.
* `participants` - current number of endpoints, including `octo` endpoints. Deprecated.
* `preemptive_kfr_sent` - total number of preemptive keyframe requests sent.
* `receive_only_endpoints` - current number of endpoints which are not sending audio nor video.
* `region` - preconfigured region used for bridge selection in jicofo.
* `relay_id` - encodes the `octo` address of this bridge.
* `rtt_aggregate` - round-trip-time measured via RTCP averaged over all local endpoints with a valid RTT measurement in
milliseconds.
* `stress_level` - current stress level on the bridge, with 0 indicating no load and 1 indicating the load is at full
capacity (though values >1 are permitted).
* `threads` - current number of JVM threads.
* `total_bytes_received` - total number of bytes received in RTP.
* `total_bytes_received_octo` - total number of bytes received on the `octo` channel.
* `total_bytes_sent` - total number of bytes sent in RTP.
* `total_bytes_sent_octo` - total number of bytes sent on the `octo` channel.
* `total_colibri_web_socket_messages_received` - total number of messages received on a Colibri "bridge channel" 
messages received on a WebSocket.
* `total_colibri_web_socket_messages_sent` - total number of messages sent over a Colibri "bridge channel" messages 
sent over a WebSocket.
* `total_conference_seconds` - total number of conference-seconds served (only updates once a conference expires).
* `total_conferences_completed` - total number of conferences completed.
* `total_conferences_created` - total number of conferences created.
* `total_data_channel_messages_received` - total number of Colibri "bridge channel" messages received on SCTP data
channels.
* `total_data_channel_messages_sent` - total number of Colibri "bridge channel" messages sent over SCTP data
channels.
* `total_dominant_speaker_changes` - total number of times the dominant speaker in a conference changed.
* `total_failed_conferences` - total number of conferences in which no endpoints succeeded to establish an ICE 
connection.
* `total_ice_failed` - total number of endpoints which failed to establish an ICE connection.
* `total_ice_succeeded` - total number of endpoints which successfully established an ICE connection.
* `total_ice_succeeded_relayed` - total number of endpoints which connected through a TURN relay (currently broken).
* `total_ice_succeeded_tcp` - total number of endpoints which connected through via ICE/TCP (currently broken).
* `total_packets_dropped_octo` - total number of packets dropped on the `octo` channel.
* `total_packets_received` - total number of RTP packets received.
* `total_packets_received_octo` - total number packets received on the `octo` channel.
* `total_packets_sent` - total number of RTP packets sent.
* `total_packets_sent_octo` - total number packets sent over the `octo` channel.
* `total_partially_failed_conferences` - total number of conferences in which at least one endpoint failed to establish
an ICE connection.
* `total_participants` - total number of endpoints created.
* `version` - the version of jitsi-videobridge.
* `videochannels` - current number of endpoints with a video channel (i.e. which support receiving video). Deprecated.
