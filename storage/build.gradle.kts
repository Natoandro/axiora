plugins { kotlin("jvm") }

dependencies {
    implementation(project(":learning-core"))
    implementation(project(":content-model"))
    implementation(project(":content-validation"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}
