package com.nimo.ledger.ui.theme

/**
 * 外观偏好（F12 深色模式）。
 *
 * 只有三种可能：跟着系统走（默认，与 F12 之前的行为完全一致），或者手动钉死浅色 / 深色。
 * 这里刻意不写「自动」以外的第四种状态：手动钉死后系统怎么变都不再影响界面。
 *
 * @param storageValue 落盘用的稳定字符串。**不要**改——改了会让老用户的偏好回落成跟随系统。
 * @param label 设置页展示的名字
 */
enum class ThemePreference(val storageValue: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    ;

    /**
     * 这一偏好最终要不要深色。
     *
     * @param systemDark 系统当前的深色开关（Compose 的 isSystemInDarkTheme()）。
     *   手动的两种取值只看自己，系统值仅对 [SYSTEM] 有意义。
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /**
         * 落盘值的反序列化。认不出来（null / 空串 / 大小写不符 / 老版本残留的别的值）
         * 一律回落到 [SYSTEM]：偏好文件不是数据，宁可回到默认也不许崩。
         */
        fun fromStorage(raw: String?): ThemePreference =
            entries.firstOrNull { it.storageValue == raw } ?: SYSTEM
    }
}
