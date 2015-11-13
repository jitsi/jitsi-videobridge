# Introduction

This document describes the Jitsi Videobridge (JVB) pipeline. In the JVB we
have the concept of `RtpChannel` and each `RtpChannel` is associated to a
`MediaStream`. For the purposes of this discussion we can think of an
`RtpChannel` and a `MediaStream` as one and the same thing, although this is
not technically true, as a `MediaStream` can be used without an `RtpChannel`
but the opposite is not possible. Also, because the JVB pipeline is based on
the Libjitsi pipeline it will be described here as well.

# Channel Pipeline

Packets that go in and out of the `MediaStream` they go through the _channel
pipeline_ that consists of a series of `TransformEngine`s. In the following
diagram we give an combined overview of all the `TransformEngine` that make
the channel pipeline.

The parenthesis lists the `TransformEngine`s that are implemented in the JVB
but there is discussion to move some of them in Libjitsi.

```
JVB <=> CSRC <=> (SIM <=> DE-RED <=> RTX) <=> PT/RED/FEC <=> SSRC-RWR <=>
    <=> RTCP-TERM <=> STATS+NACK+REMB <=> RTX-REQ <=> ABS-TIME <=>
    <=> PKT-CACHE <=> DEBUG <=> SRTP <=> ENDPOINT
```

# Ordering rules

- CSRC
- SIM
- RE-RED
- RTX
- PT/RED/FEC
- SSRC-RWR
- RTCP-TERM
- STATS+NACK+REMB
- RTX-REQ
- ABS-TIME
- PKT-CACHE
- DEBUG
- SRTP

