package com.codecapture

import com.codecapture.bluetooth.HidReportDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportDescriptorTest {

    @Test
    fun mouseWheelReportClampsToSignedByteBounds() {
        val down = HidReportDescriptor.buildMouseWheelReport(-200)
        assertEquals(4, down.size)
        assertEquals((-127).toByte(), down[3])

        val up = HidReportDescriptor.buildMouseWheelReport(200)
        assertEquals(127.toByte(), up[3])
    }

    @Test
    fun keyboardReportHasEightBytesWithKeycodeInSlotTwo() {
        val press = HidReportDescriptor.buildKeyboardReport(0x00, HidReportDescriptor.KEY_PAGE_DOWN)
        assertEquals(8, press.size)
        assertEquals(HidReportDescriptor.KEY_PAGE_DOWN, press[2])

        val release = HidReportDescriptor.buildKeyboardReport()
        assertTrue(release.all { it == 0.toByte() })
    }

    @Test
    fun compositeDescriptorContainsBothReportIds() {
        val desc = HidReportDescriptor.COMPOSITE_KEYBOARD_MOUSE_DESCRIPTOR
        assertTrue(desc.isNotEmpty())
        // 0x85 0x01 (Report ID 1) and 0x85 0x02 (Report ID 2)
        val hasKeyboardId = (0 until desc.size - 1).any {
            desc[it] == 0x85.toByte() && desc[it + 1] == HidReportDescriptor.REPORT_ID_KEYBOARD.toByte()
        }
        val hasMouseId = (0 until desc.size - 1).any {
            desc[it] == 0x85.toByte() && desc[it + 1] == HidReportDescriptor.REPORT_ID_MOUSE.toByte()
        }
        assertTrue(hasKeyboardId)
        assertTrue(hasMouseId)
    }
}
