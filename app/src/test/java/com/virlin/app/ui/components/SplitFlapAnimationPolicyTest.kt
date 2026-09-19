package com.virlin.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Elapsed-second policy for split-flap: baseline/rebase snaps; adjacent ticks flip.
 */
class SplitFlapAnimationPolicyTest {

    @Test fun firstRender_snaps() {
        assertFalse(splitFlapShouldAnimate(previousSeconds = null, nextSeconds = 0))
        assertFalse(splitFlapShouldAnimate(previousSeconds = null, nextSeconds = 1634))
    }

    @Test fun adjacentTick_animates() {
        assertTrue(splitFlapShouldAnimate(0, 1))
        assertTrue(splitFlapShouldAnimate(9, 10))   // 00:09 → 00:10
        assertTrue(splitFlapShouldAnimate(59, 60))  // 00:59 → 01:00
        assertTrue(splitFlapShouldAnimate(1954, 1955))
    }

    @Test fun largeDiscontinuity_snaps() {
        assertFalse(splitFlapShouldAnimate(1955, 0))
        assertFalse(splitFlapShouldAnimate(0, 1634))
        assertFalse(splitFlapShouldAnimate(10, 8))
        assertFalse(splitFlapShouldAnimate(10, 12))
    }

    @Test fun sameValue_doesNotAnimate() {
        assertFalse(splitFlapShouldAnimate(42, 42))
    }

    @Test fun focusSwitchStyleJump_snaps() {
        // Stream A at 27:14 → Stream B at 00:00
        assertFalse(splitFlapShouldAnimate(27 * 60 + 14, 0))
    }

    @Test fun adjacentDigitGlyph_steps() {
        assertTrue(isAdjacentDigitStep('8', '9'))
        assertTrue(isAdjacentDigitStep('9', '0'))
        assertTrue(isAdjacentDigitStep('0', '1'))
        assertFalse(isAdjacentDigitStep('8', '0'))
        assertFalse(isAdjacentDigitStep('1', '3'))
        assertFalse(isAdjacentDigitStep('5', '5'))
    }

    @Test fun fullscreenSecondsForce_onlyOnAdjacentElapsedTick() {
        // Same policy gate the Fullscreen seconds epoch uses — first open / jump = no force.
        assertFalse(splitFlapShouldAnimate(null, 52))
        assertTrue(splitFlapShouldAnimate(52, 53))   // force both seconds (incl. 5→5)
        assertTrue(splitFlapShouldAnimate(58, 59))
        assertTrue(splitFlapShouldAnimate(59, 60))   // force 5→0 and 9→0
        assertFalse(splitFlapShouldAnimate(52, 54))  // discontinuity — SNAP, no force
    }
}
