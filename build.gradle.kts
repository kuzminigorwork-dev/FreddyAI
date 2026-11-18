plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// Необязательно: -Pfreddy.buildRoot=D:/build выносит папку сборки в короткий путь без кириллицы.
val buildRoot = providers.gradleProperty("freddy.buildRoot").orNull
if (buildRoot != null) {
    allprojects { layout.buildDirectory.set(file("$buildRoot/${project.name}")) }
}
