package com.nimo.ledger.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

/** 页面属于哪一类。只决定转场动画，不决定底栏/悬浮按钮（那是 [LedgerRoutes.TOP_LEVEL_ROUTES] 的事）。 */
internal enum class PageKind {
    /** 底部三个 Tab：彼此是平级页，切换不该有方向感 */
    TAB,

    /** 从设置页进入的二级页（账户/分类/预算），以及统计页下钻出来的明细 */
    DETAIL,

    /** 沉浸式表单（记一笔/转账）：从底部压上来，返回时落回去 */
    FORM,
}

/**
 * 路由 → 页面类别。未知路由按 Tab 处理：宁可动画平淡，也不要在导航上抛异常。
 *
 * [route] 是 NavDestination.route，带参数的目的地拿到的是模式串（"record?txId={txId}"），所以下面比对的是模式串。
 */
internal fun pageKindOf(route: String?): PageKind = when {
    route != null && route in LedgerRoutes.TAB_ROUTES -> PageKind.TAB
    route != null && route in LedgerRoutes.DETAIL_ROUTES -> PageKind.DETAIL
    route == LedgerRoutes.TRANSACTIONS_DRILL -> PageKind.DETAIL
    route == LedgerRoutes.RECORD_PATTERN || route == LedgerRoutes.TRANSFER_PATTERN -> PageKind.FORM
    else -> PageKind.TAB
}

/** 一次导航该用哪种转场。抽成枚举是为了让"哪种页面怎么动"能被纯 JVM 测试盯住。 */
internal enum class NavMotionKind {
    /** 平级切换：只淡入淡出 */
    FADE,

    /** 前进/后退一层：横向位移 + 淡入淡出，方向由前进/后退决定 */
    SLIDE_HORIZONTAL,

    /** 表单压上来/落回去：纵向位移 + 淡入淡出 */
    SLIDE_VERTICAL,
}

/**
 * 决定一次导航的转场类别。
 *
 * @param initialRoute 导航发起时所在的页面（pop 时是正在被关掉的那一页）
 * @param targetRoute 要到达的页面（pop 时是被返回到的那一页）
 * @param isPop 这次导航是不是返回（NavHost 的 popEnter/popExit）
 */
internal fun navMotionOf(
    initialRoute: String?,
    targetRoute: String?,
    isPop: Boolean,
): NavMotionKind {
    val initial = pageKindOf(initialRoute)
    val target = pageKindOf(targetRoute)
    return if (isPop) {
        when {
            initial == PageKind.FORM -> NavMotionKind.SLIDE_VERTICAL
            initial == PageKind.DETAIL && target != PageKind.FORM -> NavMotionKind.SLIDE_HORIZONTAL
            else -> NavMotionKind.FADE
        }
    } else {
        when {
            target == PageKind.FORM -> NavMotionKind.SLIDE_VERTICAL
            target == PageKind.DETAIL && initial != PageKind.FORM -> NavMotionKind.SLIDE_HORIZONTAL
            else -> NavMotionKind.FADE
        }
    }
}

/**
 * NavHost 的四个转场。设计口径：
 *
 * - Tab 之间只淡入淡出（平级，不该有方向感），时长比系统默认短；
 * - 二级页前进时从左往右推入 1/4 屏、返回时反向推出，位移幅度比整屏小——既看得出"往里走了一层"，
 *   又不至于像换了个应用；
 * - 表单从底部压上来 1/3 屏，返回时落回去；
 * - 时长 220ms 进 / 180ms 出，比 Navigation 默认的 700ms 快得多，返回不再"拖泥带水"。
 *
 * 动画时长跟随系统"减少动画"设置（动画 scale 为 0 时 Compose 会直接跳到终态）。
 */
internal object LedgerNavMotion {

    const val ENTER_MILLIS = 220
    const val EXIT_MILLIS = 180

    private const val HORIZONTAL_DIVISOR = 4
    private const val VERTICAL_DIVISOR = 3

    fun enter(initialRoute: String?, targetRoute: String?): EnterTransition =
        when (navMotionOf(initialRoute, targetRoute, isPop = false)) {
            NavMotionKind.FADE -> fadeIn(tween(durationMillis = ENTER_MILLIS, easing = FastOutSlowInEasing))
            NavMotionKind.SLIDE_HORIZONTAL -> slideInHorizontally(
                animationSpec = tween(durationMillis = ENTER_MILLIS, easing = FastOutSlowInEasing),
                initialOffsetX = { it / HORIZONTAL_DIVISOR },
            ) + fadeIn(tween(durationMillis = ENTER_MILLIS, easing = FastOutSlowInEasing))
            NavMotionKind.SLIDE_VERTICAL -> slideInVertically(
                animationSpec = tween(durationMillis = ENTER_MILLIS, easing = FastOutSlowInEasing),
                initialOffsetY = { it / VERTICAL_DIVISOR },
            ) + fadeIn(tween(durationMillis = ENTER_MILLIS, easing = FastOutSlowInEasing))
        }

    fun exit(initialRoute: String?, targetRoute: String?): ExitTransition =
        when (navMotionOf(initialRoute, targetRoute, isPop = false)) {
            NavMotionKind.FADE -> fadeOut(tween(durationMillis = EXIT_MILLIS))
            NavMotionKind.SLIDE_HORIZONTAL -> slideOutHorizontally(
                animationSpec = tween(durationMillis = EXIT_MILLIS),
                targetOffsetX = { -it / (HORIZONTAL_DIVISOR * 2) },
            ) + fadeOut(tween(durationMillis = EXIT_MILLIS))
            NavMotionKind.SLIDE_VERTICAL -> fadeOut(tween(durationMillis = EXIT_MILLIS))
        }

    fun popEnter(initialRoute: String?, targetRoute: String?): EnterTransition =
        when (navMotionOf(initialRoute, targetRoute, isPop = true)) {
            NavMotionKind.FADE -> fadeIn(tween(durationMillis = ENTER_MILLIS, easing = FastOutSlowInEasing))
            NavMotionKind.SLIDE_HORIZONTAL -> slideInHorizontally(
                animationSpec = tween(durationMillis = ENTER_MILLIS, easing = FastOutSlowInEasing),
                initialOffsetX = { -it / (HORIZONTAL_DIVISOR * 2) },
            ) + fadeIn(tween(durationMillis = ENTER_MILLIS, easing = FastOutSlowInEasing))
            NavMotionKind.SLIDE_VERTICAL -> fadeIn(tween(durationMillis = ENTER_MILLIS, easing = FastOutSlowInEasing))
        }

    fun popExit(initialRoute: String?, targetRoute: String?): ExitTransition =
        when (navMotionOf(initialRoute, targetRoute, isPop = true)) {
            NavMotionKind.FADE -> fadeOut(tween(durationMillis = EXIT_MILLIS))
            NavMotionKind.SLIDE_HORIZONTAL -> slideOutHorizontally(
                animationSpec = tween(durationMillis = EXIT_MILLIS),
                targetOffsetX = { it / HORIZONTAL_DIVISOR },
            ) + fadeOut(tween(durationMillis = EXIT_MILLIS))
            NavMotionKind.SLIDE_VERTICAL -> slideOutVertically(
                animationSpec = tween(durationMillis = EXIT_MILLIS),
                targetOffsetY = { it / VERTICAL_DIVISOR },
            ) + fadeOut(tween(durationMillis = EXIT_MILLIS))
        }
}
