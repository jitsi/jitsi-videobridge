# CLAUDE.md (export)

Notes for the audio export code (`Exporter`, `ExporterWrapper`).

## Audio export over mediajson

- The bridge streams conference audio to an external peer (a recorder, transcriber, translator, or voice agent) over a WebSocket. It uses the mediajson protocol of `jicoco-mediajson`.
- A colibri2 `Connect` (`org.jitsi.xmpp.extensions.colibri2.Connect`, from `jitsi-xmpp-extensions`) requests the export. Its fields are:
  - `type` (`Connect.Types`, for example `RECORDER` or `AGENT`), `protocol`, `url`, HTTP headers, and ping settings.
  - The `exports` and `requests` lists of source names.
  - `audio`, `video`, `create`, and `expire`.
- `ExporterWrapper.validate()` rejects `video=true` and any protocol other than `MEDIAJSON`. `MEDIAJSON` is the only value of `Protocols`, and the provider rejects unknown protocol strings when it parses the IQ.
- Each `Conference` has one `ExporterWrapper`. It keeps the running `Exporter`s in a map keyed by the `Connect` id. `applyConnects()` takes a delta in which each connect is `expire`, `create`, or an update. It validates the whole delta before it changes anything.
  - Expires are applied before creates. One delta can expire and re-create the same id to replace a connect.
  - An update can change only `exports` and `requests`. An update with unchanged parameters is a no-op.
- Each `Exporter` is a `PotentialPacketHandler` on the audio send path of the conference. `wants()` maps the SSRC to a signaled source name and checks that name against `exports`. An empty `exports` list exports all audio only when `requests` is also empty. A connect with `requests` (a translator or an agent) exports only the sources in `exports`.
- `Exporter` opens one WebSocket for each connect. It sends these events:
  - Audio as mediajson `start` and `media` events, only for exported sources. A new `start` follows when the payload type of an SSRC changes. `start` carries `customParameters.endpointId` and `diarize`. `media` carries the audio level and the VAD flag.
  - A `SourcesEvent` with `exports` and `requests`. On connect, the bridge sends it only when `exports` or `requests` is not empty. On update, it always sends it.
  - A `SpeakersEvent`, for `AGENT` connects only.
  - An `InfoEvent` with the application name, version, and region, right after connect.
  - Ping events, if configured.
  - A `SessionEndEvent` on stop.
- `Exporter` receives these events:
  - `TranscriptionResultEvent`. The conference broadcasts it to the endpoints.
  - `MediaEvent`: injected audio, see below.
  - `start` and `stop` with a timestamp. They are sending-state changes of a synthetic source. A `start` or `stop` without a timestamp, and any other event, is only counted as an "other message".
  - `PongEvent`. A pong with an id that does not match the last ping is logged and ignored.
  - `InfoEvent`. It is only logged.
  - The bridge logs a message that it cannot parse at warn level and counts it.
- The conference handles the received events through the `ExporterEventHandler` that it gives to `ExporterWrapper`.
- `handleMediaEvent()` in the `ExporterEventHandler` of `Conference.java` injects received audio. The `tag` of the `MediaEvent` names a synthetic audio source, which must already be known to the conference.
  - The bridge drops media for an unknown source. It logs a warning once for each tag. Media for a recently removed synthetic source is dropped at debug level.
  - Injection needs an Opus payload type that was negotiated in the conference. If there is none, the bridge drops the packet and logs a warning once.
  - The bridge puts the base64 Opus payload in a new `AudioRtpPacket` on the SSRC of the source. The sequence number and the RTP timestamp come from `SyntheticSourceRtpContinuity`. They are the `chunk` and the timestamp of the event (the low 16 and 32 bits) until the producer starts its numbering again, which it does on every new connection. After that the sequence numbers continue from the last one sent, and the timestamps continue by the time that passed. Without this the source keeps its SSRC but goes back to sequence numbers that receivers already got, and their SRTP replay protection drops the packets.
  - The exporter reports a new connection of a producer through `handleProducerConnected()`, with the sources it requests. Numbers that go back are also taken as a new start, for a producer that starts again on the same connection.
  - The mediajson timestamp is not wrapped, and `RtpPacket` caches the raw long, so the timestamp is wrapped to 32 bits before it is set. The timestamp of a sending change goes through the same `SyntheticSourceRtpContinuity`, so it stays on the timeline of the injected media.
  - Then the bridge passes the packet to `handleIncomingPacket()`.
- Only audio subscriptions decide who gets synthetic audio, including its owner. `Conference.sendOut()` skips the self-echo check for a synthetic source (`isSyntheticSource()`), also for synthetic audio that arrives over a relay with the ID of its owner. `sendOut()` also never offers a synthetic source to the exporters. The injected packet has no endpoint ID. Only endpoints with an explicit (`Include`) audio subscription to the source get the audio. The owner of the source can be one of them: for a translation source it is the translated endpoint, and for an `AGENT` source it is a synthetic endpoint.
- `debugState()` of `ExporterWrapper` and `Exporter` returns a Jackson `ObjectNode`. It shows each connect, tagged with its id, with `url`, `type`, `exports`, `requests` and connection counters. The debug state of the conference includes it under `exporter`, unless the debug mode is `SHORT`.
