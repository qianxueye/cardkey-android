package cc.cardkey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

internal enum class StatusKind { OK, PENDING, UNKNOWN, WARNING, ERROR }

/** Status glyph paired with a text label; unknown is a grey outline diamond, never accent-coloured. */
@Composable
internal fun StatusMark(kind: StatusKind, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val color: Color = when (kind) {
        StatusKind.OK -> scheme.success
        StatusKind.PENDING, StatusKind.UNKNOWN -> scheme.onSurfaceVariant
        StatusKind.WARNING -> scheme.warning
        StatusKind.ERROR -> scheme.error
    }
    Canvas(modifier.size(16.dp).clearAndSetSemantics {}) {
        val s = size.width
        val stroke = s * 0.125f
        when (kind) {
            StatusKind.OK -> drawPath(Path().apply {
                moveTo(s * .16f, s * .54f); lineTo(s * .40f, s * .76f); lineTo(s * .86f, s * .26f)
            }, color, style = Stroke(stroke * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            StatusKind.PENDING -> drawPath(Path().apply {
                moveTo(s * .5f, s * .22f); lineTo(s * .78f, s * .5f); lineTo(s * .5f, s * .78f); lineTo(s * .22f, s * .5f); close()
            }, color)
            StatusKind.UNKNOWN -> drawPath(Path().apply {
                moveTo(s * .5f, s * .1f); lineTo(s * .9f, s * .5f); lineTo(s * .5f, s * .9f); lineTo(s * .1f, s * .5f); close()
            }, color, style = Stroke(stroke, join = StrokeJoin.Round))
            StatusKind.WARNING -> drawPath(Path().apply {
                moveTo(s * .5f, s * .08f); lineTo(s * .96f, s * .88f); lineTo(s * .04f, s * .88f); close()
            }, color)
            StatusKind.ERROR -> {
                drawCircle(color, s * .45f, Offset(s / 2, s / 2))
                drawLine(scheme.surface, Offset(s * .5f, s * .28f), Offset(s * .5f, s * .55f), stroke * 1.2f, StrokeCap.Round)
                drawCircle(scheme.surface, stroke * .7f, Offset(s * .5f, s * .72f))
            }
        }
    }
}
