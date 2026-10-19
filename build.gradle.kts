plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// Reuse Android Studio's SVG importer from the already-installed Android Gradle Plugin.
tasks.register("convertBrandingVectors") {
    doLast {
        val directory = file(providers.gradleProperty("brandingVectorDir").get())
        directory.listFiles { file -> file.extension == "svg" }!!.sortedBy { it.name }.forEach { svg ->
            val output = java.io.ByteArrayOutputStream()
            val errors = com.android.ide.common.vectordrawable.Svg2Vector.parseSvgToXml(svg.toPath(), output)
            check(errors.isBlank()) { "Cannot convert ${svg.name}:\n$errors" }
            directory.resolve(svg.nameWithoutExtension + ".xml").writeBytes(output.toByteArray())
        }
    }
}
