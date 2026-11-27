package io.github.univers629.millet.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.ui.model.AppIconSource
import io.github.univers629.millet.ui.util.AppIconCache
import io.github.univers629.millet.ui.util.StatsPackageMember
import io.github.univers629.millet.ui.util.canStackStatsMemberIcons
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
fun StatsAppIcon(
    iconSource: AppIconSource?,
    members: List<StatsPackageMember>,
    label: String,
    modifier: Modifier = Modifier,
) {
    val shared = members.size > 1
    val sources = when {
        !shared -> listOfNotNull(iconSource)
        members.size == 2 -> members.map { it.iconSource }
        else -> emptyList()
    }
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 48.dp.roundToPx() }
    var icons by remember(sources, sizePx) {
        mutableStateOf(sources.map { it?.let { src -> AppIconCache.getCached(src, sizePx) } })
    }
    val hasUncached = icons.any { it == null } && sources.any { it != null }
    if (hasUncached) {
        LaunchedEffect(sources, sizePx) {
            icons = sources.map { it?.let { src -> AppIconCache.loadIcon(context, src, sizePx) } }
        }
    }

    val semanticModifier = modifier.clearAndSetSemantics {
        contentDescription = if (shared) "$label，共享 UID" else label
    }

    // 绝大多数普通应用走无子组合的普通 Box，避免排行展开时批量触发 BoxWithConstraints 布局开销。
    if (!shared) {
        val singleIcon = icons.firstOrNull()
        val shape = RoundedCornerShape(percent = 26)
        if (singleIcon != null) {
            val imageBitmap = remember(singleIcon) { singleIcon.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = semanticModifier.clip(shape),
            )
        } else {
            Box(
                modifier = semanticModifier
                    .clip(shape)
                    .background(colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.GridView,
                    contentDescription = null,
                    tint = colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxSize(0.64f),
                )
            }
        }
        return
    }

    BoxWithConstraints(modifier = semanticModifier) {
        val extent = maxWidth
        val corner = extent * 0.26f
        val stackedIcons = icons.filterNotNull()
        if (shared && canStackStatsMemberIcons(members.size, icons.map { it != null })) {
            val iconSize = maxWidth * 0.76f
            Image(
                bitmap = stackedIcons[0].asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(iconSize)
                    .clip(RoundedCornerShape(corner)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(iconSize)
                    .clip(RoundedCornerShape(corner))
                    .background(colorScheme.surface)
                    .padding(1.dp),
            ) {
                Image(
                    bitmap = stackedIcons[1].asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(corner)),
                )
            }
        } else {
            val singleIcon = if (!shared) icons.firstOrNull() else null
            if (singleIcon != null) {
                Image(
                    bitmap = singleIcon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(corner)),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(corner))
                        .background(colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Glass.GridView,
                        contentDescription = null,
                        tint = colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxSize().padding(extent * 0.18f),
                    )
                }
            }
        }
        if (shared) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((maxWidth * 0.34f).coerceAtLeast(10.dp))
                    .clip(CircleShape)
                    .background(colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "共",
                    color = colorScheme.onPrimary,
                    fontSize = (extent.value * 0.24f).coerceAtLeast(7f).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}
