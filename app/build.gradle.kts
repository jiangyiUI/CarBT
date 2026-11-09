plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.carbluetooth"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.carbluetooth"
        minSdk = 28
        targetSdk = 28          // 与车机 Android 9 对齐
        versionCode = 20
        versionName = "3.0"
    }

    signingConfigs {
        create("platform") {
            storeFile = file("platform.jks")
            storePassword = "123456"   // 密钥库密码
            keyAlias = "test"          // 密钥别名
            keyPassword = "123456"     // 密钥密码
        }
    }

    lint {
        // 系统签名应用无需运行时权限检查；targetSdk 28 是有意为之
        disable += listOf("MissingPermission", "ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = false
    }

    buildTypes {
        debug {
            // debug 包也用平台签名，方便直接 Run 到车机
            signingConfig = signingConfigs.getByName("platform")
        }
        release {
            optimization {
                signingConfig = signingConfigs.getByName("platform")
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }

    // 自定义输出 APK 文件名：应用名-版本号.apk
    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("手机互联助手-${output.versionName.get()}.apk")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}