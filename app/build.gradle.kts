plugins {
    alias(libs.plugins.android.application)
}

// 签名复用本机现成 keystore（与 classapp 同一套），不复制任何文件
android {
    namespace = "me.livephoto.assist"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "me.livephoto.assist"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "2.9.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("/mnt/TY/android/android-project/WIfikeyXposed/wifikeyxposed.keystore")
            storePassword = "tyopxn360"
            keyAlias = "tyopxn360"
            keyPassword = "tyopxn360"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // libxposed 模块元数据必须打包进 APK，其余资源全部排除
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.libxposed.api)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
