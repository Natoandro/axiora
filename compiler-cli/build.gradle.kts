plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":content-validation"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

application {
    applicationName = "axiora"
    mainClass.set("mg.abela.axiora.cli.MainKt")
}

tasks.named<JavaExec>("run") { workingDir(rootProject.projectDir) }
