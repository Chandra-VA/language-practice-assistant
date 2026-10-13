import java.util.Properties

plugins {
    id("com.android.application")
}

val wordmatchSigningFile = rootProject.file(
    providers.gradleProperty("wordmatch.signingPropertiesFile")
        .getOrElse(".signing/signing.properties")
)
val wordmatchSigningProperties = Properties().apply {
    if (wordmatchSigningFile.isFile) {
        wordmatchSigningFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = wordmatchSigningFile.isFile
if (providers.gradleProperty("wordmatch.requireReleaseSigning").orNull == "true" && !hasReleaseSigning) {
    throw GradleException("Release signing was required but no local signing configuration was provided")
}
if (hasReleaseSigning) {
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
        require(!wordmatchSigningProperties.getProperty(key).isNullOrBlank()) {
            "Missing signing property: $key"
        }
    }
}

android {
    namespace = "com.wordmatch.assist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wordmatch.assist"
        minSdk = 23
        targetSdk = 36
        versionCode = 63
        versionName = "4.1.0"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) create("wordmatchRelease") {
            storeFile = wordmatchSigningFile.parentFile.resolve(wordmatchSigningProperties.getProperty("storeFile"))
            storePassword = wordmatchSigningProperties.getProperty("storePassword")
            keyAlias = wordmatchSigningProperties.getProperty("keyAlias")
            keyPassword = wordmatchSigningProperties.getProperty("keyPassword")
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Public/CI checkouts build without private keys. Never substitute a
            // debug key for a missing release key; that would break update identity.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("wordmatchRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
