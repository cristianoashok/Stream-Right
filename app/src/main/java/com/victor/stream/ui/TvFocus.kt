package com.victor.stream.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ==========================================
// TV REMOTE (D-PAD) FOCUS HELPERS
// Shared by every screen so the "where am I?" highlight looks the same everywhere.
// ==========================================

/** Colour of the focus ring drawn around whatever the remote is currently on. */
val TvFocusColor = Color.White

/** True when the app is running on an Android TV / Google TV device. */
fun isTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
fun rememberIsTv(): Boolean {
    val context = LocalContext.current
    return remember(context) { isTvDevice(context) }
}

/**
 * Draws a thick white ring and slightly enlarges the element while it (or the
 * clickable inside it) has remote focus. Put it BEFORE clip/clickable in the chain,
 * or pass it as the `modifier` of a Material Button.
 */
fun Modifier.tvFocusHighlight(
    shape: Shape = RoundedCornerShape(8.dp),
    focusedScale: Float = 1.06f,
    borderWidth: Dp = 3.dp
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        label = "tvFocusScale"
    )
    this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (focused) Modifier.border(borderWidth, TvFocusColor, shape) else Modifier
        )
}

/**
 * Remembers which poster on the home screen should receive focus next
 * (the first poster on launch, or the poster you opened when you come back).
 */
class TvFocusMemory {
    /** Key of the poster that should grab focus as soon as it appears. */
    var pendingKey by mutableStateOf<String?>(FIRST_ITEM)

    /** Key of the poster that most recently had focus. */
    var lastFocusedKey: String? = null

    companion object {
        const val FIRST_ITEM = "__first_item__"
    }
}

/**
 * Lets the player screen see remote-control key presses before anything else does.
 * MainActivity.dispatchKeyEvent() forwards every key here first.
 */
object RemoteKeyRouter {
    @Volatile
    var playerHandler: ((android.view.KeyEvent) -> Boolean)? = null
}
