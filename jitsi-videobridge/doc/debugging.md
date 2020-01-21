# Introduction

This document describes some methods and techniques that can be used to debug
the JVB.

# Capturing packets

The JVB can be configured to log all (or just some) of the incoming/outgoing
packets that it receives/sends. In order to capture all RTP/RTCP traffic, you
can setup logging like this:

    net.java.sip.communicator.packetlogging.PACKET_LOGGING_ENABLED=true
    net.java.sip.communicator.packetlogging.PACKET_LOGGING_ARBITRARY_ENABLED=true
    net.java.sip.communicator.packetlogging.PACKET_LOGGING_SIP_ENABLED=false
    net.java.sip.communicator.packetlogging.PACKET_LOGGING_JABBER_ENABLED=false
    net.java.sip.communicator.packetlogging.PACKET_LOGGING_RTP_ENABLED=false
    net.java.sip.communicator.packetlogging.PACKET_LOGGING_ICE4j_ENABLED=false
    net.java.sip.communicator.packetlogging.PACKET_LOGGING_FILE_COUNT=1
    net.java.sip.communicator.packetlogging.PACKET_LOGGING_FILE_SIZE=-1

Optionally, you can log the packets to a named pipe like this:

    mkfifo ~/.sip-communicator/log/jitsi0.pcap

If you log to a named pipe, you can launch Wireshark like this (assuming that
you're using Bash):

    wireshark -k -i < (cat ~/.sip-communicator/log/jitsi0.pcap)

# Scripting the bridge

You can script the bridge using Groovy. This functionality is not baked in the
vanilla bridge for security reasons, so you have to download and compile and
configure the shell yourself. Here
https://github.com/jitsi/jitsi-videobridge-groovysh you can find more
information on how to do that.
