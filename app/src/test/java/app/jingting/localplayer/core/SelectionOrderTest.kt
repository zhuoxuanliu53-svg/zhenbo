package app.jingting.localplayer.core

import org.junit.Assert.*
import org.junit.Test

class SelectionOrderTest {
    @Test fun clickingOrderSurvivesDisplayOrderAndReselect() {
        var selected = emptyList<String>()
        listOf("c", "a", "b").forEach { selected = SelectionOrder.toggle(selected, it) }
        assertEquals(listOf("c", "a", "b"), selected)
        selected = SelectionOrder.toggle(selected, "a")
        assertEquals(listOf("c", "b"), selected)
        assertEquals(listOf("c", "b", "a"), SelectionOrder.toggle(selected, "a"))
    }
    @Test fun selectAllAppendsOnlyMissingItems() {
        assertEquals(listOf("b", "a", "c"), SelectionOrder.selectAll(listOf("b"), listOf("a", "b", "c")))
    }
    @Test fun applyingOrderPreservesUnselectedEntries() {
        assertEquals(listOf("d", "b", "a", "c"), SelectionOrder.moveFirst(listOf("a", "b", "c", "d"), listOf("d", "b", "gone")))
    }
}
