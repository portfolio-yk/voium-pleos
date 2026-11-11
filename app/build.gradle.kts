import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Speechmatics API 키는 Git에 올라가지 않는 local.properties에서만 읽습니다.
// 예: speechmatics.apiKey=발급받은_키
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val speechmaticsApiKey = localProperties.getProperty("speechmatics.apiKey", "").trim()
// Pleos Connect 앱 키(대화 기록 AI 요약용). Pleos Playground 콘솔에서 발급받아 local.properties에 넣습니다.
val pleosClientId = localProperties.getProperty("pleos.clientId", "").trim()
val pleosClientSecret = localProperties.getProperty("pleos.clientSecret", "").trim()

android {
    namespace = "com.example.pleos"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.pleos"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SPEECHMATICS_API_KEY", "\"$speechmaticsApiKey\"")
        buildConfigField("String", "PLEOS_CLIENT_ID", "\"$pleosClientId\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"$pleosClientSecret\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // Pleos 차량 정보(좌석·실내 조명·기어)와 내비게이션(목적지·경유지)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navi.helper)
    // 대화 기록 AI 요약 (Pleos LLM)
    implementation(libs.pleos.llm)
    implementation(libs.pleos.stt)
    testImplementation(libs.junit)
    // Android의 org.json은 JVM 단위 테스트에서 동작하지 않아 실제 구현을 추가합니다.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
