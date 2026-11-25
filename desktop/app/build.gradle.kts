import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.9.3"
}

group = "com.enweilo.fujishokudo"
version = (System.getenv("APP_VERSION") ?: "1.0.0")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

// The parts shared with the Android app: recipe data, camera tables, PTP, writing recipes and RAW baking.
val sharedSources = tasks.register<Sync>("sharedSources") {
    from("../../app/src/main/java") {
        include(
            "com/enweilo/fujirecipes/data/Recipe.kt",
            "com/enweilo/fujirecipes/data/Tables.kt",
            "com/enweilo/fujirecipes/data/RecipeSync.kt",
            "com/enweilo/fujirecipes/camera/PtpCore.kt",
            "com/enweilo/fujirecipes/camera/FujiOps.kt",
            "com/enweilo/fujirecipes/camera/RawBake.kt",
        )
    }
    into(layout.buildDirectory.dir("shared"))
}

kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir(sharedSources) }
}

compose.desktop {
    application {
        mainClass = "com.enweilo.fujishokudo.MainKt"
        jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "FujiShokudo"
            packageVersion = "1.0.0"
            description = "富士食堂"
            vendor = "EnWeiLo"
            includeAllModules = true
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "富士食堂"
                shortcut = true
            }
        }
    }
}

tasks.register<JavaExec>("renderPreview") {
    dependsOn("classes")
    mainClass.set("com.enweilo.fujishokudo.PreviewKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(layout.buildDirectory.dir("preview").get().asFile.absolutePath) + (project.findProperty("rafDir")?.toString()?.let { listOf(it) } ?: emptyList())
    jvmArgs("-Djava.awt.headless=true")
}

