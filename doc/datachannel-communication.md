# Messages by COLIBRI class:

## EndpointMessage:


This type of messages could be used by the clients for sending JSON objects to the other clients in the conference. A client can send a message to specific endpoint or a broadcast message.



* The messages processed by Jitsi Videobridge has the following format:

```
{
    colibriClass: "EndpointMessage",  
    to: `<ENDPOINT-ID>`,   
    msgPayload: `<PAYLOAD>`  
}
```

`<PAYLOAD>` is valid JSON string.  
`<ENDPOINT-ID>` is endpoint id of existing participant or `""` for broadcast message.

* The messages sent by Jitsi Videobridge has the following format:


```
{
    colibriClass: "EndpointMessage",
    to: `<ENDPOINT-ID>`,
    from: `<ENDPOINT-ID>`,
    msgPayload: <PAYLOAD>
}
```


The only modification made by Jitsi Videobridge before the message is forwarded is setting the `from` property to the endpoint id of the sender of the message, overwriting any `from` the sender included.

Jitsi Videobridge does not forward messages from visitor endpoints. The endpoint id `transcriber` is reserved: Jitsi Videobridge does not create an endpoint with it (a request to create one is rejected with `bad-request`), and does not forward messages from an endpoint with it.

Jitsi Videobridge itself also sends `EndpointMessage`s with `from: "transcriber"`. They carry the transcription results Jitsi Videobridge receives from a transcriber, in `msgPayload`, and are sent to all endpoints in the conference.
