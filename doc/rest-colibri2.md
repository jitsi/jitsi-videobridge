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
			200 OK with JSON objects which represents <code>endpointsBySpeechActivity</code>,<code>dominantEndpoint</code>, <code>endpointsInLastNOrder</code>,<code> dominantSpeakerIdentification</code> <br />
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
            See the test example 
            <a href="https://github.com/jitsi/jitsi-xmpp-extensions/blob/master/src/test/kotlin/org/jitsi/xmpp/extensions/colibri2/json/Colibri2JSONSerializerTest.kt">
    Colibri2JSONSerializerTest
</a> <br />
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
                            "id": "5bbb5d5e101f870f0ffffffffdf286239",
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
                            "id": "56f8f898101f870f0fffffffff3be6336",
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
                            "id": "c6ac5101f870f0ffffffffcb50fcba",
                            "rel-addr": "172.18.0.4",
                            "priority": "1694498815",
                            "type": "srflx",
                            "network": "0"
                        }
                    ],
                    "xmlns": "urn:xmpp:jingle:transports:ice-udp:1",
                    "ufrag": "26ant1ioqu7qnr",
                    "rtcp-mux": true,
                    "pwd": "9q8uj7tg6j4rc4i6ulc1pmsq9",
                    "web-sockets": [
                        "wss://localhost:8443/colibri-ws/172.18.0.4/7e441eda3701676e/79f0273d?pwd=9q8uj7tg6j4rc4i6ulc1pmsq9"
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
                    "ssrc": 3191927963,
                    "name": "jvb-a0"
                }
            ],
            "id": "jvb-a0",
            "type": "audio"
        },
        {
            "sources": [
                {
                    "ssrc": 1863439907,
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
     Expire conference <br />
			200 OK which represents success. <br />
			For example: 
<pre>
The Request body to expire conference: 
{
  "expire":true
}
</pre>
		</td>
	</tr>
	<tr>
		<td>PATCH</td>
		<td>/colibri/v2/conferences/{mettingId}</td>
		<td>
			200 OK which represents success. <br />
			Here, I am doing it for 
            <a href="https://github.com/jitsi/jitsi-xmpp-extensions/blob/master/src/test/kotlin/org/jitsi/xmpp/extensions/colibri2/json/Colibri2JSONSerializerTest.kt#L326">
    Conference-modified with transport for an endpoint and feedback sources
</a> <br />
In the same way you can do it for
<a href="https://github.com/jitsi/jitsi-xmpp-extensions/blob/master/src/test/kotlin/org/jitsi/xmpp/extensions/colibri2/json/Colibri2JSONSerializerTest.kt#L388C17-L388C59">
    Update endpoint with transport and sources
</a> <br />
For example, a request could look like:
			<pre>
{
  "endpoints": [
    {
      "id":"79f0273e",
      "transport": {
        "transport": {
          "candidates": [
            {
              "generation": "0",
              "rel-port": "9",
              "component": "1",
              "protocol": "udp",
              "port": "10000",
              "ip": "129.80.210.199",
              "foundation": "2",
              "id": "653aa1ba295b62480ffffffffdc52c0d9",
              "rel-addr": "0.0.0.0",
              "priority": "1694498815",
              "type": "srflx",
              "network": "0"
            }
          ],
          "xmlns": "urn:xmpp:jingle:transports:ice-udp:1",
          "ufrag": "2ivqh1fvtf0l3h",
          "rtcp-mux": true,
          "pwd": "1a5ejbent91k6io6a3fauikg22",
          "web-sockets": [
            "wss://beta-us-ashburn-1-global-2808-jvb-83-102-26.jitsi.net:443/colibri-ws/default-id/3d937bbdf97a23e0/79f0273e?pwd=1a5ejbent91k6io6a3fauikg22"
          ],
          "fingerprints": [
            {
              "fingerprint": "2E:CC:85:71:32:5B:B5:60:64:C8:F6:7B:6D:45:D4:34:2B:51:A0:06:B5:EA:2F:84:BC:7B:64:1F:A3:0A:69:23",
              "setup": "actpass",
              "hash": "sha-256",
              "cryptex": true
            }
          ]
        }
      }
    }
  ],
  "sources": [
    {
      "type": "audio",
      "id": "jvb-a0",
      "sources": [
        { "ssrc":411312308, "name": "jvb-a0", "parameters": { "msid": "mixedmslabel mixedlabelaudio0" } }
      ]
    },
    {
      "type": "video",
      "id": "jvb-v0",
      "sources": [
        { "ssrc":3929652146, "name": "jvb-v0", "parameters": { "msid": "mixedmslabel mixedlabelvideo0" } }
      ]
    }
  ]
}</pre>

The respective response could look like:

<pre>
{
    "endpoints": [
        {
            "id": "79f0273e"
        }
    ]
}</pre>
</td>
</tr>
	
</table>
