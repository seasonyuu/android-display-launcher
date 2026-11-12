package me.seasonyuu.displaylauncher

import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayMappingTest {
    private val outputId = "11529215050297162657"
    private fun logical(id: Int = 90000, stack: Int = 90000, name: String = "vivo_car_screen_3", unique: String = "virtual:com.vivo.carlauncher:carlauncher_car") =
        "mBaseDisplayInfo=DisplayInfo{\"$name\", displayId $id, type VIRTUAL, uniqueId \"$unique\", density 414, layerStack $stack, colorMode 0}"
    private fun output(id: String = outputId, stack: Int = 90000, name: String = "vivo_car_screen_3") = """
        Display $id (virtual, "$name")
           Composition Display State:
           isEnabled=true isSecure=true usesDeviceComposition=false
           layerFilter={layerStack=$stack toInternalDisplay=false skipScreenshot=false }
           layerStackSpace=ProjectionSpace{bounds=Rect(0, 0, 1048, 1147)}
    """.trimIndent()
    private fun listed(id: String = outputId, name: String = "vivo_car_screen_3") = "Display $id (Virtual display): displayName=\"$name\""
    @Test fun mapsExplicitLayerStackWithUnsignedId() {
        assertEquals(outputId, ShellProtocol.virtualId(logical(), 90000, output(), listed()))
        assertNull(ShellProtocol.virtualId(logical(), 9000, output(), listed()))
        assertEquals(outputId, ShellProtocol.virtualId(logical(id = 8), 8, output(), listed()))
    }
    @Test fun neverGuessesFromNameOrDimensions() {
        assertNull(ShellProtocol.virtualId(logical(), 90000, output(stack = 90002), listed()))
        assertNull(ShellProtocol.virtualId(logical(), 90000, output(name = "other"), listed(name = "other")))
        assertNull(ShellProtocol.virtualId(logical(), 90000, "", listed()))
    }
    @Test fun refusesAmbiguousOutputsAndMirroredLogicalStacks() {
        val duplicate = output() + "\n" + output(id = "11529215050297162658")
        assertNull(ShellProtocol.virtualId(logical(), 90000, duplicate, listed()))
        assertNull(ShellProtocol.virtualId(logical() + "\n" + logical(id = 90004), 90000, output(), listed()))
        assertNull(ShellProtocol.virtualId(logical() + "\n" + logical(unique = "virtual:changed"), 90000, output(), listed()))
    }
    @Test fun refusesStaleDisabledAndExcludedOutputs() {
        assertNull(ShellProtocol.virtualId(logical(), 90000, output(), listed(id = "123")))
        assertNull(ShellProtocol.virtualId(logical(), 90000, output().replace("isEnabled=true", "isEnabled=false"), listed()))
        assertNull(ShellProtocol.virtualId(logical(), 90000, output().replace("skipScreenshot=false", "skipScreenshot=true"), listed()))
        assertNull(ShellProtocol.virtualId(logical(), 90000, output().replace("toInternalDisplay=false", "toInternalDisplay=true"), listed()))
    }
    @Test fun doesNotReadLayerFilterFromFollowingSections() {
        val dump = "Display $outputId (virtual, \"vivo_car_screen_3\")\nOther section:\n   isEnabled=true\n   layerFilter={layerStack=90000 toInternalDisplay=false skipScreenshot=false }"
        assertNull(ShellProtocol.virtualId(logical(), 90000, dump, listed()))
        assertNull(ShellProtocol.virtualId(logical(), 90000, output().replace("layerFilter=", "unknownFilter="), listed()))
    }
    @Test fun rejectsOutOfRangeIdsAndDetectsRecreatedIdentity() {
        assertNull(ShellProtocol.virtualId(logical(), 90000, output(id = "18446744073709551616"), listed(id = "18446744073709551616")))
        assertNotEquals(ShellProtocol.displayIdentity(logical(), 90000), ShellProtocol.displayIdentity(logical(unique = "virtual:new"), 90000))
    }
}
