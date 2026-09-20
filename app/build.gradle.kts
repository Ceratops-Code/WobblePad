plugins {
    id("com.android.application")
}
android {
    namespace = "io.github.ceratops_code.wobblepad"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.ceratops_code.wobblepad"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { aidl = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}

dependencies {
    implementation("no.nordicsemi.android:ble:2.11.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
