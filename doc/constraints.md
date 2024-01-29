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
