package com.disastersafety.app

import org.junit.Assert.*
import org.junit.Test

class MeshParticipantDirectoryTest {

    @Test
    fun directAndMultiHopDiscoveryOrdersAndDeduplicates() {
        val dir = MeshParticipantDirectory(staleTimeoutMs = 45_000L, expireTimeoutMs = 180_000L)
        val now = 1_000_000L

        // Direct neighbor B (1 hop)
        dir.upsertParticipant("NODE-B", "Phone B", "pubB", "fpB", "NODE-B", 1, "ble", now)
        // Remote node E via B (4 hops)
        dir.upsertParticipant("NODE-E", "Phone E", "pubE", "fpE", "NODE-B", 4, "ble", now)
        // Remote node C via B (2 hops)
        dir.upsertParticipant("NODE-C", "Phone C", "pubC", "fpC", "NODE-B", 2, "ble", now)
        // Duplicate announcement for C with worse hop count (3 hops) should not degrade route
        dir.upsertParticipant("NODE-C", "Phone C", "pubC", "fpC", "NODE-X", 3, "ble", now + 1000)

        val list = dir.listParticipants()
        assertEquals(3, list.size)
        assertEquals("NODE-B", list[0].deviceId)
        assertEquals(MeshParticipantDirectory.STATUS_DIRECT, list[0].status)
        assertEquals(1, list[0].hopCount)

        assertEquals("NODE-C", list[1].deviceId)
        assertEquals(MeshParticipantDirectory.STATUS_MESH, list[1].status)
        assertEquals(2, list[1].hopCount)
        assertEquals("NODE-B", list[1].nextHopDeviceId)

        assertEquals("NODE-E", list[2].deviceId)
        assertEquals(4, list[2].hopCount)
    }

    @Test
    fun shorterRouteReplacesLongerRoute() {
        val dir = MeshParticipantDirectory()
        val now = 1_000_000L

        dir.upsertParticipant("NODE-D", "Phone D", "pubD", "fpD", "NODE-B", 4, "ble", now)
        assertEquals(4, dir.getActiveRoute("NODE-D", now)?.hopCount)

        // Shorter route via Wi-Fi Direct neighbor F (2 hops)
        dir.upsertParticipant("NODE-D", "Phone D", "pubD", "fpD", "NODE-F", 2, "wifi_direct", now + 500)
        val route = dir.getActiveRoute("NODE-D", now + 500)
        assertNotNull(route)
        assertEquals(2, route?.hopCount)
        assertEquals("NODE-F", route?.nextHopDeviceId)
        assertEquals("wifi_direct", route?.transport)
    }

    @Test
    fun disconnectionAndExpirationMarkUnreachableAndPrune() {
        val dir = MeshParticipantDirectory(staleTimeoutMs = 10_000L, expireTimeoutMs = 30_000L)
        val now = 1_000_000L

        dir.upsertParticipant("NODE-B", "Phone B", "pubB", "fpB", "NODE-B", 1, "ble", now)
        dir.upsertParticipant("NODE-C", "Phone C", "pubC", "fpC", "NODE-B", 2, "ble", now)

        // When B disconnects, both B and C (routed via B) must become unreachable
        assertTrue(dir.markNeighborDisconnected("NODE-B", now + 1000))
        assertNull(dir.getActiveRoute("NODE-B", now + 1000))
        assertNull(dir.getActiveRoute("NODE-C", now + 1000))
        assertEquals(MeshParticipantDirectory.STATUS_UNREACHABLE, dir.getParticipant("NODE-C")?.status)

        // After expireTimeoutMs, participants are pruned
        assertTrue(dir.pruneAndRefresh(emptySet(), now + 35_000L))
        assertEquals(0, dir.listParticipants().size)
    }

    @Test
    fun staleParticipantsBecomeUnreachableWhenNoRefreshArrives() {
        val dir = MeshParticipantDirectory(staleTimeoutMs = 10_000L, expireTimeoutMs = 30_000L)
        val now = 1_000_000L

        dir.upsertParticipant("NODE-C", "Phone C", "pubC", "fpC", "NODE-B", 2, "ble", now)
        assertNotNull(dir.getActiveRoute("NODE-C", now + 5_000L))

        // After staleTimeoutMs, pruneAndRefresh marks C unreachable even if B is still connected
        assertTrue(dir.pruneAndRefresh(setOf("NODE-B"), now + 12_000L))
        assertEquals(MeshParticipantDirectory.STATUS_UNREACHABLE, dir.getParticipant("NODE-C")?.status)
        assertNull(dir.getActiveRoute("NODE-C", now + 12_000L))
    }
}
