package com.nekobot.app.data.local.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotGraphManagerTest {

    @Test
    fun selectChoice_keepsOnlyTheFinalChoiceSelectedWithinNode() {
        val manager = PlotGraphManager()
        val first = PlotChoice(id = "first", nodeId = "node")
        val second = PlotChoice(id = "second", nodeId = "node")
        val third = PlotChoice(id = "third", nodeId = "node")
        manager.addChoice(first)
        manager.addChoice(second)
        manager.addChoice(third)

        manager.selectChoice("first")
        manager.selectChoice("second")
        manager.selectChoice("third")

        assertFalse(first.selected)
        assertFalse(second.selected)
        assertTrue(third.selected)
    }
}
