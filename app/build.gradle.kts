import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    jacoco
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 发布签名材料：keystore/keystore.properties 与 .jks 都在 .gitignore 里，绝不提交。
// 文件不存在时（别人 clone、CI）不注册 signingConfig，release 构建退化成未签名包，
// debug 构建与全部单元测试不受影响。
val releaseKeystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.exists()) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.nimo.ledger"
    compileSdk = 37

    signingConfigs {
        if (releaseKeystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // Robolectric JVM 测试（计划 6.1：DAO/约束用真实 SQLite 语义测）
    // JDK 17+ 模块系统要求 add-opens，否则 FileDescriptor 反射失败
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Compose UI Test（Robolectric）要渲染整棵语义树，默认 512m 堆会 OOM
                test.maxHeapSize = "4g"
                test.extensions.configure(JacocoTaskExtension::class.java) {
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
                test.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.annotation=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.nio=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/java.time=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                    "--add-opens=java.base/sun.security.jca=ALL-UNNAMED",
                    "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED",
                )
            }
        }
    }

    defaultConfig {
        // 应用身份（安装标识）。注意：它和源码命名空间 com.nimo.ledger 是两回事，
        // 后者只决定 R/BuildConfig 的包名与源码目录，改名等于全量重排源文件，此处不动。
        applicationId = "com.nimo.ledger"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // 有 keystore/keystore.properties 就用发布密钥签名；没有就是未签名包（装不上）
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Room schema 导出入库（计划 4.2 规则 6：迁移测试的前提，首次建库必须打开）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)

    debugImplementation("androidx.compose.ui:ui-tooling")
    // ui-test-manifest 提供 createComposeRule() 需要的宿主 Activity（仅 debug/测试变体）
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}

// 覆盖率：统计单元测试对 main 源码的行覆盖（报告见 app/build/reports/jacoco/jacocoTestReport/）
tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    val appClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
    classDirectories.setFrom(
        files(appClasses).asFileTree.matching {
            exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/*_Factory.*", "**/*_Impl.*")
        }
    )
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
}

// 覆盖率门禁（计划 8.1：核心逻辑覆盖率 80% 以上、domain/stats 100%）。
// 阈值按实测值留余量，避开 ±4 行的运行抖动；低于阈值时构建失败并打印违规明细。
tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    group = "verification"
    description = "核心逻辑覆盖率门禁：低于阈值即构建失败"
    dependsOn("testDebugUnitTest")
    val appClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
    classDirectories.setFrom(
        files(appClasses).asFileTree.matching {
            exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/*_Factory.*", "**/*_Impl.*")
        }
    )
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    violationRules {
        // 金额/统计/筛选/校验：计划点名 100% 或 80% 以上，实测 100%，留 5 个点余量
        rule {
            element = "PACKAGE"
            includes = listOf(
                "com.nimo.ledger.domain.stats*",
                "com.nimo.ledger.domain.validate*",
                "com.nimo.ledger.domain.filter*",
                "com.nimo.ledger.domain.model*",
                "com.nimo.ledger.util*",
            )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
        }
        // 备份/导入导出/仓库：实测 92.8% ~ 94.3%，下限 85%
        rule {
            element = "PACKAGE"
            includes = listOf(
                "com.nimo.ledger.domain.backup*",
                "com.nimo.ledger.data.repository*",
                "com.nimo.ledger.data.backup*",
            )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
        // DAO 与数据库约束：实测 89.8%，下限 85%
        rule {
            element = "PACKAGE"
            includes = listOf("com.nimo.ledger.data.local.dao*")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
        // 总体下限：防止用低覆盖新代码稀释核心分层（实测 65.6%，下限 60%）
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}
