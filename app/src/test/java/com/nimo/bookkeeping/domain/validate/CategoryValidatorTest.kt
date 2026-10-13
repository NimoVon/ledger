package com.nimo.bookkeeping.domain.validate

import com.nimo.bookkeeping.domain.model.CategoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类校验（F02）：支出/收入两套独立树、单层父子、同层不重名。
 * 纯函数测试，不碰数据库——数据由 Repository 查好喂进来。
 */
class CategoryValidatorTest {

    private fun snapshot(
        id: Long,
        name: String,
        kind: CategoryKind = CategoryKind.EXPENSE,
        parentId: Long? = null,
        isArchived: Boolean = false,
    ) = CategorySnapshot(id = id, name = name, kind = kind, parentId = parentId, isArchived = isArchived)

    // 一棵最小的树：餐饮(1) + 它的子分类 地铁(2)，收入树有一个 工资(3)
    private val tree = listOf(
        snapshot(1L, "餐饮"),
        snapshot(2L, "地铁", parentId = 1L),
        snapshot(3L, "工资", kind = CategoryKind.INCOME),
    )

    private fun validate(
        name: String,
        kind: CategoryKind = CategoryKind.EXPENSE,
        parentId: Long? = null,
        selfId: Long? = null,
        all: List<CategorySnapshot> = tree,
    ) = CategoryValidator.validate(name = name, kind = kind, parentId = parentId, all = all, selfId = selfId)

    @Test
    fun blankName_isRejected() {
        assertEquals(listOf(CategoryValidationError.NameBlank), validate("   "))
    }

    @Test
    fun tooLongName_isRejected() {
        val tooLong = "字".repeat(CategoryValidator.MAX_NAME_LENGTH + 1)

        assertEquals(listOf(CategoryValidationError.NameTooLong), validate(tooLong))
    }

    @Test
    fun nameAtMaxLength_isAccepted() {
        assertTrue(validate("字".repeat(CategoryValidator.MAX_NAME_LENGTH)).isEmpty())
    }

    @Test
    fun duplicateNameInSameLevel_isRejected_ignoringCaseAndSurroundingSpaces() {
        val withEnglish = listOf(snapshot(1L, "Coffee"))

        assertEquals(
            listOf(CategoryValidationError.DuplicateName),
            validate("  coffee  ", all = withEnglish),
        )
    }

    @Test
    fun sameNameUnderAnotherParent_isAllowed() {
        // 餐饮 下已有 地铁；交通 下要再建一个 地铁 是合法的：重名只在同一层级里判
        val all = tree + snapshot(4L, "交通")

        assertTrue(validate("地铁", parentId = 4L, all = all).isEmpty())
    }

    @Test
    fun sameNameInTheOtherTree_isAllowed() {
        // 支出有 餐饮，收入树再建一个 餐饮 也是合法的：两套树互相独立
        assertTrue(validate("餐饮", kind = CategoryKind.INCOME).isEmpty())
    }

    @Test
    fun rename_excludesItselfFromDuplicateCheck() {
        assertTrue(validate("餐饮", selfId = 1L).isEmpty())
        // 但撞到兄弟仍然要拦下来
        assertEquals(
            listOf(CategoryValidationError.DuplicateName),
            validate("地铁", parentId = 1L, selfId = 1L, all = tree + snapshot(4L, "地铁", parentId = 1L)),
        )
    }

    @Test
    fun missingParent_isRejected() {
        assertEquals(listOf(CategoryValidationError.ParentNotFound), validate("新分类", parentId = 999L))
    }

    @Test
    fun parentFromAnotherTree_isRejected() {
        assertEquals(
            listOf(CategoryValidationError.ParentKindMismatch),
            validate("新分类", kind = CategoryKind.EXPENSE, parentId = 3L),
        )
    }

    @Test
    fun secondLevelParent_isRejected() {
        // 计划 F02「单层父子分类」：地铁(2) 已经是二级分类，不能再挂子级
        assertEquals(listOf(CategoryValidationError.ParentNotTopLevel), validate("新分类", parentId = 2L))
    }

    @Test
    fun archivedParent_isRejected() {
        val all = listOf(snapshot(1L, "餐饮", isArchived = true))

        assertEquals(listOf(CategoryValidationError.ParentArchived), validate("新分类", parentId = 1L, all = all))
    }

    @Test
    fun validChild_isAccepted() {
        assertTrue(validate("打车", parentId = 1L).isEmpty())
    }

    @Test
    fun errors_accumulateInsteadOfStoppingAtTheFirst() {
        val errors = validate("   ", parentId = 999L)

        assertEquals(listOf(CategoryValidationError.NameBlank, CategoryValidationError.ParentNotFound), errors)
    }
}
