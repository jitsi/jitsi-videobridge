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


The only modification made by Jitsi Videobridge before the message is forwarded is adding `from` property to it. The value of this property is the endpoint id of the sender of the message.
