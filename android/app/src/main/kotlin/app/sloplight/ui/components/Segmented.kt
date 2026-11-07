package app.sloplight.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.sloplight.ui.theme.LocalIsDarkTheme

/** The gap between the rows of a block */
val SegmentGap = 3.dp

/** Cards and blocks of rows: lifted off the page (a step up in dark, a step down in light) */
@Composable
@ReadOnlyComposable
fun cardColor(): Color =
    if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainerLow

/** Row [index] of [count] in a block: the block's corners round, where rows meet only slightly */
@Composable
@ReadOnlyComposable
fun segmentShape(index: Int, count: Int): CornerBasedShape {
    val outer = MaterialTheme.shapes.large
    val inner = MaterialTheme.shapes.extraSmall
    return when {
        count == 1 -> outer
        index == 0 -> RoundedCornerShape(outer.topStart, outer.topEnd, inner.bottomEnd, inner.bottomStart)
        index == count - 1 -> RoundedCornerShape(inner.topStart, inner.topEnd, outer.bottomEnd, outer.bottomStart)
        else -> inner
    }
}

/** A heading over a block, in the primary color */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}

/** Rows as one block (outside lazy lists; in them, [segmentShape] per item) */
@Composable
fun SegmentedColumn(items: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        items.forEachIndexed { index, item ->
            Surface(shape = segmentShape(index, items.size), color = cardColor()) { item() }
        }
    }
}

/**
 * A row of a block: [content] (its title) over [supportingContent], an icon
 * before, a value or a control after; [selected] in the secondary container
 * color. Its shape stays put when pressed (only a ripple).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SegmentRow(
    shapes: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (selected) colors.secondaryContainer else cardColor()
    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface
    Surface(shape = shapes, color = container, contentColor = contentColor, modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier
                .then(
                    if (onClick != null) Modifier.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
                    else Modifier
                )
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (enabled) 1f else 0.38f),
        ) {
            if (leadingContent != null) {
                androidx.compose.foundation.layout.Box(Modifier.padding(end = 16.dp)) {
                    CompositionLocalProvider(LocalContentColor provides if (selected) contentColor else colors.onSurfaceVariant) { leadingContent() }
                }
            }
            Column(Modifier.weight(1f)) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge) { content() }
                if (supportingContent != null) {
                    CompositionLocalProvider(LocalContentColor provides if (selected) contentColor.copy(alpha = 0.8f) else colors.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { supportingContent() }
                    }
                }
            }
            if (trailingContent != null) {
                androidx.compose.foundation.layout.Box(Modifier.padding(start = 16.dp)) { trailingContent() }
            }
        }
    }
}
