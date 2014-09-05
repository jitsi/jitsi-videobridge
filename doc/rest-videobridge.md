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
			200 OK with a JSON array/list of JSON objects which represent conferences with id only.<br /> 
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
			200 OK with a JSON object which represents the created conference if the request was with Content-Type: application/json and was a JSON object which represented a conference without id and, optionally, with contents and channels without ids. <br />
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
		<td>/colibri/conferences{id}</td>
		<td>
			200 OK with a JSON object which represents the conference with the specified id. <br />
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
		<td>/colibri/conferences{id}</td>
		<td>
			200 OK with a JSON object which represents the modified conference if the request was with ```Content-Type: application/json``` and was a JSON object which represented a conference without ```id``` or with the specified ```id``` and, optionally, with contents and channels with or without ```id```s.
		</td>
	</tr>
	<tr>
		<td>GET</td>
		<td>/colibri/stats</td>
		<td>
		200 OK with a JSON object which represents the statistics report.
<pre>
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
}</pre>
		</td>
	</tr>
</table>

Configuration
==============
**To enable the REST API you have to start Jitsi video bridge with parameter --apis=rest.**

**The following configuration properties can be added in the Jitsi Videobridge configuration file(HOME/.sip-communicator/sip-communicator.properties):**

 * **org.jitsi.videobridge.rest.jetty.port** - 
 Specifies the port on which the REST API of Videobridge is to be served over HTTP. The default value is 8080.
 * **org.jitsi.videobridge.rest.jetty.sslContextFactory.keyStorePassword** - 
 Specifies the keystore password to be utilized when the REST API of Videobridge is served over HTTPS.
 * **org.jitsi.videobridge.rest.jetty.sslContextFactory.keyStorePath** - 
 Specifies the keystore path to be utilized when the REST API of Videobridge is served over HTTPS.
 * **org.jitsi.videobridge.rest.jetty.sslContextFactory.needClientAuth** - 
 Specifies whether client certificate authentication is to be required when the REST API of Videobridge is served over HTTPS.
 * **org.jitsi.videobridge.rest.jetty.tls.port** - 
 Specifies the port on which the REST API of Videobridge is to be served over HTTPS. The default value is 8443.

