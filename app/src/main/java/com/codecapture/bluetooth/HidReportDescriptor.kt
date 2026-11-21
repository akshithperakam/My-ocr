package com.codecapture.bluetooth

/**
 * Standard USB/Bluetooth HID composite report descriptor implementing:
 * - Report ID 1: Boot-compatible Keyboard (8-byte input: modifiers, reserved, 6 keycodes)
 * - Report ID 2: Mouse with 3 buttons, relative X/Y, and signed 8-bit vertical scroll wheel
 *
 * Works across Windows, Linux, macOS, and Bluetooth-enabled VMs without any host software.
 */
object HidReportDescriptor {
    const val REPORT_ID_KEYBOARD: Int = 1
    const val REPORT_ID_MOUSE: Int = 2

    const val KEY_PAGE_UP: Byte = 0x4B
    const val KEY_PAGE_DOWN: Byte = 0x4E
    const val KEY_DOWN_ARROW: Byte = 0x51
    const val KEY_UP_ARROW: Byte = 0x52

    val COMPOSITE_KEYBOARD_MOUSE_DESCRIPTOR: ByteArray = byteArrayOf(
        // === Keyboard Collection (Report ID 1) ===
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x85.toByte(), REPORT_ID_KEYBOARD.toByte(), // Report ID (1)

        // Modifier byte (8 bits: Ctrl, Shift, Alt, GUI)
        0x05.toByte(), 0x07.toByte(), // Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0xE0.toByte(), // Usage Minimum (224)
        0x29.toByte(), 0xE7.toByte(), // Usage Maximum (231)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x95.toByte(), 0x08.toByte(), // Report Count (8)
        0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute)

        // Reserved byte
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x81.toByte(), 0x01.toByte(), // Input (Constant)

        // LED Output report (5 bits + 3 padding)
        0x95.toByte(), 0x05.toByte(), // Report Count (5)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x05.toByte(), 0x08.toByte(), // Usage Page (LEDs)
        0x19.toByte(), 0x01.toByte(), // Usage Minimum (Num Lock)
        0x29.toByte(), 0x05.toByte(), // Usage Maximum (Kana)
        0x91.toByte(), 0x02.toByte(), // Output (Data, Variable, Absolute)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x75.toByte(), 0x03.toByte(), // Report Size (3)
        0x91.toByte(), 0x01.toByte(), // Output (Constant)

        // 6 Keycode slots
        0x95.toByte(), 0x06.toByte(), // Report Count (6)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(), // Logical Maximum (101)
        0x05.toByte(), 0x07.toByte(), // Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0x00.toByte(), // Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(), // Usage Maximum (101)
        0x81.toByte(), 0x00.toByte(), // Input (Data, Array)
        0xC0.toByte(),                // End Collection

        // === Mouse Collection with Vertical Wheel (Report ID 2) ===
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x85.toByte(), REPORT_ID_MOUSE.toByte(), // Report ID (2)
        0x09.toByte(), 0x01.toByte(), // Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(), // Collection (Physical)

        // 3 Buttons + 5 Padding bits
        0x05.toByte(), 0x09.toByte(), // Usage Page (Buttons)
        0x19.toByte(), 0x01.toByte(), // Usage Minimum (1)
        0x29.toByte(), 0x03.toByte(), // Usage Maximum (3)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x95.toByte(), 0x03.toByte(), // Report Count (3)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x75.toByte(), 0x05.toByte(), // Report Size (5)
        0x81.toByte(), 0x01.toByte(), // Input (Constant)

        // Relative X, Y, and Vertical Wheel (-127..+127)
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), // Usage (X)
        0x09.toByte(), 0x31.toByte(), // Usage (Y)
        0x09.toByte(), 0x38.toByte(), // Usage (Wheel)
        0x15.toByte(), 0x81.toByte(), // Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(), // Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x03.toByte(), // Report Count (3)
        0x81.toByte(), 0x06.toByte(), // Input (Data, Variable, Relative)

        0xC0.toByte(),                // End Collection (Physical)
        0xC0.toByte()                 // End Collection (Application)
    )

    /**
     * Builds a 4-byte mouse input report: [buttons, dx, dy, wheelDelta].
     * Convention: negative wheelDelta scrolls DOWN in standard HID hosts (Windows/Linux/macOS default).
     */
    fun buildMouseWheelReport(wheelDelta: Int, buttons: Int = 0): ByteArray {
        val clampedWheel = wheelDelta.coerceIn(-127, 127).toByte()
        return byteArrayOf(
            (buttons and 0x07).toByte(),
            0x00,
            0x00,
            clampedWheel
        )
    }

    /**
     * Builds an 8-byte keyboard input report for key press or release.
     */
    fun buildKeyboardReport(modifierMask: Byte = 0x00, keyCode: Byte = 0x00): ByteArray =
        byteArrayOf(modifierMask, 0x00, keyCode, 0x00, 0x00, 0x00, 0x00, 0x00)
}
