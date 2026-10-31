package com.flarego.ui

import androidx.compose.runtime.Composable

/** Keeps platform back navigation aligned with the visible shared UI route. */
@Composable
internal expect fun AppBackHandler(enabled: Boolean, onBack: () -> Unit)
