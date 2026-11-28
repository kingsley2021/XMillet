package io.github.univers629.millet.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.core.AppReleaseInfo
import io.github.univers629.millet.core.AppUpdateVersionParser
import io.github.univers629.millet.ui.util.AppUpdateController
import io.github.univers629.millet.ui.util.AppUpdateDownloadState
import io.github.univers629.millet.ui.util.PopupBackdropHolder
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 新版本更新底部面板（与「运行环境与服务检测」「配置与备份」同款 `GlassBottomSheet`）：
 * 采样全局弹窗背景源，随「背景模糊」档位在关闭（不透明纯色）与高斯/渐进模糊（液态玻璃）间自动切换。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateDialogMiuix(
    show: Boolean,
    currentVersionName: String,
    release: AppReleaseInfo,
    downloadState: AppUpdateDownloadState,
    onDismiss: () -> Unit,
    onIgnoreVersion: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onPrimaryAction: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val downloading = downloadState as? AppUpdateDownloadState.Downloading
    val failed = downloadState as? AppUpdateDownloadState.Failed
    val formattedNotes = remember(release.body) {
        AppUpdateVersionParser.formatReleaseBody(release.body)
    }
    val publishedText = remember(release.publishedAtUtc) {
        AppUpdateController.formatPublishedTime(release.publishedAtUtc)
    }
    val sizeText = remember(release.apkAsset?.sizeBytes) {
        AppUpdateVersionParser.formatFileSize(release.apkAsset?.sizeBytes ?: 0L)
    }

    val primaryLabel = when {
        release.apkAsset == null -> "前往发布页"
        downloading != null -> {
            val pct = downloading.fraction?.let { (it * 100f).roundToInt() }
            if (pct != null) "下载中 $pct%" else "下载中…"
        }

        downloadState is AppUpdateDownloadState.Ready -> "立即安装"
        failed != null -> "重试下载"
        else -> "下载并安装"
    }

    val configuration = LocalConfiguration.current
    val bodyMaxHeight = (configuration.screenHeightDp.dp * 0.56f).coerceIn(220.dp, 460.dp)
    val scrollState = rememberScrollState()

    GlassBottomSheet(
        backdrop = PopupBackdropHolder.current,
        show = show,
        title = "发现新版本",
        insideMargin = DpSize(20.dp, 8.dp),
        // 与运行环境检测 / 配置与备份面板一致：顶栏栈式排布，不以浅色带覆盖正文
        progressiveBlur = false,
        endAction = {
            val dismissState = LocalDismissState.current
            IconButton(
                onClick = {
                    onDismiss()
                    dismissState?.invoke()
                },
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "关闭",
                    tint = colorScheme.onBackground,
                )
            }
        },
        onDismissRequest = onDismiss,
    ) { innerPadding ->
        val dismissState = LocalDismissState.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(scrollState)
                    .padding(innerPadding),
            ) {
                Text(
                    text = "v$currentVersionName → v${release.versionName}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                )

                Spacer(Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusTagMiuix(
                        label = release.tagName,
                        backgroundColor = colorScheme.primary.copy(alpha = 0.14f),
                        contentColor = colorScheme.primary,
                        fontSize = 10.sp,
                    )
                    if (sizeText.isNotEmpty()) {
                        StatusTagMiuix(
                            label = sizeText,
                            backgroundColor = colorScheme.secondaryContainer,
                            contentColor = colorScheme.onSecondaryContainer,
                            fontSize = 10.sp,
                        )
                    }
                    if (publishedText.isNotEmpty()) {
                        StatusTagMiuix(
                            label = publishedText,
                            backgroundColor = Color.Transparent,
                            contentColor = colorScheme.onSurfaceVariantSummary,
                            borderColor = colorScheme.outline,
                            fontSize = 10.sp,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 240.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colorScheme.surfaceContainerHigh)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(
                        text = formattedNotes.ifEmpty {
                            "点击下方按钮更新，或前往 GitHub 发布页查看完整变更说明。"
                        },
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }

                if (downloading != null) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = downloading.fraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    val readText = AppUpdateVersionParser.formatFileSize(downloading.bytesRead).ifEmpty { "0 KB" }
                    val totalText = AppUpdateVersionParser.formatFileSize(downloading.totalBytes)
                    val progressDetail = if (totalText.isNotEmpty()) {
                        "已下载 $readText / $totalText"
                    } else {
                        "已下载 $readText"
                    }
                    Text(
                        text = progressDetail,
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                } else if (failed != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = failed.message,
                        fontSize = 12.sp,
                        color = colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            UpdateDialogFooter(
                isDownloading = downloading != null,
                primaryLabel = primaryLabel,
                onCancelDownload = onCancelDownload,
                onDismiss = {
                    onDismiss()
                    dismissState?.invoke()
                },
                onPrimaryAction = onPrimaryAction,
                onIgnoreVersion = {
                    onIgnoreVersion()
                    dismissState?.invoke()
                },
                onOpenReleasePage = onOpenReleasePage,
            )
        }
    }
}

@Composable
private fun UpdateDialogFooter(
    isDownloading: Boolean,
    primaryLabel: String,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
    onPrimaryAction: () -> Unit,
    onIgnoreVersion: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextButton(
            text = if (isDownloading) "取消" else "稍后再说",
            onClick = {
                if (isDownloading) onCancelDownload() else onDismiss()
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        TextButton(
            text = primaryLabel,
            enabled = !isDownloading,
            onClick = onPrimaryAction,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }

    if (!isDownloading) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "忽略此版本",
                fontSize = 12.5.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onIgnoreVersion)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Text(
                text = "前往发布页",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenReleasePage)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}
