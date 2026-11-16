import java.util.Properties

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.serialization)
	// FCM (ADR-022): android/app/google-services.json gerekir (depoya girmez, .gitignore).
	alias(libs.plugins.google.services)
}

/**
 * API kökü öncelik sırası: -Pgks.apiBaseUrl=... > local.properties > gradle.properties (emülatör varsayılanı).
 * Değer derlemeye BuildConfig.API_BASE_URL olarak girer; uygulama kodunda adres sabit yazılmaz.
 */
val apiBaseUrl: String = run {
	val local = Properties().apply {
		rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
	}
	val fromCommandLine = gradle.startParameter.projectProperties["gks.apiBaseUrl"]
	val value = fromCommandLine ?: local.getProperty("gks.apiBaseUrl") ?: providers.gradleProperty("gks.apiBaseUrl").get()
	require(value.startsWith("http://") || value.startsWith("https://")) { "gks.apiBaseUrl http(s) ile başlamalı: $value" }
	if (value.endsWith("/")) value else "$value/"
}

android {
	namespace = "org.gks.android"
	compileSdk = 34

	defaultConfig {
		applicationId = "org.gks.android"
		minSdk = 26
		targetSdk = 34
		versionCode = 1
		versionName = "0.1.0"
		buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
		// Acil alarm sesi (ADR-022): sürekli (ısrarlı) ses süresi ve sonrasındaki kısa hatırlatma aralığı, dakika.
		// Değiştirmek için: -Pgks.alertInsistentMinutes=… / gradle.properties.
		buildConfigField("int", "ALERT_INSISTENT_MINUTES",
			(providers.gradleProperty("gks.alertInsistentMinutes").orNull ?: "10"))
		buildConfigField("int", "ALERT_REMINDER_MINUTES",
			(providers.gradleProperty("gks.alertReminderMinutes").orNull ?: "3"))
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
		kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
	}
	packaging {
		resources {
			excludes += "/META-INF/{AL2.0,LGPL2.1}"
		}
	}
	testOptions {
		unitTests.all {
			it.maxParallelForks = 1
			it.maxHeapSize = "512m"
		}
	}
}

dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.androidx.navigation.compose)
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.ui)
	implementation(libs.compose.ui.tooling.preview)
	implementation(libs.compose.material3)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.retrofit)
	implementation(libs.retrofit.kotlinx.serialization)
	implementation(libs.okhttp)
	implementation(platform(libs.firebase.bom))
	implementation(libs.firebase.messaging)
	debugImplementation(libs.compose.ui.tooling)

	testImplementation(libs.junit)
	testImplementation(libs.okhttp.mockwebserver)
	testImplementation(libs.kotlinx.coroutines.test)
}
