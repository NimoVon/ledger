package com.nimo.ledger.data.local

import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.domain.model.CategoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附.4「恢复默认分类」的判重口径：按「同 kind + 同名」补种缺失项，绝不覆盖已有。
 */
class DefaultCategoriesTest {

    @Test
    fun onAnEmptyList_everyPresetCategoryIsMissing() {
        val missing = DefaultCategories.missingFrom(emptyList())

        assertEquals(DefaultCategories.all().size, missing.size)
        assertEquals(DefaultCategories.all().map { it.name }, missing.map { it.name })
    }

    @Test
    fun anExistingCategoryWithTheSameNameIsNeverReAdded() {
        val missing = DefaultCategories.missingFrom(listOf(category("餐饮", CategoryKind.EXPENSE)))

        assertTrue(missing.none { it.name == "餐饮" && it.kind == CategoryKind.EXPENSE })
        // 只是"支出餐饮"算已有，别的照样缺
        assertEquals(DefaultCategories.all().size - 1, missing.size)
    }

    @Test
    fun theSameNameInTheOtherTreeDoesNotCount() {
        // 支出有「餐饮」不代表收入有「餐饮」——两棵树各算各的
        val missing = DefaultCategories.missingFrom(listOf(category("餐饮", CategoryKind.INCOME)))

        val expected = DefaultCategories.all().filterNot { it.name == "餐饮" && it.kind == CategoryKind.INCOME }
        assertEquals(expected.map { it.kind to it.name }, missing.map { it.kind to it.name })
    }

    @Test
    fun matchingIgnoresSurroundingSpacesAndCase() {
        // 用户手建过「 餐饮 」，不该再补一个「餐饮」出来
        val missing = DefaultCategories.missingFrom(listOf(category(" 餐饮 ", CategoryKind.EXPENSE)))

        assertTrue(missing.none { it.kind == CategoryKind.EXPENSE && it.name == "餐饮" })
    }

    @Test
    fun nothingIsMissingWhenEveryPresetCategoryAlreadyExists() {
        val existing = DefaultCategories.all().map { it.copy(id = it.name.hashCode().toLong()) }

        assertEquals(emptyList<CategoryEntity>(), DefaultCategories.missingFrom(existing))
    }

    private fun category(name: String, kind: CategoryKind) =
        CategoryEntity(name = name, kind = kind, sortOrder = 0)
}
