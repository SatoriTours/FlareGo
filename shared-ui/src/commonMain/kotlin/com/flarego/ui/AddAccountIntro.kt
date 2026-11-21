package com.flarego.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flarego.core.application.connection.ReadFeature

@Composable
internal fun ColumnScope.AddAccountIntro(
    warm: Boolean,
    browserFailed: Boolean,
    onCreate: () -> Unit,
    onExisting: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val muted = if (warm) Color(0xFF546058) else colors.onSurfaceVariant
    val link = if (warm) Color(0xFF805237) else colors.tertiary
    val scopeStyle = typography.bodyLarge.copy(lineHeight = 24.sp)

    AddAccountIllustration()
    Spacer(Modifier.height(28.dp))
    Text(
        "连接 Cloudflare",
        Modifier.semantics { heading() },
        style = typography.headlineLarge.copy(fontSize = 31.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    )
    Spacer(Modifier.height(4.dp))
    Text("使用只读 Token，不授予管理权限。", color = muted,
        style = typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp))

    Spacer(Modifier.height(33.dp))
    HorizontalDivider(color = if (warm) Color(0xFFE3DFD7) else colors.outlineVariant)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("授权范围", Modifier.weight(1f), style = scopeStyle, fontWeight = FontWeight.Bold)
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            colors = ButtonDefaults.textButtonColors(contentColor = link),
            contentPadding = PaddingValues(start = 8.dp),
        ) {
            Text("权限明细", style = typography.bodyMedium)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandMore else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    Text("默认包含全部账号和域名，\n可在 Cloudflare 官网缩小范围。", color = muted, style = scopeStyle)
    if (expanded) {
        Spacer(Modifier.height(12.dp))
        Text(ReadFeature.entries.distinctBy { it.key }.joinToString("、") { it.label },
            color = muted, style = scopeStyle)
        Spacer(Modifier.height(4.dp))
        Text("Registrar 不在预填范围内。", color = muted, style = scopeStyle)
    }
    if (browserFailed) TokenBrowserError()

    Spacer(Modifier.height(35.dp))
    Button(
        onClick = onCreate,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("token-create"),
        shape = RoundedCornerShape(19.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (warm) Color(0xFF303D34) else colors.primary,
            contentColor = if (warm) Color(0xFFFFFAF0) else colors.onPrimary,
        ),
    ) {
        Text("去官网创建 Token", Modifier.weight(1f, fill = false), textAlign = TextAlign.Center,
            style = typography.labelLarge.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.NorthEast, contentDescription = null, modifier = Modifier.size(16.dp))
    }
    Spacer(Modifier.height(9.dp))
    TextButton(
        onClick = onExisting,
        modifier = Modifier.align(Alignment.CenterHorizontally).testTag("token-existing"),
        colors = ButtonDefaults.textButtonColors(contentColor = if (warm) Color(0xFF45544A) else muted),
    ) {
        Text("使用已有 Token", textAlign = TextAlign.Center, style = scopeStyle)
    }
}

@Composable
private fun AddAccountIllustration() {
    val cloud = remember {
        PathParser().parsePathString("M138 231a25 25 0 1 1 3-50 38 38 0 0 1 72-7 29 29 0 1 1 8 57Z").toPath()
    }
    val key = remember { PathParser().parsePathString("m0 0 10 10 3-3m-8-2 3-3").toPath() }
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(342f / 174f).clip(RoundedCornerShape(28.dp)),
    ) {
        scale(size.width, size.height, pivot = Offset.Zero) {
            drawRect(
                Brush.linearGradient(listOf(Color(0xFFFFF4DF), Color(0xFFFFCF9F)), end = Offset(1f, 1f)),
                size = Size(1f, 1f),
            )
        }
        // Keep the approved SVG's view box and geometry; only the illustration scales.
        scale(size.width / 342f, size.height / 174f, pivot = Offset.Zero) {
            translate(-24f, -112f) {
                rotate(-16f, pivot = Offset(195f, 208f)) {
                    val orbit = Color(0xFFDF975B).copy(alpha = .48f)
                    drawOval(orbit, Offset(-26f, 157f), Size(442f, 102f), style = Stroke(1f))
                    drawOval(orbit, Offset(-42f, 132f), Size(474f, 152f), style = Stroke(1f))
                }
                drawCircle(Color(0xFFFFA352), 27f, Offset(250f, 166f))
                drawPath(cloud, Color(0xFFFFFAF0))
                drawPath(cloud, Color(0xFFBD784B), style = Stroke(1.6f))
                translate(225f, 218f) {
                    rotate(-13f, pivot = Offset.Zero) {
                        drawRoundRect(Color(0xFF303E35), Offset(-21f, -21f), Size(45f, 45f), CornerRadius(13f))
                        val ink = Color(0xFFFFF1CF)
                        val stroke = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        drawCircle(ink, 5f, Offset(-4f, -4f), style = stroke)
                        drawPath(key, ink, style = stroke)
                    }
                }
            }
        }
    }
}
