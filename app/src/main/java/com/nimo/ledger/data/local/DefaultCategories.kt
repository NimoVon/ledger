package com.nimo.ledger.data.local

import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.domain.model.CategoryKind

/**
 * 预置分类（计划 3.2）。首次启动且分类表为空时播种；
 * 恢复备份后分类表非空，不会重复播种。
 */
object DefaultCategories {

    private val expense = listOf(
        "餐饮" to "🍜",
        "交通" to "🚌",
        "购物" to "🛍️",
        "居住" to "🏠",
        "通讯" to "📱",
        "娱乐" to "🎮",
        "医疗" to "💊",
        "教育" to "📚",
        "人情" to "🎁",
        "订阅" to "🔁",
        "其他" to "📋",
    )

    private val income = listOf(
        "工资" to "💼",
        "奖金" to "🏆",
        "副业" to "💻",
        "理财收益" to "📈",
        "报销" to "🧾",
        "退款" to "↩️",
        "其他" to "📋",
    )

    fun all(): List<CategoryEntity> =
        expense.map { (name, icon) -> CategoryEntity(name = name, kind = CategoryKind.EXPENSE, icon = icon) } +
            income.map { (name, icon) -> CategoryEntity(name = name, kind = CategoryKind.INCOME, icon = icon) }

    /**
     * 相对 [existing] 缺失的预置分类（附.4「恢复默认分类」按钮用）。
     *
     * 判重按「同 kind + 同名」，忽略首尾空格与大小写：同名分类不管是谁建的、是否已停用，
     * 都算"已经有"——恢复默认是加法，绝不覆盖、改名或复活用户主动停用的分类。
     */
    fun missingFrom(existing: List<CategoryEntity>): List<CategoryEntity> {
        val present = existing.mapTo(mutableSetOf()) { it.kind to it.name.trim().lowercase() }
        return all().filterNot { (it.kind to it.name.trim().lowercase()) in present }
    }
}
