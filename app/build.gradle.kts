plugins { id("com.android.application") }

android {
    namespace = "net.libane.wifidatacontroller"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.libane.wifidatacontroller"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
