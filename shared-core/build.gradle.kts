import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
}

kotlin {
    android {
        namespace = "com.flarego.core"
        compileSdk = 36
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("app.cash.sqldelight:runtime:2.3.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

sqldelight { databases { create("FlareGoDatabase") { packageName.set("com.flarego.db") } } }

// Coverage gates consume evidence outside the JVM classpath. Metadata/source/schema-only
// changes must invalidate cached test results just like changes to compiled test code.
tasks.named("jvmTest") {
    inputs.files(rootProject.fileTree("docs/cloudflare-audit") {
        include("*.json", "*.csv")
    }).withPropertyName("cloudflareAuditEvidence").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    for (module in listOf("shared-core", "shared-ui", "app", "android-platform")) {
        inputs.dir(rootProject.file("$module/src"))
            .withPropertyName("auditSource-$module")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    }
    // The coverage snapshot also hashes this build file and the gate compares against four
    // immutable review baselines that live outside docs/*.json. Declaring them keeps an ordinary
    // (non-forced) build from staying UP-TO-DATE while one of those inputs drifts.
    inputs.files(
        rootProject.file("app/build.gradle.kts"),
        rootProject.file(
            "docs/cloudflare-audit/evidence/r2-b-2026-10-19/task3/coverage/baseline/review-round-start-2026-10-21.json.gz"
        ),
        rootProject.file(
            "docs/cloudflare-audit/evidence/r2-b-2026-10-19/task4/coverage/baseline/review-round-start-2026-10-21-b2.json.gz"
        ),
        rootProject.file(
            "docs/cloudflare-audit/evidence/r2-b-2026-10-19/task6/coverage/baseline/review-round-start-2026-10-21-b3.json.gz"
        ),
        rootProject.file(
            "docs/cloudflare-audit/evidence/r2-c-2026-10-22/baselines/review-before-c.json.gz"
        ),
    ).withPropertyName("auditExternalFiles")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(project.file("build.gradle.kts"))
        .withPropertyName("auditBuildConfiguration")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    val schema = providers.environmentVariable("SCHEMA_FILE")
        .orElse(rootProject.file(".superpowers/cloudflare/openapi.json").absolutePath)
    inputs.file(schema).withPropertyName("cloudflarePinnedSchema")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.NONE)
}
