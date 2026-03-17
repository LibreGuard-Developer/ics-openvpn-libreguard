import com.android.build.gradle.api.LibraryVariant
import java.util.Locale
import org.gradle.api.tasks.compile.JavaCompile

/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    id("checkstyle")
}

android {
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    namespace = "de.blinkt.openvpn"
    compileSdk = 35
    //compileSdkPreview = "UpsideDownCake"

    // Also update runcoverity.sh
    ndkVersion = "28.0.13004108"

    defaultConfig {
        manifestPlaceholders += mapOf("applicationId" to "de.blinkt.openvpn")
        minSdk = 21
        // Fallback for ${applicationId} used in library manifest (overridden by base app)
        externalNativeBuild {
            cmake {
                //arguments+= "-DCMAKE_VERBOSE_MAKEFILE=1"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = File("${projectDir}/src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "build/ovpnassets")
        }
        create("ui") { }
        create("skeleton") { }
        getByName("debug") { }
        getByName("release") { }
    }

    lint {
        enable += setOf("BackButton", "EasterEgg", "StopShip", "IconExpectedSize", "GradleDynamicVersion", "NewerVersionAvailable")
        checkOnly += setOf("ImpliedQuantity", "MissingQuantity")
        disable += setOf("MissingTranslation", "UnsafeNativeCodeLocation")
    }

    flavorDimensions += listOf("implementation", "ovpnimpl")

    productFlavors {
        create("ui") { dimension = "implementation" }
        create("skeleton") { dimension = "implementation" }
        create("ovpn23") { dimension = "ovpnimpl"; buildConfigField("boolean", "openvpn3", "true") }
        create("ovpn2") { dimension = "ovpnimpl"; buildConfigField("boolean", "openvpn3", "false") }
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    // Do not build UI flavor variants in this module
}

androidComponents {
    // Disable all variants that include the implementation=ui flavor
    beforeVariants(selector().withFlavor("implementation" to "ui")) { variantBuilder ->
        variantBuilder.enable = false
    }
}

var swigcmd = "swig"
if (file("/opt/homebrew/bin/swig").exists())
    swigcmd = "/opt/homebrew/bin/swig"
else if (file("/usr/local/bin/swig").exists())
    swigcmd = "/usr/local/bin/swig"

fun registerGenTask(variantName: String, variantDirName: String): File {
    val baseDir = File(buildDir, "generated/source/ovpn3swig/${variantDirName}")
    val genDir = File(baseDir, "net/openvpn/ovpn3")
    tasks.register<Exec>("generateOpenVPN3Swig${variantName}") {
        doFirst { mkdir(genDir) }
        commandLine(listOf(swigcmd, "-outdir", genDir, "-outcurrentdir", "-c++", "-java", "-package", "net.openvpn.ovpn3",
            "-Isrc/main/cpp/openvpn3/client", "-Isrc/main/cpp/openvpn3/",
            "-DOPENVPN_PLATFORM_ANDROID",
            "-o", "${genDir}/ovpncli_wrap.cxx", "-oh", "${genDir}/ovpncli_wrap.h",
            "src/main/cpp/openvpn3/client/ovpncli.i"))
        inputs.files("src/main/cpp/openvpn3/client/ovpncli.i")
        outputs.dir(genDir)
    }
    return baseDir
}

fun sanitizeAidlJavaFileHeader(file: File) {
    val original = file.readText()
    val pkgIdx = original.indexOf("\npackage ")
    val headEnd = if (pkgIdx >= 0) pkgIdx else original.indexOf("package ")
    val fixed = if (headEnd > 0) {
        val head = original.substring(0, headEnd).replace('\\', '/').replace("\r\n", "\n")
        head + original.substring(headEnd)
    } else {
        val start = original.indexOf("/*")
        val end = original.indexOf("*/", startIndex = if (start >= 0) start else 0)
        if (start == 0 && end > start) {
            val head = original.substring(0, end + 2).replace('\\', '/').replace("\r\n", "\n")
            head + original.substring(end + 2)
        } else original
    }
    if (fixed != original) file.writeText(fixed)
}

fun registerSanitizeAidlTask(variant: LibraryVariant) {
    val capName = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    val aidlRootDir = File(buildDir, "generated/aidl_source_output_dir")
    val sanitizeTask = tasks.register("sanitizeAidlUnicode${capName}") {
        doLast {
            if (aidlRootDir.exists()) {
                fileTree(aidlRootDir).matching { include("**/*.java") }.files.forEach { f ->
                    sanitizeAidlJavaFileHeader(f)
                }
            }
        }
    }
    val aidlTaskName = "compile${capName}Aidl"
    val javacTaskName = "compile${capName}JavaWithJavac"
    tasks.matching { it.name == aidlTaskName }.configureEach { finalizedBy(sanitizeTask) }
    tasks.matching { it.name == javacTaskName }.configureEach {
        dependsOn(sanitizeTask)
        doFirst {
            if (aidlRootDir.exists()) {
                fileTree(aidlRootDir).matching { include("**/*.java") }.files.forEach { f ->
                    sanitizeAidlJavaFileHeader(f)
                }
            }
        }
    }
}

android.libraryVariants.all(object : Action<LibraryVariant> {
    override fun execute(variant: LibraryVariant) {
        val sourceDir = registerGenTask(variant.name, variant.baseName.replace("-", "/"))
        val task = tasks.named("generateOpenVPN3Swig${variant.name}").get()
        @Suppress("DEPRECATION")
        variant.registerJavaGeneratingTask(task, sourceDir)
        registerSanitizeAidlTask(variant)
    }
})

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    uiImplementation(libs.android.view.material)
    uiImplementation(libs.androidx.activity)
    uiImplementation(libs.androidx.activity.ktx)
    uiImplementation(libs.androidx.appcompat)
    uiImplementation(libs.androidx.cardview)
    uiImplementation(libs.androidx.viewpager2)
    uiImplementation(libs.androidx.constraintlayout)
    uiImplementation(libs.androidx.core.ktx)
    uiImplementation(libs.androidx.fragment.ktx)
    uiImplementation(libs.androidx.lifecycle.runtime.ktx)
    uiImplementation(libs.androidx.lifecycle.viewmodel.ktx)
    uiImplementation(libs.androidx.preference.ktx)
    uiImplementation(libs.androidx.recyclerview)
    uiImplementation(libs.androidx.security.crypto)
    uiImplementation(libs.androidx.webkit)
    uiImplementation(libs.kotlin)
    uiImplementation(libs.mpandroidchart)
    uiImplementation(libs.square.okhttp)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin)
    testImplementation(libs.mockito.core)
    testImplementation(libs.robolectric)
}

fun DependencyHandler.uiImplementation(dependencyNotation: Any): Dependency? =
    add("uiImplementation", dependencyNotation)

// Globally ensure AIDL header sanitization runs before any Java compilation
tasks.withType(JavaCompile::class.java).configureEach {
    doFirst {
        val aidlRootDir = File(project.buildDir, "generated/aidl_source_output_dir")
        if (aidlRootDir.exists()) {
            fileTree(aidlRootDir).matching { include("**/*.java") }.files.forEach { f ->
                sanitizeAidlJavaFileHeader(f)
            }
        }
    }
}
