package cc.cardkey.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import cc.cardkey.R
import cc.cardkey.protectedpiv.ProtectedPivAction
import cc.cardkey.protectedpiv.ProtectedPivState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.io.File
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtectedPivLocaleListTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun context(base: Context, tags: String): Context = base.createConfigurationContext(
        Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) })

    private fun entries(file: File): Map<String, Element> {
        val nodes = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val item = nodes.item(index) as Element
            item.getAttribute("name") to item
        }
    }

    @Test fun applicationOwnedChineseCatalogCoversExactlyTheEnglishScope() {
        val english = entries(File("src/main/res/values-en/protected_piv.xml"))
        val chineseFile = File("src/main/res/values-zh/protected_piv.xml")
        assertTrue("The application must declare its own qualified Chinese catalog", chineseFile.isFile)
        val chinese = entries(chineseFile)
        val defaults = requireNotNull(File("src/main/res/values").listFiles()).filter { it.extension == "xml" }
            .flatMap { entries(it).entries }.associate { it.key to it.value }
        assertEquals(170, english.size)
        assertEquals(english.keys, chinese.keys)
        val placeholders = Regex("%[0-9]+\\$[sd]")
        for ((key, translated) in chinese) {
            val original = requireNotNull(defaults[key])
            assertEquals(key, original.textContent, translated.textContent)
            assertEquals(key, original.getAttribute("formatted"), translated.getAttribute("formatted"))
            assertFalse(key, translated.getAttributeNS("http://schemas.android.com/tools", "ignore").contains("MissingTranslation"))
            assertEquals(key, placeholders.findAll(english.getValue(key).textContent).map { it.value }.toList(),
                placeholders.findAll(translated.textContent).map { it.value }.toList())
        }
    }

    @Test fun ownCatalogTagsMatchTheFirstSupportedLocaleWithAndroidLocaleList() {
        val tags = listOf("en", "zh").filter { File("src/main/res/values-$it/protected_piv.xml").isFile }.toTypedArray()
        assertEquals("zh-CN", LocaleList.forLanguageTags("zh-CN,en-US").getFirstMatch(tags)?.toLanguageTag())
        assertEquals("en-US", LocaleList.forLanguageTags("en-US,zh-CN").getFirstMatch(tags)?.toLanguageTag())
    }

    @Test fun chineseFirstLocaleListKeepsChineseForEveryEnglishScopeKey() {
        val app = RuntimeEnvironment.getApplication()
        val defaults = requireNotNull(File("src/main/res/values").listFiles()).filter { it.extension == "xml" }
            .flatMap { entries(it).entries }.associate { it.key to it.value.textContent }
        val chinese = context(app, "zh-CN").resources
        val preferred = context(app, "zh-CN,en-US").resources
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values-en/protected_piv.xml")).getElementsByTagName("string")
        assertEquals(170, nodes.length)
        for (index in 0 until nodes.length) {
            val key = nodes.item(index).attributes.getNamedItem("name").nodeValue
            val id = chinese.getIdentifier(key, "string", app.packageName)
            assertNotEquals(key, 0, id)
            assertEquals("zh-CN: $key", defaults.getValue(key), chinese.getString(id))
            assertEquals("zh-CN,en-US: $key", defaults.getValue(key), preferred.getString(id))
        }
    }

    @Test fun robolectricResourceFallbackMatrixRemainsStable() {
        val app = RuntimeEnvironment.getApplication()
        val english = entries(File("src/main/res/values-en/protected_piv.xml"))
        val chinese = requireNotNull(File("src/main/res/values").listFiles()).filter { it.extension == "xml" }
            .flatMap { entries(it).entries }.associate { it.key to it.value }
        val cases = listOf("en-US" to true, "en-US,zh-CN" to true, "fr-FR" to false,
            "de-DE" to false,
            "fr-FR,zh-CN,en-US" to false, "en-US,fr-FR" to true)
        for ((tags, useEnglish) in cases) {
            val selected = context(app, tags).resources
            val expected = if (useEnglish) english else chinese
            for (key in english.keys) {
                val id = selected.getIdentifier(key, "string", app.packageName)
                assertEquals("$tags: $key", expected.getValue(key).textContent, selected.getString(id))
            }
        }
    }

    private fun render(tags: String, check: () -> Unit) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val localized = context(host.get(), tags)
        host.get().setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ProtectedPivPanel(ProtectedPivState(generation = 1, profileReady = true, awaitingPin = true,
                            issuerSubject = "\u202e", issuerFingerprint = "abc", action = ProtectedPivAction.VERIFY_PIN,
                            retries = 1, erasesKeysOnFailure = true), false, false, 0, {}, {}, { _, _, _ -> }, {})
                    }
                }
            }
        }
        try { check() } finally { host.pause().stop().destroy() }
    }

    @Test fun chineseFirstLocaleListRendersChinesePanelAndExactErasureRisk() {
        render("zh-CN,en-US") {
            compose.onNodeWithText("受保护的 PIV").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("签发者：").assertDoesNotExist()
            compose.onNodeWithText("待执行：验证 PIN · 槽位 9A").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("PIN 仅剩一次重试，PUK 已锁定。此次验证失败将清除 PIV 私钥。确认继续？").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun englishFirstLocaleListKeepsEnglishPanelAndRiskText() {
        render("en-US,zh-CN") {
            compose.onNodeWithText("Protected PIV").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Pending: Verify PIN · slot 9A").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Only one PIN attempt remains and the PUK is blocked. If this verification fails, the PIV private keys will be erased. Continue?").performScrollTo().assertIsDisplayed()
        }
    }
}
