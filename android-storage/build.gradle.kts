plugins { kotlin("jvm") }

val androidJar = file("${providers.gradleProperty("androidSdk").get()}/platforms/android-33/android.jar")

dependencies {
    implementation(project(":storage")) {
        exclude(group = "org.xerial", module = "sqlite-jdbc")
    }
    compileOnly(files(androidJar))
}

tasks.register<Sync>("stageAndroidRuntime") {
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("android-runtime"))
}

val probe by sourceSets.creating

dependencies {
    add(probe.implementationConfigurationName, sourceSets.main.get().output)
    add(probe.implementationConfigurationName, project(":content-model"))
    add(probe.implementationConfigurationName, project(":learning-core"))
    add(probe.implementationConfigurationName, project(":storage"))
    add(probe.compileOnlyConfigurationName, files(androidJar))
}

val probeJar by tasks.registering(Jar::class) {
    archiveClassifier.set("probe")
    from(probe.output)
}

tasks.named<Sync>("stageAndroidRuntime") {
    from(probeJar)
}
