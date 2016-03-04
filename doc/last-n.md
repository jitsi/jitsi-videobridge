# General
Jitsi Videobridge supports a *lastN* feature for video channels. This is an
integer, which can be set on a specific video channel. If set to *N* for a
channel, only the video from the first *N* other endpoints in the conference
will be sent to the channel. The endpoints are ordered by the last time they
were the "dominant speaker".

To enable lastN and dominant speaker switching, a few things must happen. First, when allocating video channels
for a client on the Jitsi Videobridge, the "last-n" value of the video channel must >= 0.
Secondly, a data channel must be established between the client and the Jitsi Videobridge. Third, the client must implement
the client-to-mixer audio level RTP header extension (RFC6464) and the focus must forward them to the videobridge. The Jitsi Videobridge uses this RTP header extension data to determine the current dominant speaker.

# Client Side Events
Clients will receive events over the data channel from the Jitsi Videobridge whenever there is a change in
the dominant speaker or lastN endpoints.

**LastNEndpointsChangeEvent**
```json
{
    "colibriClass": "LastNEndpointsChangeEvent",
    "endpointsEnteringLastN": [
      "myuserJid@somewhere.com/chat-1234"
    ],
    "lastNEndpoints": [
      "myuserJid@somewhere.com/chat-1234"
    ],
    "conferenceEndpoints": [
      "myuserJid@somewhere.com/chat-1234", "another-endpoint"
    ]
}
```
The "lastNEndpoints" array contains the IDs of the endpoints for which video is
currently being forwarded from the bridge to the client, and only these
endpoints. The list is ordered according to the history of the speech activity in
the conference. That is, the first entry in the list is the one which has been dominant
speaker most recently. This list is not necessarily an initial segment of the 
"conferenceEndpoints" list, because of endpoint pinning.

The "endpointsEnteringLastN" array contains the IDs of the endpoints which are
in the "lastNEndpoints" array, but were not in the array the last time the
event was sent. I.e. their video was not being forwarded, but now it is.

The "conferenceEndpoints" array contains the IDs of the endpoints in the
conference, ordered by speech activity. That is, the first element is the
current dominant speaker, the second element is the previous dominant speaker
and so on. The list is limited to the "lastN" value for the endpoint (which may
or may not be the same as the size of "lastNEndpoints").

For example, if we have a conference with endpoints "a", "b", "c", "d", "e",
ordered by speech activity in this way, we have lastN=4, we have "e" as a pinned endpoint,
and previously no video was being forwarded (e.g. adaptive last-n had dropped the
number of forwarded streams to 0), we could receive the following:

```json
{
    "colibriClass": "LastNEndpointsChangeEvent",
    "endpointsEnteringLastN": [
      "a", "b", "e"
    ],
    "lastNEndpoints": [
     "a", "b", "e"
    ],
    "conferenceEndpoints": [
      "a", "b", "c", "d"
    ]
}
```


**DominantSpeakerEndpointChangeEvent**
```json
{
    "colibriClass":"DominantSpeakerEndpointChangeEvent",
    "dominantSpeakerEndpoint":"myuserJid@somewhere.com/chat-1234"
}
```
This event could be used by the client to raise the UI component displaying the new
dominant speaker and minimize UI components for participants who are not currently
the dominant speaker.

# Pinning
Clients can "pin" specific endpoints in order to "override" the set of streams being forwarded according to LastN. For example:

- LastN = 2
- Clients Alice and Bob are speaking
- Client Charlie is sharing his screen but not speaking
- Client Diane wants to get Charlie's screen stream regardless of who is speaking

To achieve this:
- Client Diane sends a `PinnedEndpointChangedEvent` event over the LastN data channel with Charlie's endpoint
- Jitsi VideoBridge begins forwarding Charlie's screen stream and either Alice's or Bob's video streams

```json
{
    "colibriClass": "PinnedEndpointChangedEvent",
    "pinnedEndpoint": "charlie@somewhere.com/chat-1234"
}
```

# Signaling Example
The following example is for a case where a focus controller is handling signaling between a jingle
client and the Jitsi Videobridge.


**Focus To Videobridge Signaling To Allocate Channels for a Client (Colibri)**

Example stanza to allocate audio and video channels for a client on the bridge setting the last-n value to 2.
Note: the parameter passed to the bridge is "last-n" but elsewhere in documentation it is referred to as "lastN".
```xml
<iq to="jitsi-videobridge.somewhere.com" from="myfocusId@conference.somewhere.com" id="564e42fe0000155716c7e6b4" type="set">
   <conference xmlns="http://jitsi.org/protocol/colibri">
      <content name="audio">
         <channel initiator="true" endpoint="chat-1234" />
      </content>
      <content name="video">
         <channel initiator="true" endpoint="chat-1234" last-n="2" />
      </content>
      <content name="data">
         <sctpconnection initiator="true" endpoint="chat-1234" port="5000" />
      </content>
   </conference>
</iq>
```

**Focus to Client Signaling (Jingle)**
Below is an example jingle session-init stanza that would be sent to the client including the audio-level hdr extension
and the data channel needed for dominant speaker and lastN capability.
```xml
<iq xmlns="jabber:client" from="myfocusId@conference.somewhere.com" id="564e3bcd00000013c9c342b6" type="set" to="chat-1234">
   <jingle xmlns="urn:xmpp:jingle:1" initiator="myfocusId@conference.somewhere.com" action="session-initiate" sid="785e63ce2145b0">
      <content creator="initiator" name="audio" senders="both">
         <description xmlns="urn:xmpp:jingle:apps:rtp:1" media="audio">
            <payload-type id="111" name="opus" clockrate="48000" channels="2">
               <parameter name="minptime" value="10" />
            </payload-type>
            <payload-type id="103" name="ISAC" clockrate="16000" />
            <payload-type id="104" name="ISAC" clockrate="32000" />
            <payload-type id="9" name="G722" clockrate="8000" />
            <payload-type id="0" name="PCMU" clockrate="8000" />
            <payload-type id="8" name="PCMA" clockrate="8000" />
            <rtp-hdrext xmlns="urn:xmpp:jingle:apps:rtp:rtp-hdrext:0" id="1" uri="urn:ietf:params:rtp-hdrext:ssrc-audio-level" />
            <rtp-hdrext xmlns="urn:xmpp:jingle:apps:rtp:rtp-hdrext:0" id="3" uri="http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time" />
            <rtcp-mux />
         </description>
         <transport xmlns="urn:xmpp:jingle:transports:ice-udp:1" pwd="3623d1d2rl9407rpq9q6geom4r" ufrag="8gd0v1a4gpiti5">
            <fingerprint xmlns="urn:xmpp:jingle:apps:dtls:0" hash="sha-1" setup="actpass">D7:FB:A1:9E:67:58:FC:60:73:C0:49:E3:15:7E:45:66:0F:30:9D:3A</fingerprint>
            <candidate component="1" foundation="1" generation="0" id="785e63ce2145b079d34ca515bb1f405b0033e6" network="0" priority="2130706431" protocol="udp" type="host" ip="172.17.176.16" port="10000" />
            <candidate component="1" foundation="2" generation="0" id="785e63ce2145b079d34ca515bb1f405e45ffbf" network="0" priority="2130706431" protocol="udp" type="host" ip="192.168.99.1" port="10000" />
            <candidate component="1" foundation="3" generation="0" id="785e63ce2145b079d34ca515bb1f40459984f3" network="0" priority="2113932031" protocol="udp" type="host" ip="172.18.178.75" port="10000" />
            <candidate component="2" foundation="1" generation="0" id="785e63ce2145b079d34ca515bb1f4061e79137" network="0" priority="2130706430" protocol="udp" type="host" ip="172.17.176.16" port="10001" />
            <candidate component="2" foundation="2" generation="0" id="785e63ce2145b079d34ca515bb1f4029eee418" network="0" priority="2130706430" protocol="udp" type="host" ip="192.168.99.1" port="10001" />
            <candidate component="2" foundation="3" generation="0" id="785e63ce2145b079d34ca515bb1f402c0f92a" network="0" priority="2113932030" protocol="udp" type="host" ip="172.18.178.75" port="10001" />
         </transport>
      </content>
      <content creator="initiator" name="video" senders="both">
         <description xmlns="urn:xmpp:jingle:apps:rtp:1" media="video">
            <payload-type id="100" name="VP8" clockrate="90000">
               <rtcp-fb xmlns="urn:xmpp:jingle:apps:rtp:rtcp-fb:0" type="ccm" subtype="fir" />
               <rtcp-fb xmlns="urn:xmpp:jingle:apps:rtp:rtcp-fb:0" type="nack" />
               <rtcp-fb xmlns="urn:xmpp:jingle:apps:rtp:rtcp-fb:0" type="nack" subtype="pli" />
               <rtcp-fb xmlns="urn:xmpp:jingle:apps:rtp:rtcp-fb:0" type="goog-remb" />
            </payload-type>
            <payload-type id="116" name="red" clockrate="90000" />
            <payload-type id="117" name="ulpfec" clockrate="90000" />
            <rtp-hdrext xmlns="urn:xmpp:jingle:apps:rtp:rtp-hdrext:0" id="2" uri="urn:ietf:params:rtp-hdrext:toffset" />
            <rtp-hdrext xmlns="urn:xmpp:jingle:apps:rtp:rtp-hdrext:0" id="3" uri="http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time" />
            <rtcp-mux />
         </description>
         <transport xmlns="urn:xmpp:jingle:transports:ice-udp:1" pwd="7qoa29251372qka443d9sdvrsq" ufrag="aerv81a4gpj5sn">
            <fingerprint xmlns="urn:xmpp:jingle:apps:dtls:0" hash="sha-1" setup="actpass">8C:B8:1B:AE:EE:01:23:E1:C1:8F:FC:17:D4:E6:CD:1C:CC:1C:F0:47</fingerprint>
            <candidate component="1" foundation="1" generation="0" id="785e63ce2145b0250ef83c2b51ce8207cccf06" network="0" priority="2130706431" protocol="udp" type="host" ip="172.17.176.16" port="10002" />
            <candidate component="1" foundation="2" generation="0" id="785e63ce2145b0250ef83c2b51ce820172b96ea" network="0" priority="2130706431" protocol="udp" type="host" ip="192.168.99.1" port="10002" />
            <candidate component="1" foundation="3" generation="0" id="785e63ce2145b0250ef83c2b51ce8202ec0e888" network="0" priority="2113932031" protocol="udp" type="host" ip="172.18.178.75" port="10002" />
            <candidate component="2" foundation="1" generation="0" id="785e63ce2145b0250ef83c2b51ce8207ae250b2" network="0" priority="2130706430" protocol="udp" type="host" ip="172.17.176.16" port="10003" />
            <candidate component="2" foundation="2" generation="0" id="785e63ce2145b0250ef83c2b51ce82020201525" network="0" priority="2130706430" protocol="udp" type="host" ip="192.168.99.1" port="10003" />
            <candidate component="2" foundation="3" generation="0" id="785e63ce2145b0250ef83c2b51ce8201a4c409d" network="0" priority="2113932030" protocol="udp" type="host" ip="172.18.178.75" port="10003" />
         </transport>
      </content>
      <content creator="initiator" name="data" senders="both">
         <description xmlns="urn:xmpp:jingle:transports:webrtc-datachannel:0" type="datachannel" />
         <transport xmlns="urn:xmpp:jingle:transports:ice-udp:1" pwd="1mvii60vtvraadsj5lkoln3551" ufrag="7u3l71a4gpj60b">
            <rtcp-mux />
            <fingerprint xmlns="urn:xmpp:jingle:apps:dtls:0" hash="sha-1" setup="actpass">01:B3:47:71:57:28:7E:5D:9A:69:82:A1:AB:47:2B:94:2A:BF:B9:44</fingerprint>
            <candidate component="1" foundation="1" generation="0" id="785e63ce2145b025eb06ed7fa66cfc073d55dd" network="0" priority="2130706431" protocol="udp" type="host" ip="172.17.176.16" port="10004" />
            <candidate component="1" foundation="2" generation="0" id="785e63ce2145b025eb06ed7fa66cfc04bd023e1" network="0" priority="2130706431" protocol="udp" type="host" ip="192.168.99.1" port="10004" />
            <candidate component="1" foundation="3" generation="0" id="785e63ce2145b025eb06ed7fa66cfc04de3afa9" network="0" priority="2113932031" protocol="udp" type="host" ip="172.18.178.75" port="10004" />
            <candidate component="1" foundation="4" generation="0" id="785e63ce2145b025eb06ed7fa66cfc01f21a832" network="0" priority="2113932031" protocol="ssltcp" tcptype="passive" type="host" ip="172.17.176.16" port="4443" />
            <candidate component="1" foundation="5" generation="0" id="785e63ce2145b025eb06ed7fa66cfc05aa97a95" network="0" priority="2113932031" protocol="ssltcp" tcptype="passive" type="host" ip="192.168.99.1" port="4443" />
            <candidate component="1" foundation="6" generation="0" id="785e63ce2145b025eb06ed7fa66cfc02caefab3" network="0" priority="2113932031" protocol="ssltcp" tcptype="passive" type="host" ip="172.18.178.75" port="4443" />
            <sctpmap xmlns="urn:xmpp:jingle:transports:dtls-sctp:1" number="5000" protocol="webrtc-datachannel" streams="1024" />
         </transport>
      </content>
   </jingle>
</iq>
```
