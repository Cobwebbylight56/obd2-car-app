package com.rhys.obd2.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The spacing scale.
 *
 * Every gap in the app comes from here. The previous values were chosen per call site —
 * 5, 6, 7, 10, 12, 14, 16 all appeared — and the result is the kind of drift you can't
 * name but can see: rows that almost line up, cards whose padding is a pixel off their
 * neighbour's. A four-point scale removes the decision and makes alignment automatic.
 */
object Space {
    /** Hairline separation inside a component, e.g. a dot from its label. */
    val xxs = 2.dp

    /** Between tightly related items: an icon and its text. */
    val xs = 4.dp

    /** Within a component: rows of a list, a label above its value. */
    val sm = 8.dp

    /** Between components inside a card. */
    val md = 12.dp

    /** Standard padding inside a card, and between cards. */
    val lg = 16.dp

    /** Screen margins and separation between unrelated blocks. */
    val xl = 24.dp

    /** Around lone empty-state content, so it doesn't read as an error. */
    val xxl = 32.dp

    /**
     * Trailing space at the bottom of every scrolling screen.
     *
     * Without it the last card sits flush against the navigation bar and looks clipped —
     * the reader can't tell whether the list has ended or the screen has run out.
     */
    val scrollFooter = 32.dp
}

/**
 * Corner radii.
 *
 * Radius carries hierarchy: the bigger the block, the softer the corner. Cards were 16,
 * banners 14 and inline notes 12 for no reason other than the order they were written, so
 * the eye read three different kinds of container where there were meant to be two.
 */
object Radius {
    /** Chips, pills and anything fully rounded. */
    val pill = RoundedCornerShape(percent = 50)

    /** Inline controls: buttons, text fields, small tappable rows. */
    val control = RoundedCornerShape(12.dp)

    /** Cards and any block that groups other content. */
    val card = RoundedCornerShape(18.dp)

    /** Dialogs and sheets, which sit above everything else. */
    val sheet = RoundedCornerShape(24.dp)
}

/**
 * Motion.
 *
 * Two rules. Anything the user initiated confirms in [Fast] so the app feels responsive to
 * touch. Anything the *car* initiated — a value changing, a state arriving — moves in
 * [Standard] on a decelerating curve, because a reading that snaps looks like a glitch and
 * a reading that eases looks like a measurement.
 *
 * [Emphasised] is reserved for a value crossing into a warning or danger band, where the
 * change is the message and deserves to catch the eye.
 */
object Motion {
    const val Fast = 120
    const val Standard = 260
    const val Slow = 420

    /** Decelerate: enters quickly, settles gently. The default for incoming values. */
    val Decelerate: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    /** Standard ease for changes that both begin and end on screen. */
    val Standard_: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** Slight overshoot, for a state change worth noticing. */
    val Emphasised: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
}

/**
 * Minimum sizes for anything you can touch.
 *
 * This app is used one-handed, often in a parked car with the phone in a cradle and the
 * user leaning across, sometimes in gloves. The Material minimum of 48dp is treated as a
 * floor rather than a target, and [TouchTarget.comfortable] is used for the controls that
 * matter — connect, read codes, clear codes — where a mis-tap costs real time.
 */
object TouchTarget {
    val minimum = 48.dp
    val comfortable = 56.dp
}
