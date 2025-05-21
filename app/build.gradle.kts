plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf") version "0.9.4"
}

android {
    namespace = "com.example.budget_predictor_android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.budget_predictor_android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "google/protobuf/field_mask.proto"
            )
        }
    }

    sourceSets["main"].java.srcDir("build/generated/source/proto/main/java")
    sourceSets["main"].java.srcDir("build/generated/source/proto/main/grpc")

}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.23.4"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.57.2"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc") {
                    option("lite")
                }
            }
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}


dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // gRPC Lite
    implementation("io.grpc:grpc-okhttp:1.57.2") {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    implementation("io.grpc:grpc-protobuf-lite:1.57.2")
    implementation("io.grpc:grpc-okhttp:1.57.2")
    implementation("io.grpc:grpc-stub:1.57.2")
    implementation("com.google.protobuf:protobuf-javalite:3.23.4")

    // For javax.annotation.Generated
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // Unit test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

