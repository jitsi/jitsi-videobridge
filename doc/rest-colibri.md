Introduction
==============

This document describes the REST APIs and the JSON format used with
the REST version of the [COLIBRI protocol](https://xmpp.org/extensions/xep-0340.html).

See [this document](rest.md) for how to configure the HTTP(S) interfaces of jitsi-videobridge.

Implementation
==============

<table>
	<tr>
		<th>HTTP Method</th>
		<th>Resource</th>
		<th>Response</th>
	</tr>
	<tr>
		<td>GET</td>
		<td>/colibri/conferences</td>
		<td>
			200 OK with a JSON array/list of JSON objects which represent conferences with <code>id</code> only.<br />
			For example: 
<pre>
[ 
	{ "id" : "a1b2c3" }, 
	{ "id" : "d4e5f6" } 
]</pre>
		</td>
	</tr>
	<tr>
		<td>POST</td>
		<td>/colibri/conferences</td>
		<td>
			200 OK with a JSON object which represents the created conference if the request was with <code>Content-Type: application/json</code> and was a JSON object which represented a conference without <code>id</code> and, optionally, with contents and channels without <code>id</code>s. <br />
			For example, a request could look like:
			<pre>
{ 
	"contents" : 
	[ 
		{
			 "name" : "audio", 
			 "channels" : [ { "expire" : 60 } ] 
		}, 
		{ 
			"name" : "video", 
			"channels" : [ { "expire" : 60 } ] 
		} 
	] 
}</pre>

The respective response could look like:
<pre>
{ 
	"id" : "conference1", 
	"contents" : 
		[ 
			{ 
				"name" : "audio", 
				"channels" : 
					[
						 { "id" : "channelA" }, 
						 { "expire" : 60 }, 
						 { "rtp-level-relay-type" : "translator" } 
					 ]
			 }, 
			 { 
			 	"name" : "video", 
			 	"channels" : 
			 		[ 
			 			{ "id" : "channelV" }, 
			 			{ "expire" : 60 }, 
			 			{ "rtp-level-relay-type" : "translator" } 
		 			] 
 			} 
		] 
}</pre>
</td>
</tr>
<tr>
		<td>GET</td>
		<td>/colibri/conferences/{id}</td>
		<td>
			200 OK with a JSON object which represents the conference with the specified <code>id</code>. <br />
			For example: 
<pre>
{ 
	"id" : "{id}", 
	"contents" : 
		[ 
			{ 
				"name" : "audio", 
				"channels" : 
					[ 
						{ "id" : "channelA" }, 
						{ "expire" : 60 }, 
						{ "rtp-level-relay-type" : "translator" } 
					] 
			}, 
			{ 
				"name" : "video", 
					"channels" : 
						[ 
							{ "id" : "channelV" }, 
							{ "expire" : 60 }, 
							{ "rtp-level-relay-type" : "translator" } 
						] 
			} 
		] 
}</pre>
		</td>
	</tr>
	<tr>
		<td>PATCH</td>
		<td>/colibri/conferences/{id}</td>
		<td>
			200 OK with a JSON object which represents the modified conference if the request was with <code>Content-Type: application/json</code> and was a JSON object which represented a conference without <code>id</code> or with the specified <code>id</code> and, optionally, with contents and channels with or without <code>id</code>s.
		</td>
	</tr>
	<tr>
		<td>GET</td>
		<td>/colibri/stats</td>
		<td>
		200 OK with a JSON object which represents the statistics report.
<pre>
{
    "rtp_loss":"0",
    "bit_rate_download":"0",
    "audiochannels":0,
    "bit_rate_upload":"0",
    "conferences":0,
    "participants":0,
    "current_timestamp":"2014-08-14 23:26:14.782",
    "threads":17,
    "videochannels":0
}</pre>
		(Make sure <a href="statistics.md#configuration">statistics are enabled</a>.)
		</td>
	</tr>
</table>

Example
==============

1. Create the conference by posting an empty json object:
Post "[]" to <bridge_base_url>/colibri/conferences/

2. Bridge will respond with a json response that contains the colibri conference id:
```json
{"id":"a439deb315b4128c"}
```

3. When a client joins, allocate channels on the bridge for that client.  Here we allocate audio, video and data channels for a client.  We include the colibri conference id of the conference to which this client will belong.  We also set last-n for the video channel here to "2" to enable last-n mode, and the relay type to 'mixer' for audio so we get a single mixed audio stream from the bridge.  We do this by sending a Patch to <bridge_base_url>/colibri/conferences/a439deb315b4128c (note we now send the patch to a url that contains the conference id).

```json
{
  "id": "a439deb315b4128c",
  "contents": [
    {
      "name": "audio",
      "channels": [
        {
          "expire": 10,
          "initiator": true,
          "endpoint": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "direction": "sendrecv",
          "channel-bundle-id": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "rtp-level-relay-type": "mixer"
        }
      ]
    },
    {
      "name": "video",
      "channels": [
        {
          "expire": 10,
          "initiator": true,
          "endpoint": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "direction": "sendrecv",
          "channel-bundle-id": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "last-n": 2
        }
      ]
    },
    {
      "name": "data",
      "sctpconnections": [
        {
          "expire": 20,
          "initiator": true,
          "endpoint": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "port": 5000,
          "channel-bundle-id": "9f537ebb-1c2a-4ee9-9940-373304f9b260"
        }
      ]
    }
  ],
  "channel-bundles": [
    {
      "id": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
      "transport": {
        "xmlns": "urn:xmpp:jingle:transports:ice-udp:1",
        "rtcp-mux": true
      }
    }
  ]
}
```
4. In the response, the bridge will include some information it has assigned to the endpoint (like ids for the endpoint and all the channels that it created), as well as its ICE information
```json
{
  "channel-bundles": [
    {
      "id": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
      "transport": {
        "candidates": [
          {
            "generation": 0,
            "component": 1,
            "protocol": "ssltcp",
            "port": 443,
            "ip": "192.168.142.251",
            "tcptype": "passive",
            "foundation": "1",
            "id": "a439deb315b4128c592d42d8fd48f990ffffffffce863924",
            "priority": 2130706431,
            "type": "host",
            "network": 0
          },
          {
            "generation": 0,
            "component": 1,
            "protocol": "ssltcp",
            "port": 443,
            "ip": "2001:0:9d38:6abd:3894:2bef:cd37:1a05",
            "tcptype": "passive",
            "foundation": "2",
            "id": "a439deb315b4128c592d42d8fd48f990ffffffffcfe258da",
            "priority": 2130706431,
            "type": "host",
            "network": 0
          },
          {
            "generation": 0,
            "component": 1,
            "protocol": "udp",
            "port": 10000,
            "ip": "2001:0:9d38:6abd:3894:2bef:cd37:1a05",
            "foundation": "4",
            "id": "a439deb315b4128c592d42d8fd48f990ffffffffcfe27e2f",
            "priority": 2113939711,
            "type": "host",
            "network": 0
          },
          {
            "generation": 0,
            "component": 1,
            "protocol": "udp",
            "port": 10000,
            "ip": "192.168.142.251",
            "foundation": "3",
            "id": "a439deb315b4128c592d42d8fd48f990ffffffffce865e79",
            "priority": 2113932031,
            "type": "host",
            "network": 0
          }
        ],
        "xmlns": "urn:xmpp:jingle:transports:ice-udp:1",
        "ufrag": "1t16a1ai1rpu7f",
        "rtcp-mux": true,
        "pwd": "4isr88ofimikd2c2knp03t3jm5",
        "fingerprints": [
          {
            "fingerprint": "8F:B5:A3:C2:35:1D:42:E4:B6:B4:F1:08:F8:5D:47:38:2D:E8:07:F8",
            "setup": "actpass",
            "hash": "sha-1"
          }
        ]
      }
    }
  ],
  "contents": [
    {
      "channels": [
        {
          "endpoint": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "channel-bundle-id": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "sources": [
            3816478770
          ],
          "rtp-level-relay-type": "mixer",
          "expire": 10,
          "initiator": true,
          "id": "e4023f2a35c47d57",
          "direction": "recvonly"
        }
      ],
      "name": "audio"
    },
    {
      "channels": [
        {
          "endpoint": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "channel-bundle-id": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "sources": [
            2504560421
          ],
          "rtp-level-relay-type": "translator",
          "expire": 10,
          "initiator": true,
          "id": "f52e248a094d3334",
          "receive-simulcast-layer": null,
          "direction": "sendrecv",
          "last-n": 2
        }
      ],
      "name": "video"
    },
    {
      "sctpconnections": [
        {
          "endpoint": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "channel-bundle-id": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "port": 5000,
          "expire": 20,
          "initiator": true,
          "id": "5c258c1a66d1c021"
        }
      ],
      "name": "data"
    }
  ],
  "id": "a439deb315b4128c"
}
```

5. We take that response and translate it into SDP to create an offer for the new client.
6. The client will send back an answer that we translate from SDP into colibri.  It will now contain new information about the client, like which payload types, codecs and ssrcs it will be using.  We then patch the conference again with the contents of that colibri message (again to <bridge_base_url>/colibri/conferences/a439deb315b4128c)
```json
{
  "id": "a439deb315b4128c",
  "contents": [
    {
      "name": "audio",
      "channels": [
        {
          "id": "e4023f2a35c47d57",
          "expire": 10,
          "initiator": true,
          "endpoint": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "direction": "sendonly",
          "sources": [
            
          ],
          "channel-bundle-id": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "rtp-level-relay-type": "translator",
          "ssrc-groups": [
            
          ],
          "payload-types": [
            {
              "id": 111,
              "name": "opus",
              "clockrate": 48000,
              "channels": 2,
              "parameters": {
                "minptime": 10,
                "useinbandfec": 1
              }
            }
          ],
          "rtp-hdrexts": [
            {
              "id": 1,
              "uri": "urn:ietf:params:rtp-hdrext:ssrc-audio-level"
            }
          ]
        }
      ]
    },
    {
      "name": "video",
      "channels": [
        {
          "id": "f52e248a094d3334",
          "expire": 10,
          "initiator": true,
          "endpoint": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "direction": "sendonly",
          "sources": [
            1
          ],
          "channel-bundle-id": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "rtp-level-relay-type": "translator",
          "ssrc-groups": [
          {
            "semantics": "FID",
            "sources": [
              2,
              3
            ]}
          ],
          "payload-types": [
            {
              "id": 127,
              "name": "H264",
              "clockrate": 90000,
              "channels": 0,
              "parameters": {},
              "rtcp-fbs": [ {
                "type": "ccm",
                "subtype": "fir"
              }, {
                "type": "nack"
              }, {
                "type": "nack",
                "subtype": "pli"
              } ]
            },
            {
              "id": 100,
              "name": "VP8",
              "clockrate": 90000,
              "channels": 0,
              "parameters": {},
              "rtcp-fbs": [ {
                "type": "ccm",
                "subtype": "fir"
              }, {
                "type": "nack"
              }, {
                "type": "goog-remb"
              } ]
            }
          ],
          "rtp-hdrexts": [
            {
              "id": 2,
              "uri": "urn:ietf:params:rtp-hdrext:toffset"
            },
            {
              "id": 3,
              "uri": "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time"
            }
          ]
        }
      ]
    },
    {
      "name": "data",
      "sctpconnections": [
        {
          "id": "5c258c1a66d1c021",
          "expire": 20,
          "initiator": true,
          "endpoint": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
          "port": 5000,
          "channel-bundle-id": "9f537ebb-1c2a-4ee9-9940-373304f9b260"
        }
      ]
    }
  ],
  "channel-bundles": [
    {
      "id": "9f537ebb-1c2a-4ee9-9940-373304f9b260",
      "transport": {
        "candidates": [
          
        ],
        "fingerprints": [
          {
            "fingerprint": "49:37:35:23:0E:06:27:BD:08:BA:F9:EB:8E:D8:65:66:1E:26:22:BB:E8:8A:02:54:2B:74:28:63:A6:34:78:B9",
            "hash": "sha-256"
          }
        ],
        "pwd": "qZ6ehCjUjOS04MxUq+ahdctA",
        "ufrag": "CBdMLgEmFdIJ9xjJ",
        "xmlns": "urn:xmpp:jingle:transports:ice-udp:1",
        "rtcp-mux": true
      }
    }
  ]
}
```

