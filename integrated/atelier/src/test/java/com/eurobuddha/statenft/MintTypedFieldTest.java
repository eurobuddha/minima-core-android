package com.eurobuddha.statenft;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The field-level check names the field and the character, on the typed pieces, before the JSON is built. */
public class MintTypedFieldTest {
    @Test public void theFirstForbiddenCharacterIsNamed() {
        assertEquals("]", StateNft.badChar("Nice art] amount:1"));
        assertEquals("[", StateNft.badChar("a[b"));
        assertEquals("{", StateNft.badChar("a{b"));
        assertEquals("}", StateNft.badChar("a}b"));
        assertEquals("\"", StateNft.badChar("say \"hi\""));
        assertEquals("\\", StateNft.badChar("back\\slash"));
        assertEquals(";", StateNft.badChar("a;b"));
        for (String control : new String[]{"a\nb", "a\tb", "a\rb", "a\u0000b", "a\u007fb"})
            assertEquals("line break / control character", StateNft.badChar(control));
        for (String ok : new String[]{null, "", "Painter, Kyiv — oil & ink (2026)", "Україна 中文", "50% <b> 'q' a:b #1 @x"})
            assertNull(ok, StateNft.badChar(ok));
    }

    @Test public void theFieldIsNamedForKeysValuesTraitsAndAttributes() throws Exception {
        assertNull(StateNft.metaProblem(StateNft.nftMetadata("n", "d", "<artimage>AA</artimage>", "o", "", "", new JSONArray().put(new JSONObject().put("trait_type", "Mood").put("value", "calm")))));
        assertEquals("name: ]", StateNft.metaProblem(new JSONObject().put("name", "Nice art] amount:1")));
        assertEquals("description: \\", StateNft.metaProblem(new JSONObject().put("name", "n").put("description", "a\\b")));
        assertEquals("value: \"", StateNft.metaProblem(new JSONObject().put("attributes", new JSONArray().put(new JSONObject().put("trait_type", "Mood").put("value", "say \"hi\"")))));
        assertEquals("1 key: [", StateNft.metaProblem(new JSONObject().put("traits", new JSONObject().put("1", new JSONObject().put("Mo[od", "x")))));
        assertEquals("metadata key: ;", StateNft.metaProblem(new JSONObject().put("a;b", "x")));
        assertNull("numbers and booleans are the app's own", StateNft.metaProblem(new JSONObject().put("size", 12).put("nft", true)));
    }

    /** A collection row saved by an older version re-enters the engine without the screen's check. */
    @Test public void aStoredCollectionRowIsCheckedBeforeItsCommandIsBuilt() throws Exception {
        JSONObject row = new JSONObject().put("name", "Lots").put("description", "ten lots").put("mode", "embed").put("size", 10)
                .put("creatorpk", "0x" + "ab".repeat(32)).put("webvalidate", "https://x.example/v.txt");
        JSONObject traits = new JSONObject().put("1", new JSONObject().put("Mood", "calm"));
        assertNull(MintEngine.unsafeCreate(StateNft.tokenMetadata(MintEngine.metaFromRow(row)).put("traits", traits), row));
        for (String bad : MintCommandInjectionTest.EVIL) {
            for (String field : new String[]{"name", "description", "webvalidate", "creatorpk"}) {
                JSONObject evil = new JSONObject(row.toString()).put(field, row.getString(field) + bad);
                String refusal = MintEngine.unsafeCreate(StateNft.tokenMetadata(MintEngine.metaFromRow(evil)), evil);
                assertNotNull(field + " " + bad, refusal);
                assertTrue(refusal, refusal.contains(field) && refusal.contains("Nothing was sent"));
            }
            JSONObject evilTraits = new JSONObject().put("1", new JSONObject().put("Mood", bad));
            assertNotNull("trait " + bad, MintEngine.unsafeCreate(StateNft.tokenMetadata(MintEngine.metaFromRow(row)).put("traits", evilTraits), row));
        }
        JSONObject url = new JSONObject(row.toString()).put("mode", "url").put("base", "https://x.example/a]b/").put("ext", ".png");
        assertNotNull(MintEngine.unsafeCreate(StateNft.tokenMetadata(MintEngine.metaFromRow(url)), url));
        url.put("base", "https://x.example/a/").put("ext", ".p\"ng");
        assertNotNull(MintEngine.unsafeCreate(StateNft.tokenMetadata(MintEngine.metaFromRow(url)), url));
    }
}
