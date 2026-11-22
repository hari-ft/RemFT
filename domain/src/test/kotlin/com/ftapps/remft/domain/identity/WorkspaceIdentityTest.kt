package com.ftapps.remft.domain.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceIdentityTest {
    private val minted = "01MINTED000000000000000000"
    private fun mint() = minted

    @Test
    fun dominantPicksMostRowsThenSmallerId() {
        assertEquals("B", WorkspaceIdentity.dominant(mapOf("A" to 2, "B" to 5)))
        assertEquals("A", WorkspaceIdentity.dominant(mapOf("B" to 3, "A" to 3)))
        assertNull(WorkspaceIdentity.dominant(emptyMap()))
    }

    @Test
    fun keepsAStoredIdThatOwnsRows() {
        val decision = WorkspaceIdentity.decide("A", true, mapOf("A" to 4), null, ::mint)
        assertEquals("A", decision.workspaceId)
        assertTrue(decision.reliable)
        assertFalse(decision.persist)
    }

    @Test
    fun keepsAStoredIdOnAnEmptyDatabase() {
        val decision = WorkspaceIdentity.decide("A", true, emptyMap(), null, ::mint)
        assertEquals("A", decision.workspaceId)
        assertTrue(decision.reliable)
    }

    @Test
    fun adoptsTheDominantOwnerWhenTheStoredIdOwnsNothing() {
        val decision = WorkspaceIdentity.decide("A", true, mapOf("B" to 1, "C" to 9), null, ::mint)
        assertEquals("C", decision.workspaceId)
        assertTrue(decision.adopted)
        assertTrue(decision.persist)
        assertFalse(decision.reliable)
    }

    @Test
    fun adoptionWithASingleOwnerIsReliable() {
        val decision = WorkspaceIdentity.decide(null, true, mapOf("B" to 1), null, ::mint)
        assertEquals("B", decision.workspaceId)
        assertTrue(decision.reliable)
    }

    @Test
    fun mintsOnlyWhenPrefsAndDatabaseWereBothReadable() {
        val first = WorkspaceIdentity.decide(null, true, emptyMap(), null, ::mint)
        assertEquals(minted, first.workspaceId)
        assertTrue(first.reliable)
        assertTrue(first.persist)

        val prefsUnreadable = WorkspaceIdentity.decide(null, false, emptyMap(), null, ::mint)
        assertFalse(prefsUnreadable.reliable)
        assertFalse(prefsUnreadable.persist)

        val dbUnreadable = WorkspaceIdentity.decide(null, true, null, null, ::mint)
        assertFalse(dbUnreadable.reliable)
        assertFalse(dbUnreadable.persist)
    }

    @Test
    fun reusesTheSessionIdWhenPrefsAreUnreadable() {
        val decision = WorkspaceIdentity.decide(null, false, emptyMap(), "S", ::mint)
        assertEquals("S", decision.workspaceId)
        assertFalse(decision.reliable)
        assertFalse(decision.persist)
    }
}
