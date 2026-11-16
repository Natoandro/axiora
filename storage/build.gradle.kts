plugins { kotlin("jvm") }

dependencies {
    implementation(project(":learning-core"))
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
}
