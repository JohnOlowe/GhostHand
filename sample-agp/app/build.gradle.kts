plugins { id("com.android.application") }

android {
    namespace = "com.example.agpproject"   // <- the manifest no longer carries this
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
