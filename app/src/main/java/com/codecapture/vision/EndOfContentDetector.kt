package com.codecapture.vision

enum class EndOfContentVerdict {
    CONTINUE_SCROLLING,
    END_OF_DOCUMENT_REACHED,
    BLUETOOTH_DISCONNECTED,
    CAMERA_MOVEMENT_INTERFERENCE,
    EDITOR_UNRESPONSIVE_RETRY
}

data class EndOfContentAssessment(
    val verdict: EndOfContentVerdict,
    val consecutiveNoMoveCount: Int,
    val message: String
)

/**
 * Distinguishes true End-of-Content from temporary scroll failure, Bluetooth disconnect,
 * camera motion, or unfocused editor (FR-12). Supports explicit user override.
 */
class EndOfContentDetector(
    private val requiredConsecutiveStaticAttempts: Int = 2,
    private val minMeaningfulDisplacement: Float = 0.025f
) {
    private var consecutiveStaticCount: Int = 0
    private var overrideActive: Boolean = false

    fun reset() {
        consecutiveStaticCount = 0
        overrideActive = false
    }

    fun overrideEndOfContent() {
        overrideActive = true
        consecutiveStaticCount = 0
    }

    fun evaluate(
        bluetoothConnected: Boolean,
        cameraMovedSignificantly: Boolean,
        scrollCommandSentSuccessfully: Boolean,
        overlapMatch: OverlapMatchResult,
        newUnseenLinesCount: Int?
    ): EndOfContentAssessment {
        if (!bluetoothConnected) {
            return EndOfContentAssessment(
                verdict = EndOfContentVerdict.BLUETOOTH_DISCONNECTED,
                consecutiveNoMoveCount = consecutiveStaticCount,
                message = "Bluetooth HID disconnected before scroll could be verified."
            )
        }

        if (cameraMovedSignificantly) {
            return EndOfContentAssessment(
                verdict = EndOfContentVerdict.CAMERA_MOVEMENT_INTERFERENCE,
                consecutiveNoMoveCount = consecutiveStaticCount,
                message = "Camera moved significantly; recalibrating before assuming end of file."
            )
        }

        if (!scrollCommandSentSuccessfully) {
            return EndOfContentAssessment(
                verdict = EndOfContentVerdict.EDITOR_UNRESPONSIVE_RETRY,
                consecutiveNoMoveCount = consecutiveStaticCount,
                message = "HID scroll report failed to transmit; retrying."
            )
        }

        val noVisualMove = overlapMatch.isIdenticalFrame ||
            overlapMatch.displacementRatio < minMeaningfulDisplacement
        val noNewLines = newUnseenLinesCount == null || newUnseenLinesCount == 0

        if (noVisualMove && noNewLines) {
            consecutiveStaticCount++
            if (overrideActive) {
                overrideActive = false
                return EndOfContentAssessment(
                    verdict = EndOfContentVerdict.EDITOR_UNRESPONSIVE_RETRY,
                    consecutiveNoMoveCount = consecutiveStaticCount,
                    message = "End-of-content override active; attempting one more scroll."
                )
            }
            return if (consecutiveStaticCount >= requiredConsecutiveStaticAttempts) {
                EndOfContentAssessment(
                    verdict = EndOfContentVerdict.END_OF_DOCUMENT_REACHED,
                    consecutiveNoMoveCount = consecutiveStaticCount,
                    message = "No visual displacement across $consecutiveStaticCount scroll attempts; end of content reached."
                )
            } else {
                EndOfContentAssessment(
                    verdict = EndOfContentVerdict.EDITOR_UNRESPONSIVE_RETRY,
                    consecutiveNoMoveCount = consecutiveStaticCount,
                    message = "Screen did not move on attempt $consecutiveStaticCount; verifying editor focus and retrying."
                )
            }
        }

        consecutiveStaticCount = 0
        return EndOfContentAssessment(
            verdict = EndOfContentVerdict.CONTINUE_SCROLLING,
            consecutiveNoMoveCount = 0,
            message = "Screen scrolled cleanly (${"%.0f".format(overlapMatch.displacementRatio * 100f)}% displacement)."
        )
    }
}
