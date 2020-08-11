# Introduction

This document describes some HTTP endpoints that can be useful when debugging jitsi-videobridge. With a few exceptions, 
these are NOT intended to be used against production systems. Most endpoints return valid JSON which can be fed to e.g. `jq`:
```
curl http://localhost:8080/debug | jq .
```

# Basic queries

### Health
Returns `200 OK` if the instance is currently healthy, and `4XX/5XX` otherwise.
```
GET /about/health
```

### Version
Returns the currently running version.
```
GET /about/version
```

### List conferences
Returns a list of running conferences.
```
GET /colibri/conferences
```

### Describe a conference
Returns the COLIBRI description of a conference.
```
GET /colibri/conferences/<CONFERENCE_ID>
```

### Overall statistics 
Returns overall bridge statistics. These include number of current and total conferences and endpoints, number of packets/bytes received/sent, current packet rate and bit rate. They are generally suitable for storing as timeseries in order to monitor a service.
```
GET /colibri/stats
```


# Detailed debug state
The following can be used to get very detailed state of running conferences and endpoints.

### List conferences
This includes a list of all conferences and their endpoints, but not the full state.
```
GET /debug
```

### List all conferences with full state
This includes the full state of all conferences and their endpoints. The output can be substantial (~25KB per endpoint).
```
GET /debug?full=true
```

## List a specific conference with full state
This includes the full state of a specific conference and its endpoints. The output can be substantial (~25KB per endpoint).
The full output can be suspended (leaving just the list of endpoints) by adding `?full=false`.
```
GET /debug/CONFERENCE_ID
```

## Get full state for an endpoint
This includes the full state of a specific endpoint in a specific conference.
```
GET /debug/CONFERENCE_ID/ENDPOINT_ID
```


# Global debug options
These are features that affect the entire bridge (as opposed to a single endpoint or conference).

### Payload verification 
This mode enables verification of the RTP payload between each step of the packet pipeline.
This is very CPU intensive and disabled by default (it is only useful for testing new code).

Enable:
```
POST /debug/enable/payload-verification
```

Disable:
```
POST /debug/disable/payload-verification
```

Query:
```
GET /debug/stats/payload-verification
```

### Pipeline statistics 
This keeps track of processing delay, number of discarded packets,
number of packets by various types, etc. from each Node in the packet
processing pipeline. The data is organized by Node type. This has low overhead
and is enabled by default. Note that the data does not update in real-time, but
only when endpoints expire.

Enable:
```
POST /debug/enable/node-stats
```

Disable:
```
POST /debug/disable/node-stats
```
Query:
```
GET /debug/stats/node-stats
```

### Memory pool statistics
These include current size, number of requests, allocation rates, etc. for the application memory pools. This is disabled by default.

Enable:
```
POST /debug/enable/pool-stats
```

Disable:
```
POST /debug/disable/pool-stats
```
Query:
```
GET /debug/stats/pool-stats
```

### Packet queue statistics
This keeps track of the number of dropped packets and exception caught in the
various packet queues. It is enabled by default.

Enable:
```
POST /debug/enable/queue-stats
```

Disable:
```
POST /debug/disable/queue-stats
```

Query:
```
GET /debug/stats/queue-stats
```

### Packet transit time statistics
This keeps track of the overall transit time for RTP/RTCP packets (average,
max, and a distribution), as well as the jitter introduced in processing. It
is always enabled.

Query:
```
GET /debug/stats/transit-stats
```

### Task pool stats
This keeps track of statistics for the various task pools (CPU, IO, scheduled).
It is always enabled.

Query:
```
GET /debug/stats/task-pool-stats
```

### XMPP Delay stats
This keeps track of the response time for requests received over XMPP.

Query:
```
GET /debug/stats/xmpp-delay-stats
```

### Node tracing
This adds an entry to the stack trace from each Node in the packet processing pipeline. 

Enable:
```
POST /debug/enable/node-tracing
```

Disable:
```
POST /debug/disable/node-tracing
```

# Endpoint-specific debug options
These are features that can be enabled for a specific endpoint in a specific conference. 

### Saving RTP/RTCP in PCAP
This enables saving the RTP and RTCP traffic sent/received from a specific endpoint to a PCAP file in `/tmp/`.
The use of this feature is disabled by default, and the bridge needs to be explicitly configured to
allow it by setting `jmt.debug.pcap.enabled=true` in `/etc/jitsi/videobridge/jvb.conf`.

Enable:
```
POST /debug/CONFERENCE_ID/ENDPOINT_ID/enable/pcap-dump
```

Disable:
```
POST /debug/CONFERENCE_ID/ENDPOINT_ID/disable/pcap-dump
```

