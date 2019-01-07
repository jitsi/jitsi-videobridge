package org.jitsi.nlj

interface AudioLevelListener {
    fun onLevelReceived(sourceSsrc: Long, level: Long)
}