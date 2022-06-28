## Video Constraints

###### Sender video constraints

The bridge sends the following message to a sender to notify it that resolutions
higher than the specified need not be transmitted for a specific video source:

```
{
  "colibriClass": "SenderSourceConstraints",
  "sourceName": "endpoint1-v0",
  "maxHeight": 180
}
```

The legacy format prior to the multi-stream support was endpoint scoped. This message is still sent to old clients, but
will be removed in the future.

```
{
  "colibriClass": "SenderVideoConstraints",
  "videoConstraints": {
    "idealHeight": 180
  }
}
```

