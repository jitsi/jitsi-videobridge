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

Note that in order to handle portrait-mode video correctly,
despite the name `maxHeight`, this parameter is actually used to specify the smaller
of the dimensions of the video (which for portrait mode will be its width).
