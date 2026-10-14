package cc.cardkey.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp

@Composable
internal fun workspaceInputModifier(viewportHeight: Dp): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(viewportHeight, focused) {
        if (focused) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    return Modifier.bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
}
