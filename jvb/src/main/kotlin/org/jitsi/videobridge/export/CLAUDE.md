# CLAUDE.md (export)

Notes for the audio export code (`Exporter`, `ExporterWrapper`).

## Audio export over mediajson

- The bridge streams conference audio to an external peer (a recorder, transcriber, translator or voice agent) over a WebSocket, with the mediajson protocol of `jicoco-mediajson`. A colibri2 `Connect` (`org.jitsi.xmpp.extensions.colibri2.Connect`, from `jitsi-xmpp-extensions`) requests the export. Its fields are `type` (`Connect.Types`, for example `RECORDER` or `AGENT`), `protocol`, `url`, HTTP headers, ping settings, and the `exports` and `requests` lists of source names. Only `MEDIAJSON` is implemented, and `ExporterWrapper` rejects other protocols.
- Each `Conference` has one `ExporterWrapper`. It keeps the running `Exporter`s in a map keyed by the `Connect` id. `applyConnects()` takes a delta in which each connect is `expire`, `create`, or an update. It validates the whole delta before it changes anything, and an update can change only `exports` and `requests`.
- Each `Exporter` is a `PotentialPacketHandler` on the audio send path of the conference. `wants()` maps the SSRC to a signaled source name and checks that name against `exports`. An empty `exports` list exports all audio only when `requests` is also empty. A connect with `requests` (a translator or an agent) exports only the sources in `exports`.
- `Exporter` opens one WebSocket for each connect.
  - It sends: audio as mediajson `start` and `media` events (only for exported sources); a `SourcesEvent` with `exports` and `requests`, on connect and on update; a `SpeakersEvent` (`AGENT` connects only); an `InfoEvent` with the application name, version and region, right after connect; ping events, if configured; and a `SessionEndEvent` on stop.
  - It receives: `TranscriptionResultEvent` (the conference broadcasts it to the endpoints); `MediaEvent` (injected audio, see below); `start` and `stop` with a timestamp (sending-state changes of a synthetic source); `PongEvent`; and `InfoEvent` (logged only).
  - The conference handles received events through the `ExporterEventHandler` that it gives to `ExporterWrapper`.
- `handleMediaEvent()` in the `ExporterEventHandler` of `Conference.java` injects received audio. The `tag` of the `MediaEvent` names a synthetic audio source, which must already be known to the conference. The bridge drops media for an unknown source and logs a warning. It puts the base64 Opus payload in a new `AudioRtpPacket` on the SSRC of the source, with the sequence number (`chunk`) and the timestamp from the event. Then it passes the packet to `handleIncomingPacket()`.
- The injected packet has no endpoint ID, so self-echo suppression does not apply. Only endpoints with an explicit (`Include`) audio subscription to the source get it, and the original speaker can be one of them. The bridge never exports synthetic audio.
- `debugState()` of `ExporterWrapper` and `Exporter` returns a Jackson `ObjectNode`. It shows each connect, tagged with its id, with `url`, `type`, `exports`, `requests` and connection counters. The debug state of the conference includes it under `exporter`.
