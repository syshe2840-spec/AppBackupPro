plugins {
    id("com.android.application")
}

android {
    namespace = "com.appbackup.pro"
    compileSdk = 34                          // ⬅️ از 33 به 34
    
    defaultConfig {
        applicationId = "com.appbackup.pro"
        minSdk = 26                          // ⬅️ از 21 به 26
        targetSdk = 34                       // ⬅️ از 33 به 34
        versionCode = 1
        versionName = "1.0"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17    // ⬅️ از 11 به 17
        targetCompatibility = JavaVersion.VERSION_17    // ⬅️ از 11 به 17
    }

    buildTypes {
        release {
            isMinifyEnabled = false                     // ⬅️ از true به false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.9.0")
    
    // ⬇️⬇️⬇️ این سه تا خط رو اضافه کن ⬇️⬇️⬇️
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.github.topjohnwu.libsu:io:5.2.2")
}