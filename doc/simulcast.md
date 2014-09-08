Introduction
============

At the SDP level, and in Jingle, there is the notion of ssrc-groups
([rfc-5576], [xep-jingle-sources]). In Chrome we have seen two kinds
of groups : FID and SIM groups. Consider this SDP fragment for
example:

    a=ssrc-group:SIM 1 2
    a=ssrc-group:FID 1 3
    a=ssrc-group:FID 2 4

It describes 4 streams. SSRCs 1 and 2 are different spatial layers
(simulcast) of the same video stream. SSRC 3 is the FEC of SSRC 1, and
4 is the FEC of SSRC 2.

In the Jitsi videobridge, we use the ssrc-group:SIM attribute to
signal the simulcast.

Signaling the simulcast to the focus through Jingle
===================================================

Each participant (the answerer) has to signal (through Jingle) its
simulcast to the focus (the offerrer) using the the a=ssrc-group:SIM
attribute. For example, this answer :

	type: answer, sdp: v=0
	o=- 4795651232791988616 2 IN IP4 127.0.0.1
	s=-
	t=0 0
	a=msid-semantic: WMS T9nYMtmHCHLmVVIZsboAFz6RdXeGbBPx3Vfd X8kJW3o7gFbgolmwa83PbdRKArcI18meexII
	m=audio 1 RTP/SAVPF 111 103 104 0 8 106 105 13 126
	c=IN IP4 0.0.0.0
	a=rtcp:1 IN IP4 0.0.0.0
	a=ice-ufrag:OUBzpIHK06/LCTMu
	a=ice-pwd:SbM8Iym2SFEJjm7gBWRk5KmC
	a=fingerprint:sha-256 AE:2C:F2:3B:40:EB:A7:50:69:31:8B:02:E3:29:4F:9C:91:F3:36:CF:2D:54:47:5A:EE:F3:AA:D9:84:EE:26:87
	a=setup:active
	a=mid:audio
	a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level
	a=sendrecv
	a=rtpmap:111 opus/48000/2
	a=fmtp:111 minptime=10
	a=rtpmap:103 ISAC/16000
	a=rtpmap:104 ISAC/32000
	a=rtpmap:0 PCMU/8000
	a=rtpmap:8 PCMA/8000
	a=rtpmap:106 CN/32000
	a=rtpmap:105 CN/16000
	a=rtpmap:13 CN/8000
	a=rtpmap:126 telephone-event/8000
	a=maxptime:60
	a=ssrc:1921898767 cname:lwh6JMryF12bed0V
	a=ssrc:1921898767 msid:X8kJW3o7gFbgolmwa83PbdRKArcI18meexII 23029d7e-59f8-43bd-9f56-2a6835cb7e36
	a=ssrc:1921898767 mslabel:X8kJW3o7gFbgolmwa83PbdRKArcI18meexII
	a=ssrc:1921898767 label:23029d7e-59f8-43bd-9f56-2a6835cb7e36
	m=video 1 RTP/SAVPF 100 116 117
	c=IN IP4 0.0.0.0
	a=rtcp:1 IN IP4 0.0.0.0
	a=ice-ufrag:5w9Q1W/zdnE3j+lz
	a=ice-pwd:0+btTCK0ygUftcCui9aMyhuQ
	a=fingerprint:sha-256 AE:2C:F2:3B:40:EB:A7:50:69:31:8B:02:E3:29:4F:9C:91:F3:36:CF:2D:54:47:5A:EE:F3:AA:D9:84:EE:26:87
	a=setup:active
	a=mid:video
	a=extmap:2 urn:ietf:params:rtp-hdrext:toffset
	a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
	a=sendrecv
	a=rtpmap:100 VP8/90000
	a=rtcp-fb:100 ccm fir
	a=rtcp-fb:100 nack
	a=rtcp-fb:100 goog-remb
	a=rtpmap:116 red/90000
	a=rtpmap:117 ulpfec/90000
	a=ssrc:541128531 cname:Bvp+0tOqyf3pAwUA
	a=ssrc:541128531 msid:T9nYMtmHCHLmVVIZsboAFz6RdXeGbBPx3Vfd 5d9bbd8e-2808-4cba-9d51-7f869eee48c0
	a=ssrc:541128531 mslabel:T9nYMtmHCHLmVVIZsboAFz6RdXeGbBPx3Vfd
	a=ssrc:541128531 label:5d9bbd8e-2808-4cba-9d51-7f869eee48c0
	a=ssrc:3297418201 cname:Bvp+0tOqyf3pAwUA
	a=ssrc:3297418201 msid:T9nYMtmHCHLmVVIZsboAFz6RdXeGbBPx3Vfd 7df4a466-15d9-46af-aee9-743464e6fd83
	a=ssrc:3297418201 mslabel:T9nYMtmHCHLmVVIZsboAFz6RdXeGbBPx3Vfd
	a=ssrc:3297418201 label:7df4a466-15d9-46af-aee9-743464e6fd83
	m=application 1 DTLS/SCTP 5000
	c=IN IP4 0.0.0.0
	b=AS:30
	a=ice-ufrag:Wah9bVhj3jX/PNLV
	a=ice-pwd:uLsRYkjpeXMEuon74D46Cncg
	a=fingerprint:sha-256 AE:2C:F2:3B:40:EB:A7:50:69:31:8B:02:E3:29:4F:9C:91:F3:36:CF:2D:54:47:5A:EE:F3:AA:D9:84:EE:26:87
	a=setup:active
	a=mid:data
	a=sctpmap:5000 webrtc-datachannel 1024

Needs to be modified like this:

	type: answer, sdp: v=0
	o=- 4795651232791988616 2 IN IP4 127.0.0.1
	s=-
	t=0 0
	a=msid-semantic: WMS T9nYMtmHCHLmVVIZsboAFz6RdXeGbBPx3Vfd X8kJW3o7gFbgolmwa83PbdRKArcI18meexII
	m=audio 1 RTP/SAVPF 111 103 104 0 8 106 105 13 126
	c=IN IP4 0.0.0.0
	a=rtcp:1 IN IP4 0.0.0.0
	a=ice-ufrag:OUBzpIHK06/LCTMu
	a=ice-pwd:SbM8Iym2SFEJjm7gBWRk5KmC
	a=fingerprint:sha-256 AE:2C:F2:3B:40:EB:A7:50:69:31:8B:02:E3:29:4F:9C:91:F3:36:CF:2D:54:47:5A:EE:F3:AA:D9:84:EE:26:87
	a=setup:active
	a=mid:audio
	a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level
	a=sendrecv
	a=rtpmap:111 opus/48000/2
	a=fmtp:111 minptime=10
	a=rtpmap:103 ISAC/16000
	a=rtpmap:104 ISAC/32000
	a=rtpmap:0 PCMU/8000
	a=rtpmap:8 PCMA/8000
	a=rtpmap:106 CN/32000
	a=rtpmap:105 CN/16000
	a=rtpmap:13 CN/8000
	a=rtpmap:126 telephone-event/8000
	a=maxptime:60
	a=ssrc:1921898767 cname:lwh6JMryF12bed0V
	a=ssrc:1921898767 msid:X8kJW3o7gFbgolmwa83PbdRKArcI18meexII 23029d7e-59f8-43bd-9f56-2a6835cb7e36
	a=ssrc:1921898767 mslabel:X8kJW3o7gFbgolmwa83PbdRKArcI18meexII
	a=ssrc:1921898767 label:23029d7e-59f8-43bd-9f56-2a6835cb7e36
	m=video 1 RTP/SAVPF 100 116 117
	c=IN IP4 0.0.0.0
	a=rtcp:1 IN IP4 0.0.0.0
	a=ice-ufrag:5w9Q1W/zdnE3j+lz
	a=ice-pwd:0+btTCK0ygUftcCui9aMyhuQ
	a=fingerprint:sha-256 AE:2C:F2:3B:40:EB:A7:50:69:31:8B:02:E3:29:4F:9C:91:F3:36:CF:2D:54:47:5A:EE:F3:AA:D9:84:EE:26:87
	a=setup:active
	a=mid:video
	a=extmap:2 urn:ietf:params:rtp-hdrext:toffset
	a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
	a=sendrecv
	a=rtpmap:100 VP8/90000
	a=rtcp-fb:100 ccm fir
	a=rtcp-fb:100 nack
	a=rtcp-fb:100 goog-remb
	a=rtpmap:116 red/90000
	a=rtpmap:117 ulpfec/90000
	a=ssrc-group:SIM 541128531 3297418201
	a=ssrc:541128531 cname:Bvp+0tOqyf3pAwUA
	a=ssrc:541128531 msid:T9nYMtmHCHLmVVIZsboAFz6RdXeGbBPx3Vfd 5d9bbd8e-2808-4cba-9d51-7f869eee48c0
	a=ssrc:541128531 mslabel:T9nYMtmHCHLmVVIZsboAFz6RdXeGbBPx3Vfd
	a=ssrc:541128531 label:5d9bbd8e-2808-4cba-9d51-7f869eee48c0
	a=ssrc:3297418201 cname:Bvp+0tOqyf3pAwUA
	a=ssrc:3297418201 msid:T9nYMtmHCHLmVVIZsboAFz6RdXeGbBPx3Vfd 7df4a466-15d9-46af-aee9-743464e6fd83
	a=ssrc:3297418201 mslabel:T9nYMtmHCHLmVVIZsboAFz6RdXeGbBPx3Vfd
	a=ssrc:3297418201 label:7df4a466-15d9-46af-aee9-743464e6fd83
	m=application 1 DTLS/SCTP 5000
	c=IN IP4 0.0.0.0
	b=AS:30
	a=ice-ufrag:Wah9bVhj3jX/PNLV
	a=ice-pwd:uLsRYkjpeXMEuon74D46Cncg
	a=fingerprint:sha-256 AE:2C:F2:3B:40:EB:A7:50:69:31:8B:02:E3:29:4F:9C:91:F3:36:CF:2D:54:47:5A:EE:F3:AA:D9:84:EE:26:87
	a=setup:active
	a=mid:data
	a=sctpmap:5000 webrtc-datachannel 1024

Signaling the simulcast to the bridge through COLIBRI
=====================================================

In order to enable the bridge to perform its routing even before the
data channels are up, we want to signal the relationship between the
SSRCs of a sender to the bridge through COLIBRI.

We signal the "complete" picture to the bridge like this:

	<content name='video'>
	  <channel id='c9726594ccb4ede7'>
		<payload-type id='100' name='VP8' clockrate='90000' channels='1'/>
		...
		<ssrc-group semantics='SIM'>
		  <source ssrc='1'>
		  <source ssrc='2'>
		</ssrc-group>
		<ssrc-group semantics='FID'>
		  <source ssrc='1'>
		  <source ssrc='3'>
		</ssrc-group>
		<ssrc-group semantics='FID'>
		  <source ssrc='2'>
		  <source ssrc='4'>
		</ssrc-group>
	  </channel>
	  ...
	</content>

The order of the sources in the ssrc simulcast group is important and
must be from lowest to highest quality.

Configuring video channels through COLIBRI
==========================================

Through COLIBRI, a specific video channel can be configured to receive
a specific spatial layer like this:

    <content name='video'>
      <channel id='c9726594ccb4ede7' receiving-simulcast-layer='XXX'/>
      ...
    </content>

Where XXX can be 0 for low quality, 1 for high quality, 2 for higher
quality, ... .

The endpoints are notified through data channels about the change and
they must switch the video track accordingly. Schematically, this is
what we're doing:

    [focus] --COLIBRI--> [bridge] == data channels ==>> [endpoints X, Y, ..]

Thanks to the initial signaling through COLIBRI, the bridge knows
exactly which SSRCs correspond to which spatial layer and this
information will be signalled through data channels to the endpoints.

So, the above COLIBRI message will result in a data channel
notification to the peer notifying it that the streams it receives
have changed. The endpoint will receive a message like this:

	{
		colibriClass = 'SimulcastLayersChangedEvent', 
		endpointSimulcastLayers: [
			{
				'endpoint': 'zez2rE4zzA', 
				'simulcastLayer': {'primarySSRC': 'A'}
			}, 
			...
		]
	}
 
An endpoint knows to which track the SSRC A corresponds thanks to
Jingle signaling with the focus.

[rfc-5576]: http://tools.ietf.org/html/rfc5576 "Source-Specific Media Attributes in the Session Description Protocol (SDP)"
[xep-jingle-sources]: http://www.xmpp.org/extensions/inbox/jingle-sources.html "XEP-xxxx: Source-Specific Media Attributes in Jingle"
