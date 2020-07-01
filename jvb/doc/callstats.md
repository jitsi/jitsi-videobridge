To enable callstats on JVB, the following properties needs to be set 
in /etc/jitsi/videobridge/sip-communicator.properties

    # the callstats credentials
    io.callstats.sdk.CallStats.appId=
    io.callstats.sdk.CallStats.keyId=
    io.callstats.sdk.CallStats.keyPath=
    #io.callstats.sdk.CallStats.appSecret=

    # the id of the videobridge
    io.callstats.sdk.CallStats.bridgeId=
    io.callstats.sdk.CallStats.conferenceIDPrefix=

    # enable statistics and callstats statistics and the report interval
    org.jitsi.videobridge.ENABLE_STATISTICS=true
    org.jitsi.videobridge.STATISTICS_INTERVAL.callstats.io=30000
    org.jitsi.videobridge.STATISTICS_TRANSPORT=callstats.io

callstats.io supports authentication via shared secret and public/private keys.

You can use [pem-to-jwk](https://www.npmjs.com/package/pem-to-jwk) to convert PEM encoded EC private key to JWK.  
To generate a jwk file that needs to be supplied as
io.callstats.sdk.CallStats.keyPath parameter, follow these steps:
    
    # Generate EC key
    openssl ecparam -name prime256v1 -genkey > ecpriv.key
    cat ecpriv.key | pem-to-jwk > ecpriv.jwk
    
    # Generate a public key from private key
    openssl ec -in ecpriv.key -pubout -out ecpubkey.pem
