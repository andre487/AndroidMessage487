package life.andre.message487

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class ResourceContractTest {
    private fun document(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File("src/main/$path"))
    private fun strings(locale: String): Map<String, String> {
        val nodes = document("res/$locale/strings.xml").getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val element = nodes.item(it) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    @Test fun `English and Russian resources have matching keys and format arguments`() {
        val english = strings("values")
        val russian = strings("values-ru")
        assertEquals(english.keys, russian.keys)
        val placeholder = Regex("%([0-9]+\\$)?[a-zA-Z]")
        english.forEach { (name, value) ->
            assertTrue("Empty translation: $name", russian.getValue(name).isNotBlank())
            assertEquals(name, placeholder.findAll(value).map { it.value }.sorted().toList(),
                placeholder.findAll(russian.getValue(name)).map { it.value }.sorted().toList())
        }
    }

    @Test fun `exported components are permission protected and report provider is private`() {
        val manifest = document("AndroidManifest.xml")
        val namespace = "http://schemas.android.com/apk/res/android"
        fun elements(tag: String): List<Element> = manifest.getElementsByTagName(tag).let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        assertEquals(listOf(".MainActivity"), elements("activity")
            .filter { it.getAttributeNS(namespace, "exported") == "true" }.map { it.getAttributeNS(namespace, "name") })
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            elements("service").single().getAttributeNS(namespace, "permission"))
        assertEquals("android.permission.BROADCAST_SMS", elements("receiver").single().getAttributeNS(namespace, "permission"))
        val provider = elements("provider").single()
        assertEquals("false", provider.getAttributeNS(namespace, "exported"))
        assertEquals("true", provider.getAttributeNS(namespace, "grantUriPermissions"))
        assertEquals("false", elements("application").single().getAttributeNS(namespace, "allowBackup"))
        assertFalse(elements("uses-permission").any { it.getAttributeNS(namespace, "name") == "android.permission.QUERY_ALL_PACKAGES" })
        val paths = document("res/xml/file_paths.xml").documentElement.childNodes
        val roots = (0 until paths.length).mapNotNull { paths.item(it) as? Element }
        assertEquals(1, roots.size)
        assertEquals("cache-path", roots.single().tagName)
        assertEquals("feedback/", roots.single().getAttribute("path"))
    }
}
