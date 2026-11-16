plugins { kotlin("jvm") }

dependencies {
    implementation(project(":content-model"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}
