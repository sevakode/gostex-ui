package dev.gostex.desktop

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class StringsTest {
    @Test fun `explicit English overrides Russian system fallback`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            assertEquals("Settings", Strings("en")["settings"])
            assertEquals("Настройки", Strings("system")["settings"])
            assertEquals("Ajustes", Strings("es")["settings"])
        } finally { Locale.setDefault(previous) }
    }
}
