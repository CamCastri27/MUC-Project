package edu.gatech.phr25.hapticglove

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.atan2

/*
 * HAND DRAWING — the tappable hand on the main screen.
 *
 * COORDINATES: everything is drawn on a grid 100 units wide. x goes left -> right (0..100),
 * y goes top -> bottom (0 = fingertip area, ~90 = bottom of the hand). The whole grid is scaled
 * to the screen width, so 1 unit is roughly 1% of the hand's width. The hand shows the back of a
 * hand with fingers spread and the thumb on the right.
 *
 * WHERE TO EDIT:
 *   Move / resize a finger or its motor dot ... `fingerShapes` table
 *   Webbing between fingers (depth) .......... `web()`
 *   Curve between index and thumb ............ `c1` / `c2` in `palmPath`
 *   Curve under the thumb + bottom of hand ... `c3` and the `cubicTo(...)` numbers in `palmPath`
 *   Pinky side of the hand ................... last `cubicTo(...)` in `palmPath`
 *   Colors ................................... the `Color(...)` block below `visibleFingerPaths`
 *   Motor dot size / pulse animation ......... `HandView()`, "Motors" section
 *   Label font size / distance from tip ...... `drawLabel()`
 *   How forgiving taps are ................... `hitTest()`
 *
 * Fingers and palm are separate shapes merged into one outline, so you can move a finger freely
 * and the outline, webbing and selection highlight follow automatically.
 */
private const val GRID_W = 100f
/** Total grid height including TOP. Increase if you make the hand taller. */
private const val GRID_H = 101f
/** Empty space above the fingertips for the finger labels. */
private const val TOP = 10f

/**
 * One finger. All values are in grid units (see top of file).
 *
 * @param base       center of the finger at the knuckle line, where it meets the hand.
 * @param tip        center of the rounded fingertip. Direction base -> tip sets the finger's angle.
 * @param baseWidth  width at the knuckle.
 * @param tipWidth   width at the tip (smaller than baseWidth gives a natural taper).
 * @param motorAt    where the motor dot sits along the finger: 0 = fingertip, 1 = knuckle.
 * @param wrinkleAt  where the middle-knuckle wrinkle lines are drawn (same 0..1 scale).
 */
private data class FingerShape(
    val base: Offset,
    val tip: Offset,
    val baseWidth: Float,
    val tipWidth: Float,
    val motorAt: Float,
    val wrinkleAt: Float,
) {
    // ---- Geometry helpers (you normally don't need to touch these) ----

    /** Unit vector pointing from base to tip. */
    val dir: Offset get() = (tip - base) / (tip - base).getDistance()
    /** Unit vector across the finger, pointing to its screen-right side. */
    val normal: Offset get() = Offset(-dir.y, dir.x)
    /** Point along the finger's center line: t = 0 at the tip, t = 1 at the base. */
    fun at(t: Float): Offset = tip + (base - tip) * t
    /** Finger width at position t (same 0..1 scale as [at]). */
    fun widthAt(t: Float): Float = tipWidth + (baseWidth - tipWidth) * t
    /** Center of the motor dot. */
    val motor: Offset get() = at(motorAt)

    /** Base corners on the screen-left / screen-right side of the finger. */
    val baseLeft: Offset get() = base - normal * (baseWidth / 2)
    val baseRight: Offset get() = base + normal * (baseWidth / 2)

    // Control points that give the sides their slight outward bulge.
    // EDIT: the 0.5f is how much the sides bulge; 0 = straight sides.
    val midLeft: Offset get() = at(0.5f) - normal * (widthAt(0.5f) / 2 + 0.5f)
    val midRight: Offset get() = at(0.5f) + normal * (widthAt(0.5f) / 2 + 0.5f)

    /** Direction the right side is heading as it arrives at the base (pointing into the hand). */
    val rightSideIntoBase: Offset get() = (baseRight - midRight).let { it / it.getDistance() }
    /** Direction the left side heads as it leaves the base toward the tip. */
    val leftSideFromBase: Offset get() = (midLeft - baseLeft).let { it / it.getDistance() }

    /**
     * The finger's outline: tapered sides, rounded tip, plus a "root" that extends [rootDepth]
     * units below the knuckle into the hand so the finger and hand merge without a seam.
     */
    fun path(rootDepth: Float = 9f): Path {
        val n = normal
        val rootL = baseLeft - dir * rootDepth
        val rootR = baseRight - dir * rootDepth
        val tl = tip - n * (tipWidth / 2)
        val tr = tip + n * (tipWidth / 2)
        val midL = midLeft
        val midR = midRight
        // EDIT: 0.68 makes the fingertip a near-perfect half circle. Lower = flatter tip, higher = pointier.
        val cap = dir * (tipWidth * 0.68f)
        return Path().apply {
            moveTo(rootL.x, rootL.y)
            lineTo(baseLeft.x, baseLeft.y)
            quadraticTo(midL.x, midL.y, tl.x, tl.y)
            cubicTo((tl + cap).x, (tl + cap).y, (tr + cap).x, (tr + cap).y, tr.x, tr.y)
            quadraticTo(midR.x, midR.y, baseRight.x, baseRight.y)
            lineTo(rootR.x, rootR.y)
            close()
        }
    }
}

// EDIT: the main table for the hand's shape. Screen order, left to right: pinky, ring, middle, index, thumb.
// To move a finger, change base and tip together. To lengthen or shorten it, move only the tip.
// Neighbouring fingers should not overlap at the base, or the webbing between them will look off.
//                                        base (x, y)          tip (x, y)       baseW  tipW  motorAt wrinkleAt
private val fingerShapes = mapOf(
    Finger.PINKY to FingerShape(Offset(22f, 47f), Offset(11.5f, 22f), 10f, 8.2f, 0.74f, 0.5f),
    Finger.RING to FingerShape(Offset(32f, 42f), Offset(26f, 6.5f), 10.3f, 9f, 0.76f, 0.52f),
    Finger.MIDDLE to FingerShape(Offset(42.5f, 42f), Offset(41.5f, 4.5f), 10.6f, 9.4f, 0.76f, 0.52f),
    Finger.INDEX to FingerShape(Offset(53f, 43.5f), Offset(61f, 11.5f), 10.6f, 9.2f, 0.76f, 0.52f),
    Finger.THUMB to FingerShape(Offset(69.1f, 59.2f), Offset(87.4f, 43.1f), 12f, 9.5f, 0.62f, 0.4f),
)

/** Webbing between two neighbouring fingers: a shallow U from one base corner to the next. */
private fun Path.web(to: Offset, from: Offset) {
    // EDIT: 2.2f is how far the web dips below the knuckles. 0 = straight line, bigger = deeper U.
    val mid = (from + to) / 2f + Offset(0f, 2.2f)
    quadraticTo(mid.x, mid.y, to.x, to.y)
}

/**
 * Back of the hand (everything that isn't a finger). Traced clockwise starting at the pinky:
 * across the knuckles (with webbing between fingers), down into the index/thumb curve, across the
 * thumb's base, around the bottom of the hand, and back up the pinky side.
 */
private val palmPath = Path().apply {
    val p = fingerShapes.getValue(Finger.PINKY)
    val r = fingerShapes.getValue(Finger.RING)
    val m = fingerShapes.getValue(Finger.MIDDLE)
    val i = fingerShapes.getValue(Finger.INDEX)
    val t = fingerShapes.getValue(Finger.THUMB)
    moveTo(p.baseLeft.x, p.baseLeft.y)
    lineTo(p.baseRight.x, p.baseRight.y)
    web(r.baseLeft, p.baseRight)
    lineTo(r.baseRight.x, r.baseRight.y)
    web(m.baseLeft, r.baseRight)
    lineTo(m.baseRight.x, m.baseRight.y)
    web(i.baseLeft, m.baseRight)
    lineTo(i.baseRight.x, i.baseRight.y)
    // Curve between index and thumb: a U that continues the index's side and flows into the
    // thumb's side with no corners (control points follow each finger's edge direction).
    // EDIT: bigger numbers = deeper, rounder U. c1 shapes the index side, c2 the thumb side.
    val c1 = i.baseRight + i.rightSideIntoBase * 4f
    val c2 = t.baseLeft - t.leftSideFromBase * 6.5f
    cubicTo(c1.x, c1.y, c2.x, c2.y, t.baseLeft.x, t.baseLeft.y)
    lineTo(t.baseRight.x, t.baseRight.y)
    // One smooth sweep from the thumb's lower edge around to the bottom-center of the hand (46, 89.5).
    // EDIT: 7f = how far the curve keeps following the thumb's angle before turning.
    //       63f = how wide the bottom-right corner is (bigger = fuller hand on the thumb side).
    //       89.5f = how low the bottom of the hand sits.
    val c3 = t.baseRight + t.rightSideIntoBase * 7f
    cubicTo(c3.x, c3.y, 63f, 89.5f, 46f, 89.5f)
    // Bottom-center around to the pinky side of the hand at (20.5, 72).
    cubicTo(31f, 89.5f, 22f, 83f, 20.5f, 72f)
    // Pinky side of the hand back up to the pinky's knuckle. Smaller x values = wider hand on that side.
    cubicTo(19.5f, 63f, 17.5f, 55f, p.baseLeft.x, p.baseLeft.y)
    close()
}

/** Full hand silhouette: palm + all fingers merged into one shape (used for fill and outline). */
private val handPath: Path = fingerShapes.values.fold(palmPath) { acc, f ->
    Path.combine(PathOperation.Union, acc, f.path())
}

/** Just the part of each finger above its knuckle line, used for the selection highlight. */
private val visibleFingerPaths: Map<Finger, Path> = fingerShapes.mapValues { (_, f) ->
    Path.combine(PathOperation.Difference, f.path(), palmPath)
}

// EDIT: colors, as 0xAARRGGBB (AA = opacity, FF = solid).
private val SkinLight = Color(0xFFF7D8C2)   // center of the back of the hand
private val Skin = Color(0xFFEDC1A2)        // main skin tone
private val SkinShade = Color(0xFFDDAA88)   // edges and fingertips (shading)
private val SkinOutline = Color(0xFFB98A6A) // outline around the whole hand
private val Wrinkle = Color(0xFFC08E6D)     // knuckle wrinkle lines
private val Nail = Color(0xFFF8DEDA)
private val NailEdge = Color(0xFFD9A99A)
private val Highlight = Color(0xFF2F6FED)     // outline of the selected finger
private val HighlightFill = Color(0xFFD9E5FD) // fill of the selected finger
private val MotorOff = Color(0xFF9AA0A6)      // ring of a motor dot that is off
private val MotorOn = Color(0xFF2F6FED)       // motor dot that is on

/**
 * Draws the hand and handles taps.
 *
 * @param motors      on/off for each motor, indexed by [Finger.ordinal].
 * @param selected    the finger currently highlighted, or null.
 * @param onFingerTap called with the tapped finger, or null if the tap missed every finger.
 */

@Composable
fun HandView(
    motors: List<MotorState>,
    selected: Finger?,
    onFingerTap: (Finger?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Drives the expanding ring around motors that are on. Goes 0 -> 1 repeatedly.
    // EDIT: 900 = milliseconds per pulse. Lower = faster pulsing.
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier
            .aspectRatio(GRID_W / GRID_H)
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    // Convert the tap from screen pixels to grid units, then find the finger under it.
                    val scale = size.width / GRID_W
                    onFingerTap(hitTest(Offset(tap.x / scale, tap.y / scale - TOP)))
                }
            }
    ) {
        val s = size.width / GRID_W // screen pixels per grid unit

        // Everything inside this block is drawn in grid units (the transform scales it to the screen).
        // Drawing order matters: later items are drawn on top of earlier ones.
        withTransform({
            scale(s, s, pivot = Offset.Zero)
            translate(0f, TOP)
        }) {
            // 1. Skin with soft shading: lighter on the back of the hand, darker toward the edges.
            //    EDIT: center = brightest spot, radius = how far the shading spreads.
            drawPath(
                handPath,
                Brush.radialGradient(
                    0f to SkinLight, 0.5f to Skin, 1f to SkinShade,
                    center = Offset(46f, 62f), radius = 62f,
                ),
            )

            // 2. Selected finger: light blue fill, only over the finger itself (not the hand).
            val selectedPath = selected?.let { visibleFingerPaths[it] }
            selectedPath?.let { drawPath(it, HighlightFill) }

            // 3. Knuckle wrinkles and fingernails.
            fingerShapes.values.forEach { f ->
                drawWrinkle(f)
                drawNail(f)
            }

            // 4. Hand outline. EDIT: Stroke(1f) = outline thickness in grid units.
            drawPath(handPath, SkinOutline, style = Stroke(1f, join = StrokeJoin.Round))

            // 5. Selected finger: trace the hand outline in blue, clipped to that finger, so the
            //    highlight follows the finger's edge and stops at the knuckle line.
            //    EDIT: Stroke(2.4f) — only the inner half shows, so the visible line is ~1.2 units.
            selectedPath?.let { p ->
                clipPath(p) { drawPath(handPath, Highlight, style = Stroke(2.4f, join = StrokeJoin.Round)) }
            }

            // 6. Motor dots (on top of everything else).
            fingerShapes.forEach { (finger, f) ->
                val m = motors[finger.ordinal]
                val c = f.motor
                val r = 3.4f // EDIT: motor dot radius in grid units
                if (m.on) {
                    // Pulse ring: grows and fades out.
                    // EDIT: 4.5f = how far the ring expands beyond the dot; 0.5f = starting opacity.
                    drawCircle(
                        MotorOn.copy(alpha = (1f - pulse) * 0.5f),
                        radius = r + pulse * 4.5f, center = c,
                    )
                    // Solid blue dot.
                    drawCircle(MotorOn, r, c)
                } else {
                    // Off: white dot with a grey ring.
                    drawCircle(Color.White, r, c)
                    drawCircle(MotorOff, r, c, style = Stroke(0.8f))
                }
                if (finger == selected) {
                    // Blue ring around the selected finger's motor.
                    drawCircle(Highlight, r + 1.5f, c, style = Stroke(0.9f))
                }
            }
        }

        // Labels are drawn outside the grid transform so the text isn't scaled with the hand.
        fingerShapes.forEach { (finger, f) -> drawLabel(textMeasurer, finger.label, f, s) }
    }
}

/** Two soft curved lines over the middle knuckle, bowed toward the fingertip. */
private fun DrawScope.drawWrinkle(f: FingerShape) {
    val c = f.at(f.wrinkleAt)
    val w = f.widthAt(f.wrinkleAt)
    // EDIT: each pair is (distance toward the tip, half-length as a fraction of finger width).
    // Add or remove pairs to draw more or fewer wrinkle lines.
    for ((offset, span) in listOf(0f to 0.32f, 1.3f to 0.22f)) {
        val center = c + f.dir * offset
        val a = center - f.normal * (w * span)
        val b = center + f.normal * (w * span)
        val ctrl = center + f.dir * 1.1f
        val path = Path().apply { moveTo(a.x, a.y); quadraticTo(ctrl.x, ctrl.y, b.x, b.y) }
        drawPath(path, Wrinkle.copy(alpha = 0.55f), style = Stroke(0.5f, cap = StrokeCap.Round))
    }
}

/** Fingernail near the tip, aligned with the finger. */
private fun DrawScope.drawNail(f: FingerShape) {
    // EDIT: nail length and width as fractions of the fingertip width.
    val len = f.tipWidth * 0.95f
    val wid = f.tipWidth * 0.62f
    // EDIT: 0.32f = how close the nail sits to the very end of the finger (bigger = closer to the end).
    val center = f.tip - f.dir * (len * 0.5f - f.tipWidth * 0.32f)
    val angle = atan2(f.dir.y, f.dir.x)
    rotateRad(angle, pivot = center) {
        val topLeft = center - Offset(len / 2, wid / 2)
        val corner = CornerRadius(wid * 0.45f)
        drawRoundRect(Nail, topLeft, Size(len, wid), corner)
        drawRoundRect(NailEdge, topLeft, Size(len, wid), corner, style = Stroke(0.35f))
    }
}

/** Finger name just beyond the fingertip. Kept inside the canvas so labels near the edge don't get cut off. */
private fun DrawScope.drawLabel(tm: TextMeasurer, text: String, f: FingerShape, s: Float) {
    // EDIT: label font size and color.
    val layout = tm.measure(text, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF5F6368)))
    // Put the label beyond the fingertip, along the finger's direction.
    // EDIT: 5f = gap between the fingertip and the label, in grid units.
    val anchor = (f.tip + f.dir * (f.tipWidth * 0.5f + 5f) + Offset(0f, TOP)) * s
    drawText(
        layout,
        topLeft = Offset(
            (anchor.x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
            (anchor.y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height),
        ),
    )
}

/** Returns the finger under [p] (grid units), or null for the back of the hand / background. */
private fun hitTest(p: Offset): Finger? {
    // EDIT: extra grid units around each finger that still count as a tap on it. Bigger = easier to hit.
    val slop = 2.5f
    return fingerShapes.entries
        .map { (finger, f) ->
            // Only the finger itself (down to just past the motor) is tappable, not the hand under it.
            // EDIT: 0.12f = how far below the motor dot taps still select the finger.
            val reach = f.at((f.motorAt + 0.12f).coerceAtMost(1f))
            finger to distanceToSegment(p, reach, f.tip) - f.widthAt(0.5f) / 2
        }
        .filter { it.second < slop }
        .minByOrNull { it.second }
        ?.first
}

/** Shortest distance from point p to the line segment a-b. */
private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val t = (((p - a).x * ab.x + (p - a).y * ab.y) / (ab.x * ab.x + ab.y * ab.y)).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}
