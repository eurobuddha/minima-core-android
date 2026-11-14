package com.eurobuddha.minimacore.integrated;

import static org.junit.Assert.*;

import org.junit.Test;

/** Dexicon in Pandamonium's language: exact texts, templates (parts filled back in, English parts translated too), and
 *  English whenever the dictionary has nothing. */
public class DexiconTranslationsTest {
    private final String[] lang = {"ru"};
    private final DexiconTranslations t = new DexiconTranslations(() -> lang[0], l -> "ru".equals(l)
            ? "{\"Swap\":\"Обмен\",\"Cancel order {0}?\":\"Отменить ордер {0}?\",\"Failed: {0}\":\"Ошибка: {0}\","
              + "\"Outcome is unknown\":\"Исход неизвестен\",\"{0} of {1} sent\":\"отправлено {0} из {1}\"}"
            : null);

    @Test public void exactTemplatedNestedAndFallback() {
        assertEquals("Обмен", t.translate("Swap"));
        String coin = "0x5A11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";
        assertEquals("identifiers pass through whole", "Отменить ордер " + coin + "?", t.translate("Cancel order " + coin + "?"));
        assertEquals("an English part is translated too", "Ошибка: Исход неизвестен", t.translate("Failed: Outcome is unknown"));
        assertEquals("отправлено 2 из 5", t.translate("2 of 5 sent"));
        assertNull("unknown text stays English", t.translate("Something new"));
        lang[0] = "en";
        assertNull("English: nothing is translated", t.translate("Swap"));
        lang[0] = "uk";
        assertNull("no dictionary: English", t.translate("Swap"));
    }
}
