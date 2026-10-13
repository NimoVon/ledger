package com.nimo.bookkeeping.domain.model

/**
 * 同级列表里的移动方向（F02 分类排序：上移 / 下移一格）。
 *
 * 排序只在"同 kind、同 parentId 的兄弟之间"发生：一级分类和二级分类各自成组，
 * 上移不会把某个二级分类送到它父级的上面去。
 */
enum class MoveDirection {
    UP,
    DOWN,
}
