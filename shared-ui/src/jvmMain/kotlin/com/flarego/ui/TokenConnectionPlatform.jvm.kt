package com.flarego.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.asAwtTransferable
import java.awt.datatransfer.DataFlavor

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun ClipEntry.plainTokenText(): String? =
    asAwtTransferable?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
        ?.getTransferData(DataFlavor.stringFlavor) as? String

internal actual fun tokenDialogProperties(canCancel: Boolean) = DialogProperties(
    usePlatformDefaultWidth = false,
    dismissOnBackPress = canCancel,
    dismissOnClickOutside = false,
)

@Composable
internal actual fun TokenSystemBarAppearance(light: Boolean) = Unit
