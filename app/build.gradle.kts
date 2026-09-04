import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun getAndIncrementBuildNumber(): Int {
    val buildNumFile = file("build_number.properties")
    val props = Properties()
    var buildNumber = 0
    if (buildNumFile.exists()) {
        try {
            FileInputStream(buildNumFile).use { props.load(it) }
            buildNumber = props.getProperty("build.number", "0").toInt()
        } catch (e: Exception) {
            buildNumber = 0
        }
    }
    
    // Check if we are running an actual packaging or deployment task
    val isBuilding = gradle.startParameter.taskNames.any {
        it.contains("assemble", ignoreCase = true) || 
        it.contains("bundle", ignoreCase = true) || 
        it.contains("install", ignoreCase = true)
    }
    
    if (isBuilding) {
        buildNumber++
        props.setProperty("build.number", buildNumber.toString())
        try {
            FileOutputStream(buildNumFile).use { props.store(it, "Auto-incremented build number") }
        } catch (e: Exception) {
            // Ignore
        }
    } else if (buildNumber == 0) {
        buildNumber = 1 // default starting number
    }
    
    return buildNumber
}

val currentBuildNumber = getAndIncrementBuildNumber()

android {
    namespace = "com.nullroute"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nullroute"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    val hasReleaseKeystore = keystorePropsFile.exists()
    if (hasReleaseKeystore) {
        FileInputStream(keystorePropsFile).use { keystoreProperties.load(it) }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile", "nullroute-release.jks"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8" // compatible with Kotlin 1.9.22
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            output.outputFileName = "NullRoute-${variant.versionName}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Billing
    implementation("com.android.billingclient:billing:8.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
}
