package me.seasonyuu.displaylauncher

import org.junit.Assert.*
import org.junit.Test

class ShellProtocolTest {
    private fun display(logical: Int, physical: String) = "mBaseDisplayInfo=DisplayInfo{\"Screen\", displayId $logical, real 1080 x 1920, uniqueId \"local:$physical\", density 320}"
    @Test fun mapsExactLogicalIdToPhysicalId() {
        val dump = display(0, "999999999999999") + "\n" + display(3, "888888888888888")
        val sf = "Display 999999999999999 (HWC display 0):\nDisplay 888888888888888 (HWC display 1):"
        assertEquals("888888888888888", ShellProtocol.physicalId(dump, 3, sf))
        assertNull(ShellProtocol.physicalId(dump, 1, sf))
    }
    @Test fun refusesAmbiguousAndMissingMappings() {
        val sf = "Display 99 (HWC display 0):\nDisplay 88 (HWC display 1):"
        assertNull(ShellProtocol.physicalId(display(1, "99") + "\n" + display(1, "88"), 1, sf))
        assertNull(ShellProtocol.physicalId(display(1, "77"), 1, sf))
        assertNull(ShellProtocol.physicalId("displayId 1 uniqueId virtual:abc", 1, sf))
    }
    @Test fun doesNotConfuseDisplayOneWithTen() {
        assertNull(ShellProtocol.physicalId(display(10, "99"), 1, "Display 99 (HWC display 0):"))
    }
    @Test fun commandUsesArgumentsAndStandardTaskFlags() {
        val args = ShellProtocol.launchArgs("me.seasonyuu.fixture/.Main", 10, 3)
        assertEquals("3", args[args.indexOf("--display") + 1])
        assertEquals("10", args[args.indexOf("--user") + 1])
        assertEquals("0x10200000", args.last())
        assertFalse(args.contains("-S"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInjectedComponent() {
        ShellProtocol.launchArgs("me.seasonyuu.fixture/.Main; reboot", 0, 0)
    }
    @Test fun zeroExitCodeDoesNotImplySuccess() {
        assertFalse(ShellProtocol.launchAccepted(0, "Error: Activity not found"))
        assertFalse(ShellProtocol.launchAccepted(0, "Status: timeout"))
        assertTrue(ShellProtocol.launchAccepted(0, "Warning: Activity not started, task brought to front\nStatus: ok\n"))
        assertFalse(ShellProtocol.launchAccepted(1, "Status: ok"))
    }
    @Test fun onlyConfirmsUniquelyResumedExactComponentAndUser() {
        val dump = "Display #3 (activities from top to bottom):\n topResumedActivity=ActivityRecord{abc u0 me.seasonyuu.fixture/.Main t3}\n"
        assertEquals(3, ShellProtocol.actualDisplay(dump, "me.seasonyuu.fixture/me.seasonyuu.fixture.Main", 0))
        assertNull(ShellProtocol.actualDisplay(dump, "me.seasonyuu.fixture/.Trampoline", 0))
        assertNull(ShellProtocol.actualDisplay(dump, "me.seasonyuu.fixture/.Main", 10))
        assertNull(ShellProtocol.actualDisplay(dump.replace("topResumedActivity", "mLastPausedActivity"), "me.seasonyuu.fixture/.Main", 0))
        assertNull(ShellProtocol.actualDisplay(dump + dump.replace("#3", "#4"), "me.seasonyuu.fixture/.Main", 0))
    }
    @Test fun ignoresGlobalResumedSummaryAfterAnotherDisplay() {
        val dump = """
            Display #9 (activities from top to bottom):
                topResumedActivity=ActivityRecord{abc u0 me.seasonyuu.fixture/.Main t3}
            Display #0 (activities from top to bottom):
              ResumedActivity: ActivityRecord{abc u0 me.seasonyuu.fixture/.Main t3}
        """.trimIndent()
        assertEquals(9, ShellProtocol.actualDisplay(dump, "me.seasonyuu.fixture/.Main", 0))
    }

}
