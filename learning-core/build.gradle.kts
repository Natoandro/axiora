plugins { kotlin("jvm") }

dependencies {
    implementation(project(":content-model"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}
