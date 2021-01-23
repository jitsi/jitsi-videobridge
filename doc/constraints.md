## Video Constraints

###### Sender video constraints

The bridge sends the following message to a sender to notify it that resolutions
higher than the specified need not be transmitted:
```
{
  "colibriClass": "SenderVideoConstraints",
  "videoConstraints": {
    "idealHeight": 180
  }
}
```

