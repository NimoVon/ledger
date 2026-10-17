package com.nimo.ledger.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 系统的动画倍率（设计规范 §13 AA-7 / §11 批 F 的 F-4）。
 *
 * 设置 → 无障碍 → 移除动画 会把它置 0；开发者选项里的「动画时长缩放」也是同一个键。
 * 0 时应用必须不播转场（也不播将来的骨架淡入），否则对前庭功能敏感的用户看到的就是多余的晃动。
 */
internal const val ANIMATOR_DURATION_SCALE = "animator_duration_scale"

/** 动画倍率 > 0 才播动画。抽成纯函数是为了能脱离真机单测「0 就是不播」这一条。 */
internal fun animationsEnabled(animatorScale: Float): Boolean = animatorScale > 0f

/** 读一次系统动画倍率；读不到时按 1（系统默认）算，宁可按有动画处理也不要误吞动画。 */
internal fun readAnimatorScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, ANIMATOR_DURATION_SCALE, 1f)

/**
 * 读系统「减少动画」偏好，并在它变化时刷新（用户在设置里一开一关，回到应用就立刻生效）。
 *
 * 只监听这一个键；`onChange` 在系统主线程回调，赋值 `mutableStateOf` 是安全的。
 */
@Composable
internal fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(animationsEnabled(readAnimatorScale(context))) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = animationsEnabled(readAnimatorScale(context))
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}
