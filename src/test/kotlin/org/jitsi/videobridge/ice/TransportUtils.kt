package org.jitsi.videobridge.ice

import org.junit.Assert.*
import org.junit.*
import org.mockito.Mockito.*

import org.ice4j.ice.Component
import org.ice4j.ice.RemoteCandidate
import org.ice4j.ice.LocalCandidate


class TransportUtilsTest {
    @Test
    fun canReachWithoutAnyReachableCandidate() {
        val component = mock(Component::class.java)
        val remoteCandidate = mock(RemoteCandidate::class.java)
        val candidate1 = mock(LocalCandidate::class.java)
        val candidate2 = mock(LocalCandidate::class.java)
        `when`(candidate1.canReach(remoteCandidate)).thenReturn(false)
        `when`(candidate2.canReach(remoteCandidate)).thenReturn(false)
        `when`(component.getLocalCandidates()).thenReturn(listOf(candidate1, candidate2))
        assertFalse(TransportUtils.canReach(component, remoteCandidate))
        verify(candidate1).canReach(remoteCandidate)
        verify(candidate2).canReach(remoteCandidate)
    }

    @Test
    fun canReachWithTheFirstCandidateReachable() {
        val component = mock(Component::class.java)
        val remoteCandidate = mock(RemoteCandidate::class.java)
        val candidate1 = mock(LocalCandidate::class.java)
        val candidate2 = mock(LocalCandidate::class.java)
        `when`(candidate1.canReach(remoteCandidate)).thenReturn(true)
        `when`(candidate2.canReach(remoteCandidate)).thenReturn(false)
        `when`(component.getLocalCandidates()).thenReturn(listOf(candidate1, candidate2))
        assertTrue(TransportUtils.canReach(component, remoteCandidate))
        verify(candidate1).canReach(remoteCandidate)
        verify(candidate2, never()).canReach(remoteCandidate)
    }

    @Test
    fun canReachWithTheSecondCandidateReachable() {
        val component = mock(Component::class.java)
        val remoteCandidate = mock(RemoteCandidate::class.java)
        val candidate1 = mock(LocalCandidate::class.java)
        val candidate2 = mock(LocalCandidate::class.java)
        `when`(candidate1.canReach(remoteCandidate)).thenReturn(false)
        `when`(candidate2.canReach(remoteCandidate)).thenReturn(true)
        `when`(component.getLocalCandidates()).thenReturn(listOf(candidate1, candidate2))
        assertTrue(TransportUtils.canReach(component, remoteCandidate))
        verify(candidate1).canReach(remoteCandidate)
        verify(candidate2).canReach(remoteCandidate)
    }
}
