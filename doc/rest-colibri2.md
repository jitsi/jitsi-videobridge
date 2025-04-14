# Introduction

This document describes the Colibri2 REST APIs

See [this document](rest.md) for how to configure the HTTP(S) interfaces of jitsi-videobridge.

We envision using the HTTP/JSON version of the colibri2 protocol you can see some examples [here](https://github.com/jitsi/jitsi-xmpp-extensions/blob/master/src/test/kotlin/org/jitsi/xmpp/extensions/colibri2/json/Colibri2JSONSerializerTest.kt).

# Implementation

<table>
	<tr>
		<th>HTTP Method</th>
		<th>Resource</th>
		<th>Response</th>
	</tr>
	<tr>
		<td>GET</td>
		<td>/colibri/v2/conferences/{meetingId}/dominant-speaker-identification</td>
		<td>
			200 OK with a JSON array/list of JSON objects
			For example: 
<pre>
{
    "endpointsBySpeechActivity": [
        "79f0273d"
    ],
    "dominantSpeakerIdentification": null,
    "endpointsInLastNOrder": [
        "79f0273d"
    ],
    "dominantEndpoint": "null"
}</pre>
		</td>
	</tr>
	<tr>
		<td>POST</td>
		<td>/colibri/v2/conferences/</td>
		<td>
			200 OK with a JSON object which represents the created conference if the request was with <code>Content-Type: application/json</code> and was a JSON object which 
            consist field like <code> meeting-id</code>, <code>endpoints</code>, media types, media payloads<br />
            See the test example [Colibri2JSONSerializerTest](https://github.com/jitsi/jitsi-xmpp-extensions/blob/master/src/test/kotlin/org/jitsi/xmpp/extensions/colibri2/json/Colibri2JSONSerializerTest.kt)
			For example, a request could look like:
			<pre>
{
  "meeting-id":"beccf2ed-5441-4bfe-96d6-f0f3a6796378",
  "name":"hardencounterssinkright@muc.meet.jitsi",
  "create":true,
  "endpoints":[
    {
      "create":true,
      "id":"79f0273d",
      "stats-id":"Garett-w1o",
      "muc-role":"moderator",
      "medias":[
        {
          "type":"audio",
          "payload-types":[
            {
              "name": "red", "id": "112", "channels": "2", "clockrate": "48000",
              "parameters": { "null": "111/111" }
            },
            {
              "name": "opus", "id": "111", "channels": "2", "clockrate": "48000",
              "parameters": {"useinbandfec": "1", "minptime": "10" },
              "rtcp-fbs": [{"type": "transport-cc"}]
            }
          ],
          "rtp-hdrexts":[
            { "uri":"urn:ietf:params:rtp-hdrext:ssrc-audio-level", "id":1 },
            { "uri":"http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01", "id":5 }
          ],
          "extmap-allow-mixed":true
        },
        {
          "type": "video",
          "payload-types":[
            {
              "name": "VP8", "id": "100", "clockrate": "90000",
              "parameters": {"x-google-start-bitrate": "800"},
              "rtcp-fbs":[
                { "type": "ccm", "subtype": "fir" },
                { "type": "nack" },
                { "type": "nack", "subtype": "pli" },
                { "type": "transport-cc" }
              ]
            },
            {
              "name": "VP9", "id": "101", "clockrate": "90000",
              "parameters": {"x-google-start-bitrate": "800"},
              "rtcp-fbs":[
                { "type": "ccm", "subtype": "fir" },
                { "type": "nack" },
                { "type": "nack", "subtype": "pli" },
                { "type": "transport-cc" }
              ]
            },
            {
              "name": "rtx", "id": "96", "clockrate": "90000",
              "parameters": {"apt": "100"},
              "rtcp-fbs":[
                { "type": "ccm", "subtype": "fir" },
                { "type": "nack" },
                { "type": "nack", "subtype": "pli" }
              ]
            },
            {
              "name": "rtx", "id": "97", "clockrate": "90000",
              "parameters": {"apt": "101"},
              "rtcp-fbs":[
                { "type": "ccm", "subtype": "fir" },
                { "type": "nack" },
                { "type": "nack", "subtype": "pli" }
              ]
            }
          ],
          "rtp-hdrexts":[
            { "uri":"http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time", "id":3 },
            { "uri":"http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01", "id":5 }
          ],
          "extmap-allow-mixed":true
        }
      ],
      "transport": { "ice-controlling": true },
      "capabilities": [ "source-names" ]
    }
  ],
  "connects": [
    { "url": "wss://example.com/audio", "protocol": "mediajson", "type": "transcriber", "audio": true },
    { "url": "wss://example.com/video", "protocol": "mediajson", "type": "recorder", "video": true }
  ]
}</pre>

The respective response could look like:

<pre>
{
    "endpoints": [
        {
            "id": "79f0273d",
            "transport": {
                "transport": {
                    "candidates": [
                        {
                            "generation": "0",
                            "component": "1",
                            "protocol": "udp",
                            "port": "10000",
                            "ip": "172.18.0.4",
                            "foundation": "1",
                            "id": "7be8deb97f7a774a0183e029c",
                            "priority": "2130706431",
                            "type": "host",
                            "network": "0"
                        },
                        {
                            "generation": "0",
                            "rel-port": "10000",
                            "component": "1",
                            "protocol": "udp",
                            "port": "10000",
                            "ip": "192.168.1.1",
                            "foundation": "2",
                            "id": "72c906147f7a774a02cd40399",
                            "rel-addr": "172.18.0.4",
                            "priority": "1694498815",
                            "type": "srflx",
                            "network": "0"
                        },
                        {
                            "generation": "0",
                            "rel-port": "10000",
                            "component": "1",
                            "protocol": "udp",
                            "port": "10000",
                            "ip": "152.58.154.133",
                            "foundation": "2",
                            "id": "2216951d7f7a774a04669d1d",
                            "rel-addr": "172.18.0.4",
                            "priority": "1694498815",
                            "type": "srflx",
                            "network": "0"
                        }
                    ],
                    "xmlns": "urn:xmpp:jingle:transports:ice-udp:1",
                    "ufrag": "2b4it1ioqsv12m",
                    "rtcp-mux": true,
                    "pwd": "4hvp3jj7v4rrt6681oncqvmn2p",
                    "web-sockets": [
                        "wss://localhost:8443/colibri-ws/172.18.0.4/c7dbddb6f857a8ab/79f0273d?pwd=4hvp3jj7v4rrt6681oncqvmn2p"
                    ],
                    "fingerprints": [
                        {
                            "fingerprint": "F1:05:72:F8:F3:F4:B4:65:5B:9A:FC:81:24:11:23:78:D5:42:54:B1:F2:66:2F:82:CC:2B:01:1C:DB:98:BE:C0",
                            "setup": "actpass",
                            "hash": "sha-256"
                        }
                    ]
                }
            }
        }
    ],
    "sources": [
        {
            "sources": [
                {
                    "ssrc": 3490535668,
                    "name": "jvb-a0"
                }
            ],
            "id": "jvb-a0",
            "type": "audio"
        },
        {
            "sources": [
                {
                    "ssrc": 1348796334,
                    "name": "jvb-v0"
                }
            ],
            "id": "jvb-v0",
            "type": "video"
        }
    ]
}</pre>
</td>
</tr>
<tr>
		<td>PATCH</td>
		<td>/colibri/v2/conferences/{mettingId}</td>
		<td>
			200 OK which represents the expire conference. <br />
			For example: 
<pre>
The Request body look like: 
{
  "expire":true
}
</pre>
		</td>
	</tr>
	
</table>
