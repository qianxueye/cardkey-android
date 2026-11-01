package cc.cardkey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Navigation bar item whose active indicator is a 3 dp rectangle; Material 3 only offers a pill. */
@Composable
internal fun RowScope.CardKeyNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        selected -> scheme.onSecondaryContainer
        else -> scheme.onSurfaceVariant
    }
    Column(
        Modifier.weight(1f).selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            Box(Modifier.width(64.dp).height(32.dp).clip(MaterialTheme.shapes.small)
                .background(if (selected) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.Center) { icon() }
            Box(Modifier.padding(top = 4.dp)) {
                ProvideTextStyle(MaterialTheme.typography.labelMedium) { label() }
            }
        }
    }
}
