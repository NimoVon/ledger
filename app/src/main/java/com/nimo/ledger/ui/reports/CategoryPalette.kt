package com.nimo.ledger.ui.reports

import androidx.compose.ui.graphics.Color

/**
 * 分类图表配色（批 D）：环形图与「支出排行」的占比条共用这一套。
 *
 * 一条硬约束：**同一个分类在任何月份、任何主题下都是同一个颜色**。
 * 旧实现按"金额排名"取色（`donutColorAt(index)`），排名一换颜色就跳——用户既没法把
 * "橙色那块"和"橙色那条"对上号，也记不住自己常看的分类是什么颜色。
 *
 * 10 个色值取自 `docs/配色整改-架构.md`：浅色与深色**共用同一套**（不做两套），
 * 实测每个颜色对浅色 surface `#F5FAFF` 与深色 surface `#0E1418` 都 ≥3:1
 * （[CategoryPaletteTest] 守着，改色值会红）。另一条同样的硬约束：
 * 图表底必须直接铺在 `surface` 上，别放进更亮的卡片——浅卡 `#EFF6FD` 会把最差色压到 3.35，
 * 深卡 `#1A232A` 直接掉到 2.99。
 */
internal val CATEGORY_SLOT_COLORS: List<Color> = listOf(
    Color(0xFF157F84),
    Color(0xFF268CB8),
    Color(0xFF2C6FC4),
    Color(0xFF7F72CE),
    Color(0xFF9450A3),
    Color(0xFFD74664),
    Color(0xFFAB5334),
    Color(0xFFA0794C),
    Color(0xFF697832),
    Color(0xFF42982D),
)

/** 一套色板有几个色位。分类多于它时只能复用颜色（见 [resolveCategorySlots]） */
internal const val CATEGORY_SLOT_COUNT: Int = 10

/**
 * 分类 id → 色位（0..9）。
 *
 * 乘 7 取模是为了让相邻 id 落到不同色位：默认分类是连续插入的，
 * `id % 10` 会让"餐饮 1 / 交通 2"这种邻居看起来像同一族，而 7 的步长在 10 个槽里是满周期。
 * 取模结果在 Kotlin 里跟随被除数符号，这里自己归一，不依赖语言细节。
 */
internal fun categorySlotFor(categoryId: Long): Int {
    val slot = (categoryId * 7L + 3L) % CATEGORY_SLOT_COUNT
    return ((slot + CATEGORY_SLOT_COUNT) % CATEGORY_SLOT_COUNT).toInt()
}

/**
 * 一张图内避免撞色：按 categoryId 升序分配（**与传入顺序无关**，所以环形图与排行条
 * 各自算一遍也一定得到同一张表），撞了就线性往后找空位。
 *
 * 分类超过 10 个时不再有空位可找：退回基础色位。同色可以接受——名字与金额仍然可读，
 * 真正要看的是排行里的数字，不是颜色。
 */
internal fun resolveCategorySlots(categoryIds: Collection<Long>): Map<Long, Int> {
    val taken = BooleanArray(CATEGORY_SLOT_COUNT)
    val slots = LinkedHashMap<Long, Int>(categoryIds.size)
    for (id in categoryIds.distinct().sorted()) {
        var slot = categorySlotFor(id)
        if (taken[slot]) {
            val free = (1 until CATEGORY_SLOT_COUNT)
                .firstOrNull { !taken[(slot + it) % CATEGORY_SLOT_COUNT] }
            if (free != null) slot = (slot + free) % CATEGORY_SLOT_COUNT
        }
        taken[slot] = true
        slots[id] = slot
    }
    return slots
}
