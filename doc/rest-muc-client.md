The configuration for the XMPP client connections that jitsi-videobridge uses can be modified at run time using REST calls to `/colibri/muc-client/`.

# Adding an XMPP client connection
A new XMPP client connection (i.e. a MucClient) can be added by posting a JSON which contains its configuration to `/colibri/muc-client/add`:
```
{
  "id": "new-client-connection",
  "domain":"xmpp.example.com",
  "hostname":"10.0.0.1",
  "username":"xmpp-username",
  "password":"xmpp-password",
  "muc_jids":"JvbBrewery@conference.xmpp.example.com",
  "muc_nickname":"unique-resource",
  "disable_certificate_verification":"false"
}
```

If a configuration with the specified ID already exists the behavior depends on whether the `hostname`, `port`,
`domain` and `username` fields match the existing client. If they match no changes are made, otherwise the existing
`MucClient` is removed and a new one is added.

# Removing an XMPP client connection
An XMPP client connection (i.e. a MucClient) can be removed by posting a JSON which contains its ID to `/colibri/muc-client/remove`:
```
{
  "id": "new-client-connection"
}
```

The request will be successful (return 200) if an XMPP client connection was removed. 


# Listing XMPP client connections
IDs of previously added XMPP client connections can be listed using a GET call to `/colibri/muc-client/list`.
