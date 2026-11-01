package cc.cardkey.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText

/** A clickable home list row, excluding the navigation bar item with the same label. */
fun homeTask(label: String): SemanticsMatcher =
    hasText(label) and hasClickAction() and !SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

/** The navigation bar item with this label. */
fun navTab(label: String): SemanticsMatcher =
    hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
