package com.flarego.ui

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.window.DialogProperties

// Don't coerce content URIs or intents to strings, or resolve foreign clipboard content.
internal actual fun ClipEntry.plainTokenText(): String? =
    if (clipData.itemCount == 0) null else clipData.getItemAt(0).text?.toString()

internal actual fun tokenDialogProperties(canCancel: Boolean) = DialogProperties(
    usePlatformDefaultWidth = false,
    // safeDrawing includes the IME; the scrolling viewport must resize with it.
    decorFitsSystemWindows = false,
    dismissOnBackPress = canCancel,
    dismissOnClickOutside = false,
)
