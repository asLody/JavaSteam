import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask

plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.dokka)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kotlinter)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.protobuf.gradle)
    id("jacoco")
    id("signing")
    projectversiongen
    steamlanguagegen
    rpcinterfacegen
}

allprojects {
    group = "in.dragonbra"
    version = "1.6.0-SNAPSHOT"
}

repositories {
    mavenCentral()
}

// TODO remove (all this) once kotlinter supports ktlint 1.3.2+ once that's released.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            repositories {
                maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
            }
            force(
                "com.pinterest.ktlint:ktlint-cli-reporter-checkstyle:1.4.0-SNAPSHOT",
                "com.pinterest.ktlint:ktlint-cli-reporter-core:1.4.0-SNAPSHOT",
                "com.pinterest.ktlint:ktlint-cli-reporter-html:1.4.0-SNAPSHOT",
                "com.pinterest.ktlint:ktlint-cli-reporter-json:1.4.0-SNAPSHOT",
                "com.pinterest.ktlint:ktlint-cli-reporter-plain:1.4.0-SNAPSHOT",
                "com.pinterest.ktlint:ktlint-cli-reporter-sarif:1.4.0-SNAPSHOT",
                "com.pinterest.ktlint:ktlint-rule-engine-core:1.4.0-SNAPSHOT",
                "com.pinterest.ktlint:ktlint-rule-engine:1.4.0-SNAPSHOT",
                "com.pinterest.ktlint:ktlint-ruleset-standard:1.4.0-SNAPSHOT"
            )
        }
    }
}
// end to-do

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    withSourcesJar()
}

/* Protobufs */
protobuf.protoc {
    artifact = libs.protobuf.protoc.get().toString()
}

/* Testing */
tasks.test {
    useJUnitPlatform()
    testLogging {
        events = setOf(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
        )
    }
}

/* Test Reporting */
jacoco.toolVersion = libs.versions.jacoco.get()
tasks.jacocoTestReport {
    reports {
        xml.required = false
        html.required = false
    }
}

/* Java-Kotlin Docs */
tasks.dokkaJavadoc {
    dokkaSourceSets {
        configureEach {
            suppressGeneratedFiles.set(false) // Allow generated files to be documented.
            perPackageOption {
                // Deny most of the generated files.
                matchingRegex.set("in.dragonbra.javasteam.(protobufs|enums|generated).*")
                suppress.set(true)
            }
        }
    }
}

// Make sure Maven Publishing gets javadoc
// https://stackoverflow.com/a/71172854
lateinit var javadocArtifact: PublishArtifact
tasks {
    val dokkaHtml by getting(org.jetbrains.dokka.gradle.DokkaTask::class)

    val javadocJar by creating(Jar::class) {
        dependsOn(dokkaHtml)
        archiveClassifier.set("javadoc")
        from(dokkaHtml.outputDirectory)
    }

    artifacts {
        javadocArtifact = archives(javadocJar)
    }
}

/* Configuration */
configurations {
    configureEach {
        // Only allow junit 5
        exclude("junit", "junit")
        exclude("org.junit.vintage", "junit-vintage-engine")
    }
}

/* Source Sets */
sourceSets.main {
    java.srcDirs(
        // builtBy() fixes gradle warning "Execution optimizations have been disabled for task"
        files("build/generated/source/steamd/main/java").builtBy("generateSteamLanguage"),
        files("build/generated/source/javasteam/main/java").builtBy("generateProjectVersion", "generateRpcMethods")
    )
}

/* Dependencies */
tasks["lintKotlinMain"].dependsOn("formatKotlin")
tasks["check"].dependsOn("jacocoTestReport")
tasks["compileJava"].dependsOn("generateSteamLanguage", "generateProjectVersion", "generateRpcMethods")
// tasks["build"].finalizedBy(dokkaJavadocJar)

/* Kotlinter */
tasks.withType<LintTask> {
    this.source = this.source.minus(fileTree("build/generated")).asFileTree
}
tasks.withType<FormatTask> {
    this.source = this.source.minus(fileTree("build/generated")).asFileTree
}

dependencies {
    implementation(libs.commons.io)
    implementation(libs.commons.lang3)
    implementation(libs.commons.validator)
    implementation(libs.gson)
    implementation(libs.kotlin.coroutines)
    implementation(libs.kotlin.stdib)
    implementation(libs.okHttp)
    implementation(libs.xz)
    implementation(libs.protobuf.java)

    testImplementation(libs.bundles.testing)
}

/* Artifact publishing */
nexusPublishing {
    repositories {
        sonatype {
            val ossrhUsername: String by project
            val ossrhPassword: String by project
            username = ossrhUsername
            password = ossrhPassword
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(javadocArtifact)
            pom {
                name = "JavaSteam"
                packaging = "jar"
                description = "Java library to interact with Valve's Steam network."
                url = "https://github.com/Longi94/JavaSteam"
                inceptionYear = "2018"
                scm {
                    connection = "scm:git:git://github.com/Longi94/JavaSteam.git"
                    developerConnection = "scm:git:ssh://github.com:Longi94/JavaSteam.git"
                    url = "https://github.com/Longi94/JavaSteam/tree/master"
                }
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://www.opensource.org/licenses/mit-license.php"
                    }
                }
                developers {
                    developer {
                        id = "Longi"
                        name = "Long Tran"
                        email = "lngtrn94@gmail.com"
                    }
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}
