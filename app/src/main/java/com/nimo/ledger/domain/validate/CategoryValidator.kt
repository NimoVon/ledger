package com.nimo.ledger.domain.validate

import com.nimo.ledger.domain.model.CategoryKind

/**
 * 分类写入校验用的最小快照：只带校验需要的字段，不依赖 data 层实体。
 *
 * 与 [com.nimo.ledger.domain.model.CategoryRef] 同构，但多带 name / parentId——
 * 分类的规则（同层重名、单层父子）只有站在"整棵树"上才能判断。
 */
data class CategorySnapshot(
    val id: Long,
    val name: String,
    val kind: CategoryKind,
    val parentId: Long?,
    val isArchived: Boolean,
)

/**
 * 分类校验错误。页面把每一个错误翻译成中文再显示（见 ui/categories/CategoryEditDialog.kt）。
 */
sealed interface CategoryValidationError {
    /** 名称去掉首尾空格后为空 */
    data object NameBlank : CategoryValidationError

    /** 名称超过 [CategoryValidator.MAX_NAME_LENGTH] */
    data object NameTooLong : CategoryValidationError

    /** 同一层级（同 kind、同 parentId）下已有同名分类 */
    data object DuplicateName : CategoryValidationError

    /** 指定的上级分类不存在（可能是别的页面刚把它删/导入覆盖了） */
    data object ParentNotFound : CategoryValidationError

    /** 支出与收入是两套独立的树，不能跨树挂载 */
    data object ParentKindMismatch : CategoryValidationError

    /** 计划 F02「单层父子分类」：二级分类下不能再挂子级 */
    data object ParentNotTopLevel : CategoryValidationError

    /** 不能把新分类挂在一个已停用的上级下：那会让它一建出来就不可选 */
    data object ParentArchived : CategoryValidationError
}

class CategoryValidationException(
    val errors: List<CategoryValidationError>,
) : RuntimeException(errors.joinToString("; "))

/**
 * 分类校验（F02）。纯函数：数据由 Repository 查好喂进来，这里不碰数据库。
 *
 * 计划 F02 的规则落在这一层：支出/收入两套独立树（[ParentKindMismatch]）、
 * 单层父子（[ParentNotTopLevel]）、停用不删除（停用走 isArchived，见 [CategoryValidator] 的调用方）。
 */
object CategoryValidator {

    /** 分类名是列表里的短标签：比账户名（20）短一些，太长会把排序按钮挤下去 */
    const val MAX_NAME_LENGTH: Int = 12

    /**
     * 只校验名称本身：给页面做即时提示用。
     * 重名与上级分类要对着全量数据看，只有 Repository 拿得到，因此不在这里判。
     */
    fun validateName(name: String): List<CategoryValidationError> = buildList {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) add(CategoryValidationError.NameBlank)
        if (trimmed.length > MAX_NAME_LENGTH) add(CategoryValidationError.NameTooLong)
    }

    /**
     * 新建 / 改名校验。
     *
     * @param selfId 改名时传被编辑分类的 id（重名检查要排除它自己）；新建传 null。
     * @param all 全量分类（含已停用）：重名要把停用的兄弟算进去——恢复后会直接撞名。
     */
    fun validate(
        name: String,
        kind: CategoryKind,
        parentId: Long?,
        all: List<CategorySnapshot>,
        selfId: Long? = null,
    ): List<CategoryValidationError> = buildList {
        addAll(validateName(name))
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            val duplicated = all.any { other ->
                other.id != selfId &&
                    other.kind == kind &&
                    other.parentId == parentId &&
                    other.name.trim().equals(trimmed, ignoreCase = true)
            }
            if (duplicated) add(CategoryValidationError.DuplicateName)
        }
        if (parentId != null) {
            val parent = all.firstOrNull { it.id == parentId }
            when {
                parent == null -> add(CategoryValidationError.ParentNotFound)
                parent.kind != kind -> add(CategoryValidationError.ParentKindMismatch)
                parent.parentId != null -> add(CategoryValidationError.ParentNotTopLevel)
                parent.isArchived -> add(CategoryValidationError.ParentArchived)
            }
        }
    }
}
