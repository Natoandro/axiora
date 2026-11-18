plugins { kotlin("jvm") }

val androidJar = file("${providers.gradleProperty("androidSdk").get()}/platforms/android-33/android.jar")
sourceSets.main { java.srcDir(rootProject.file("spikes/android-math/renderer")) }

dependencies {
    implementation(project(":android-storage"))
    implementation(project(":storage"))
    implementation(project(":learning-core"))
    implementation(project(":content-model"))
    implementation(project(":content-validation"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    compileOnly(files(androidJar))
}

configurations.runtimeClasspath.configure { exclude(group = "org.xerial", module = "sqlite-jdbc") }

tasks.register<Sync>("stageAndroidRuntime") {
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("android-runtime"))
}
