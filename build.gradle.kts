// 根构建脚本：只声明插件版本，不 apply（AGP 9 内置 Kotlin，无需 kotlin-android 插件）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
