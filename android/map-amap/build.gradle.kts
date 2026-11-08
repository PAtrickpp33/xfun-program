plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}
android {
    namespace = "com.didi.dimina.map.amap"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":dimina"))
    implementation(libs.androidx.activity.compose)
    implementation("com.amap.api:3dmap-location-search:11.2.100_loc11.2.100_sea9.8.1")
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.property("DIMINA_GROUP_ID") as String
                artifactId = "xfun-map-amap"
                version = project.property("DIMINA_VERSION") as String
                pom {
                    name.set("Dimina AMap Adapter")
                    description.set("Optional AMap native map provider for Dimina")
                    url.set("https://github.com/didi/dimina")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                }
            }
        }
    }
}
