plugins {
    kotlin("jvm") version "2.2.20" apply false
}

allprojects {
    group = "mg.abela.axiora"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
