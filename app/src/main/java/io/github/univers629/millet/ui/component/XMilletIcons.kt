package io.github.univers629.millet.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * XMillet 自定义 MIUI 风格矢量图标集合。
 *
 * 所有图标统一遵循 Miuix Original 的 24dp 视口光学留白规范（主体落在 [2.6..21.4] 区间，约 18.8dp 高度），
 * 避免 Material 部分图标（如 Icons.Rounded.Security 占满 1.4..22.9）在底栏与列表里显得过大或过重。
 */
object XMilletIcons {

    /** 「应用策略」盾牌图标：圆润实心盾牌 + EvenOdd 镂空勾选（15.4×18.8dp 光学框）。 */
    val AppPolicy: ImageVector by lazy {
        ImageVector.Builder(
            name = "AppPolicy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                // 圆润盾牌外形：X: 4.3..19.7, Y: 2.6..21.4
                moveTo(11.3f, 2.85f)
                quadTo(12f, 2.6f, 12.7f, 2.85f)
                lineTo(18.9f, 5.15f)
                quadTo(19.7f, 5.45f, 19.7f, 6.3f)
                lineTo(19.7f, 11.2f)
                curveTo(19.7f, 15.8f, 16.55f, 19.65f, 12.65f, 21.2f)
                quadTo(12f, 21.45f, 11.35f, 21.2f)
                curveTo(7.45f, 19.65f, 4.3f, 15.8f, 4.3f, 11.2f)
                lineTo(4.3f, 6.3f)
                quadTo(4.3f, 5.45f, 5.1f, 5.15f)
                close()

                // 居中镂空圆角勾选
                moveTo(9.5f, 11.4f)
                lineTo(11.1f, 13.0f)
                lineTo(14.7f, 9.4f)
                quadTo(15.3f, 8.8f, 15.9f, 9.4f)
                quadTo(16.5f, 10.0f, 15.9f, 10.6f)
                lineTo(11.7f, 14.8f)
                quadTo(11.1f, 15.4f, 10.5f, 14.8f)
                lineTo(8.3f, 12.6f)
                quadTo(7.7f, 12.0f, 8.3f, 11.4f)
                quadTo(8.9f, 10.8f, 9.5f, 11.4f)
                close()
            }
        }.build()
    }

    /** 「进程列表」闪电图标：圆角饱满闪电（X: 6.1..17.9, Y: 2.6..21.4）。 */
    val ProcessBolt: ImageVector by lazy {
        ImageVector.Builder(
            name = "ProcessBolt",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(12.6f, 3.2f)
                quadTo(13.3f, 2.3f, 13.7f, 3.4f)
                lineTo(12.8f, 10.6f)
                quadTo(12.7f, 11.1f, 13.2f, 11.1f)
                lineTo(17.1f, 11.1f)
                quadTo(18.2f, 11.1f, 17.5f, 12.0f)
                lineTo(11.1f, 20.8f)
                quadTo(10.3f, 21.7f, 10.0f, 20.6f)
                lineTo(11.1f, 13.3f)
                quadTo(11.2f, 12.8f, 10.7f, 12.8f)
                lineTo(6.9f, 12.8f)
                quadTo(5.8f, 12.8f, 6.5f, 11.9f)
                close()
            }
        }.build()
    }

    /** 主页状态卡「工作中」：描边圆环 + 圆头对勾（还原原项目 110dp 大图标观感，非填充）。 */
    val StatusOkOutline: ImageVector by lazy {
        ImageVector.Builder(
            name = "StatusOkOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(
                "M16.29,7.88L10,14.17l-2.29,-2.29c-0.39,-0.39 -1.02,-0.39 -1.41,0 -0.39,0.39 -0.39,1.02 0,1.41l3,3c0.39,0.39 1.02,0.39 1.41,0L17.7,9.29c0.39,-0.39 0.39,-1.02 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.41,0z" +
                    "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                    "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z"
            ),
            fill = SolidColor(Color.Black),
        ).build()
    }

    /** 主页状态卡「未激活」：描边圆环 + 感叹号（与 [StatusOkOutline] 同规格）。 */
    val StatusErrorOutline: ImageVector by lazy {
        ImageVector.Builder(
            name = "StatusErrorOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(
                "M12,7c0.55,0 1,0.45 1,1v4c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1L11,8c0,-0.55 0.45,-1 1,-1z" +
                    "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
                    "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z" +
                    "M13,17h-2v-2h2v2z"
            ),
            fill = SolidColor(Color.Black),
        ).build()
    }

    /**
     * 运行环境检测雪花：三向贯穿 + 每臂两道 40° 分叉，1.5dp 圆头描边。
     * 主体落在 24dp 视口的 [3.6..20.4] 区间，与 Miuix Original/Glass 图标的 18.8dp 光学框对齐。
     */
    val Snowflake: ImageVector by lazy {
        ImageVector.Builder(
            name = "Snowflake",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                val center = 12f
                val armLength = 8.4f
                val branchFrom = 5.4f
                val branchLength = 2.3f
                val branchAngle = Math.toRadians(40.0)
                for (i in 0 until 3) {
                    val angle = Math.toRadians(60.0 * i)
                    val ux = sin(angle).toFloat()
                    val uy = -cos(angle).toFloat()
                    moveTo(center - ux * armLength, center - uy * armLength)
                    lineTo(center + ux * armLength, center + uy * armLength)
                }
                for (i in 0 until 6) {
                    val angle = Math.toRadians(60.0 * i)
                    val ux = sin(angle).toFloat()
                    val uy = -cos(angle).toFloat()
                    val fromX = center + ux * branchFrom
                    val fromY = center + uy * branchFrom
                    for (sign in intArrayOf(1, -1)) {
                        val turn = sign * branchAngle
                        val vx = ux * cos(turn).toFloat() - uy * sin(turn).toFloat()
                        val vy = ux * sin(turn).toFloat() + uy * cos(turn).toFloat()
                        moveTo(fromX, fromY)
                        lineTo(fromX + vx * branchLength, fromY + vy * branchLength)
                    }
                }
            }
        }.build()
    }

    /**
     * 安卓小人头轮廓图标：半圆头部 + 30° 双触角 + 双圆眼，1.5dp 圆头描边。
     * 主体落在 24dp 视口的 [3.8..20.2, 6.4..18.0] 区间，与 Miuix Glass Regular 图标粗细与留白一致。
     */
    val AndroidHead: ImageVector by lazy {
        ImageVector.Builder(
            name = "AndroidHead",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 半圆头轮廓与底边：圆心 (12, 17.2)，半径 8.2
                moveTo(3.8f, 17.2f)
                arcTo(
                    horizontalEllipseRadius = 8.2f,
                    verticalEllipseRadius = 8.2f,
                    theta = 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 20.2f,
                    y1 = 17.2f,
                )
                close()
                // 左、右触角（相对顶垂线 ±30°）
                moveTo(7.9f, 10.1f)
                lineTo(6.2f, 7.15f)
                moveTo(16.1f, 10.1f)
                lineTo(17.8f, 7.15f)
            }
            path(fill = SolidColor(Color.Black)) {
                // 左眼 (8.8, 13.8)，半径 0.95
                moveTo(9.75f, 13.8f)
                arcTo(0.95f, 0.95f, 0f, true, true, 7.85f, 13.8f)
                arcTo(0.95f, 0.95f, 0f, true, true, 9.75f, 13.8f)
                close()
                // 右眼 (15.2, 13.8)，半径 0.95
                moveTo(16.15f, 13.8f)
                arcTo(0.95f, 0.95f, 0f, true, true, 14.25f, 13.8f)
                arcTo(0.95f, 0.95f, 0f, true, true, 16.15f, 13.8f)
                close()
            }
        }.build()
    }
}