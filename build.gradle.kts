import buildlogic.GitVersionSource
import buildlogic.WriteVersionInfo

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
}

val gitVersion = providers.of(GitVersionSource::class) {
    parameters.repository.set(layout.projectDirectory)
}
extra["gitVersion"] = gitVersion.get().split("|")
tasks.register<WriteVersionInfo>("versionInfo") {
    versionFields.set(gitVersion)
    outputFile.set(layout.buildDirectory.file("version.json"))
}
