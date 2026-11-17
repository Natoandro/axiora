rootProject.name = "axiora"

include(":content-model", ":content-validation", ":compiler-cli", ":learning-core", ":storage")

if (providers.gradleProperty("androidSdk").isPresent) {
    include(":android-storage")
}
