plugins { java }
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies { implementation(gradleApi()); testImplementation("junit:junit:4.13.2") }
