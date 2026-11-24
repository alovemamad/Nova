package app.novavpn.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringResourcesParityTest {

    private fun resolveAppDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return when {
            File(cwd, "src/main/res/values/strings.xml").exists() -> cwd
            File(cwd, "app/src/main/res/values/strings.xml").exists() -> File(cwd, "app")
            else -> error("Could not locate app module directory from ${cwd.absolutePath}")
        }
    }

    private fun parseStringKeys(xmlFile: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        val nodes = doc.getElementsByTagName("string")
        val keys = linkedSetOf<String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            if (name.isNotBlank()) keys.add(name)
        }
        return keys
    }

    @Test
    fun `values and values-fa strings xml files have matching keys and cover all R_string usages`() {
        val appDir = resolveAppDir()
        val enXml = File(appDir, "src/main/res/values/strings.xml")
        val faXml = File(appDir, "src/main/res/values-fa/strings.xml")

        assertTrue("Missing ${enXml.path}", enXml.exists())
        assertTrue("Missing ${faXml.path}", faXml.exists())

        val enKeys = parseStringKeys(enXml)
        val faKeys = parseStringKeys(faXml)

        val missingInFa = enKeys - faKeys
        val missingInEn = faKeys - enKeys
        assertEquals("Keys present in values/strings.xml but missing in values-fa/strings.xml", emptySet<String>(), missingInFa)
        assertEquals("Keys present in values-fa/strings.xml but missing in values/strings.xml", emptySet<String>(), missingInEn)

        // Check every R.string.<key> reference in Kotlin source files exists in both string resource files
        val javaRoot = File(appDir, "src/main/java")
        val regex = Regex("""R\.string\.([a-zA-Z0-9_]+)""")
        val usedKeys = linkedSetOf<String>()
        javaRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                regex.findAll(file.readText()).forEach { match ->
                    usedKeys.add(match.groupValues[1])
                }
            }

        val missingUsedEn = usedKeys - enKeys
        val missingUsedFa = usedKeys - faKeys
        assertEquals("R.string keys used in code missing from values/strings.xml", emptySet<String>(), missingUsedEn)
        assertEquals("R.string keys used in code missing from values-fa/strings.xml", emptySet<String>(), missingUsedFa)
    }

    @Test
    fun `every component class declared in AndroidManifest exists in source tree`() {
        val appDir = resolveAppDir()
        val manifest = File(appDir, "src/main/AndroidManifest.xml")
        assertTrue("Missing AndroidManifest.xml", manifest.exists())

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val pkg = "com.example"
        val javaRoot = File(appDir, "src/main/java")

        val componentTags = listOf("application", "activity", "service", "receiver", "provider")
        for (tag in componentTags) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val rawName = el.getAttribute("android:name")
                if (rawName.isBlank()) continue
                val fqcn = if (rawName.startsWith(".")) pkg + rawName else rawName
                val relPath = fqcn.replace('.', '/')
                val ktExists = File(javaRoot, "$relPath.kt").exists()
                val javaExists = File(javaRoot, "$relPath.java").exists()
                assertTrue("Manifest class $fqcn not found in src/main/java", ktExists || javaExists)
            }
        }
    }
}
