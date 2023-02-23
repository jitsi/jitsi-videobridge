package org.jitsi.videobridge.colibri2

import org.jivesoftware.smack.packet.StanzaError

internal class UnknownEndpointException(
    condition: StanzaError.Condition,
    message: String,
    val endpointId: String
) : IqProcessingException(condition, message)