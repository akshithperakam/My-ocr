package com.codecapture

import com.codecapture.vision.EndOfContentDetector
import com.codecapture.vision.EndOfContentVerdict
import com.codecapture.vision.OverlapMatchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class EndOfContentDetectorTest {

    @Test
    fun distinguishesBluetoothDisconnectFromEndOfDocument() {
        val detector = EndOfContentDetector()
        val staticMatch = OverlapMatchResult(0f, 1f, 0.99f, null, 0.99f, isIdenticalFrame = true)

        val assessment = detector.evaluate(
            bluetoothConnected = false,
            cameraMovedSignificantly = false,
            scrollCommandSentSuccessfully = false,
            overlapMatch = staticMatch,
            newUnseenLinesCount = 0
        )
        assertEquals(EndOfContentVerdict.BLUETOOTH_DISCONNECTED, assessment.verdict)
    }

    @Test
    fun requiresConsecutiveStaticAttemptsBeforeDeclaringEndOfContent() {
        val detector = EndOfContentDetector(requiredConsecutiveStaticAttempts = 2)
        val staticMatch = OverlapMatchResult(0.005f, 0.995f, 0.98f, null, 0.98f, isIdenticalFrame = true)

        val first = detector.evaluate(true, false, true, staticMatch, 0)
        assertEquals(EndOfContentVerdict.EDITOR_UNRESPONSIVE_RETRY, first.verdict)

        val second = detector.evaluate(true, false, true, staticMatch, 0)
        assertEquals(EndOfContentVerdict.END_OF_DOCUMENT_REACHED, second.verdict)
    }
}
