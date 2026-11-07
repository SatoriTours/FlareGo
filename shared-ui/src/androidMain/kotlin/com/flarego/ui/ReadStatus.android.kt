package com.flarego.ui

import java.text.DateFormat
import java.util.Date

internal actual fun observationTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(epochMs))
