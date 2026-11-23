package com.android.benchmark.app

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import com.android.benchmark.results.RunStore.Status

private val light = lightColorScheme(
    primary = Color(0xFF284DA8), onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE6FF), onPrimaryContainer = Color(0xFF142E71),
    secondary = Color(0xFF4F5D7D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEE5F9), onSecondaryContainer = Color(0xFF303F5D),
    tertiary = Color(0xFF79562B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB4), onTertiaryContainer = Color(0xFF543713),
    background = Color(0xFFF8F9FE), onBackground = Color(0xFF181D29),
    surface = Color(0xFFF8F9FE), onSurface = Color(0xFF181D29),
    onSurfaceVariant = Color(0xFF495164), outline = Color(0xFF707A90),
    outlineVariant = Color(0xFFCCD3E2), surfaceDim = Color(0xFFD8DBE6),
    surfaceBright = Color(0xFFF8F9FE), surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2FA), surfaceContainer = Color(0xFFE9EDF7),
    surfaceContainerHigh = Color(0xFFE3E8F3), surfaceContainerHighest = Color(0xFFDDE3EF),
    inverseSurface = Color(0xFF2D3444), inverseOnSurface = Color(0xFFF0F2FB),
    inversePrimary = Color(0xFFB5C6FF), surfaceVariant = Color(0xFFE0E5F1),
)

private val dark = darkColorScheme(
    primary = Color(0xFFB5C6FF), onPrimary = Color(0xFF102B6C),
    primaryContainer = Color(0xFF24458E), onPrimaryContainer = Color(0xFFDEE6FF),
    secondary = Color(0xFFC0CBE9), onSecondary = Color(0xFF283554),
    secondaryContainer = Color(0xFF394767), onSecondaryContainer = Color(0xFFE1E8FF),
    tertiary = Color(0xFFF1BC81), onTertiary = Color(0xFF472B0A),
    tertiaryContainer = Color(0xFF62441F), onTertiaryContainer = Color(0xFFFFDDB4),
    background = Color(0xFF111621), onBackground = Color(0xFFE4E8F4),
    surface = Color(0xFF111621), onSurface = Color(0xFFE4E8F4),
    onSurfaceVariant = Color(0xFFC0C8DA), outline = Color(0xFF8B96AC),
    outlineVariant = Color(0xFF3C4659), surfaceDim = Color(0xFF111621),
    surfaceBright = Color(0xFF343D4F), surfaceContainerLowest = Color(0xFF0C111A),
    surfaceContainerLow = Color(0xFF181E2B), surfaceContainer = Color(0xFF1D2534),
    surfaceContainerHigh = Color(0xFF252E3F), surfaceContainerHighest = Color(0xFF303A4C),
    inverseSurface = Color(0xFFE4E8F4), inverseOnSurface = Color(0xFF2A3242),
    inversePrimary = Color(0xFF284DA8), surfaceVariant = Color(0xFF414B5F),
)

internal object BenchTokens {
    val fine = 4.dp
    val small = 8.dp
    val gap = 12.dp
    val inset = 16.dp
    val section = 24.dp
    val touch = 48.dp
    val contentWidth = 760.dp
    val twoColumnWidth = 640.dp
    val panelShape = RoundedCornerShape(18.dp)
    val groupCorner = 16.dp
    val seamCorner = 5.dp
}

private val systemType = Typography()
private val benchType = Typography(
    displayLarge = systemType.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp),
    headlineMedium = systemType.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = systemType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = systemType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = systemType.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.35.sp),
)

private val benchShapes = Shapes(
    extraSmall = RoundedCornerShape(BenchTokens.seamCorner),
    small = RoundedCornerShape(BenchTokens.small),
    medium = RoundedCornerShape(12.dp),
    large = BenchTokens.panelShape,
    extraLarge = RoundedCornerShape(BenchTokens.section),
)

@Composable
internal fun BenchmarkTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val night = isSystemInDarkTheme()
    val colors = remember(context, night) { benchmarkColors(context, night) }
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = benchType,
        shapes = benchShapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

@Composable
internal fun AppSurface(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
    }
}

internal enum class Glyph { Back, Forward, Down, Up, Play }

@Composable
internal fun BenchGlyph(glyph: Glyph, color: Color = MaterialTheme.colorScheme.onSurface) {
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.size(22.dp).drawWithCache {
        val path = Path()
        fun point(x: Float, y: Float) = Offset(x * size.width, y * size.height)
        fun move(x: Float, y: Float) = point(x, y).let { path.moveTo(it.x, it.y) }
        fun line(x: Float, y: Float) = point(x, y).let { path.lineTo(it.x, it.y) }
        when (glyph) {
            Glyph.Back, Glyph.Forward -> {
                val left = (glyph == Glyph.Back) != mirrored
                val tip = if (left) .26f else .74f
                val tail = 1f - tip
                val half = if (left) .52f else .48f
                move(tail, .5f)
                line(tip, .5f)
                move(half, .25f)
                line(tip, .5f)
                line(half, .75f)
            }
            Glyph.Down, Glyph.Up -> {
                val top = if (glyph == Glyph.Down) .38f else .62f
                move(.25f, top)
                line(.50f, 1f - top)
                line(.75f, top)
            }
            Glyph.Play -> {
                move(.33f, .20f)
                line(.79f, .50f)
                line(.33f, .80f)
                path.close()
            }
        }
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            if (glyph == Glyph.Play) drawPath(path, color)
            else drawPath(path, color, style = stroke)
        }
    })
}

@Composable
internal fun ScreenBar(title: String, back: () -> Unit, action: @Composable () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.widthIn(max = BenchTokens.contentWidth).fillMaxWidth()
                        .padding(horizontal = BenchTokens.inset, vertical = BenchTokens.fine),
                    horizontalArrangement = Arrangement.spacedBy(BenchTokens.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = back,
                        modifier = Modifier.semantics { contentDescription = "Back" },
                    ) { BenchGlyph(Glyph.Back) }
                    Text(title, Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                    action()
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
internal fun Supporting(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun Numeric(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleMedium,
                     color: Color = Color.Unspecified) {
    val numericStyle = remember(style) { style.copy(fontFeatureSettings = "tnum") }
    Text(text, modifier, color = color, style = numericStyle)
}

@Composable
internal fun StatusLabel(status: Status, color: Color = statusColor(status)) {
    Text(ResultLabels.status(status), color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun statusColor(status: Status): Color = when (status) {
    Status.FAILED -> MaterialTheme.colorScheme.error
    Status.COMPLETE -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = BenchTokens.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(BenchTokens.inset), verticalArrangement = Arrangement.spacedBy(BenchTokens.small),
            content = content)
    }
}

@Composable
internal fun ScreenMessage(title: String, description: String? = null, loading: Boolean = false) {
    Panel(if (loading) Modifier.progressSemantics() else Modifier) {
        SectionHeading(title)
        if (description != null) Supporting(description)
    }
}

@Composable
internal fun DialogRefreshRate() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.let(Screen::optOutOfAdaptiveRefresh) }
}

private fun benchmarkColors(context: Context, night: Boolean) = when {
    Build.VERSION.SDK_INT >= 31 && night -> dynamicDarkColorScheme(context)
    Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
    night -> dark
    else -> light
}

internal class RunnerColors(context: Context, night: Boolean) {
    private val colors = benchmarkColors(context, night)
    val header = colors.surfaceContainer.toArgb()
    val title = colors.onSurface.toArgb()
    val supporting = colors.onSurfaceVariant.toArgb()
    val progress = colors.primary.toArgb()
    val track = colors.outlineVariant.toArgb()
    val action = colors.secondaryContainer.toArgb()
    val onAction = colors.onSecondaryContainer.toArgb()
}
