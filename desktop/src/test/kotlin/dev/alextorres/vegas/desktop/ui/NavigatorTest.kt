package dev.alextorres.vegas.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    @Test
    fun openPushesAndBackPops() {
        val nav = Navigator()
        nav.open(Screen.Album("MPREb_1"))
        nav.open(Screen.Artist("UC1"))
        assertEquals(Screen.Artist("UC1"), nav.current.value)
        assertTrue(nav.back())
        assertEquals(Screen.Album("MPREb_1"), nav.current.value)
    }

    @Test
    fun selectingRailDestinationResetsStack() {
        val nav = Navigator()
        nav.open(Screen.Album("MPREb_1"))
        nav.select(Destination.LIBRARY)
        assertEquals(Screen.Library, nav.current.value)
        assertFalse(nav.back())
    }

    @Test
    fun backOnRootReturnsFalse() {
        val nav = Navigator()
        assertEquals(Screen.Home, nav.current.value)
        assertFalse(nav.back())
    }

    @Test
    fun destinationFollowsTheStackRoot() {
        val nav = Navigator()
        nav.select(Destination.SEARCH)
        nav.open(Screen.Album("MPREb_1"))
        assertEquals(Destination.SEARCH, nav.destination.value)
    }
}
