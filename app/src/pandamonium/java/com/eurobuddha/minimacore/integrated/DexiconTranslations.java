package com.eurobuddha.minimacore.integrated;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dexicon in Pandamonium's language (owner, 14 Nov 2026). The dictionaries in assets/dexicon-l10n/&lt;language&gt;.json map
 * Dexicon's English texts to 简体中文 / Русский / Українська - exact texts, and templates such as "Cancel order {0}?" for
 * texts built from parts (scripts/dexicon-strings.py extracts them, scripts/dexicon-l10n-build.py validates the
 * translations). Anything not in the dictionary stays English. Display only: Dexicon never reads a label back.
 */
final class DexiconTranslations implements com.eurobuddha.dexicon.HostMode.Translator {
    private static final Pattern SLOT = Pattern.compile("\\{(\\d+)\\}");

    private static final class Template {
        final Pattern english; final int[] slots; final String translated;
        Template(Pattern english, int[] slots, String translated) { this.english = english; this.slots = slots; this.translated = translated; }
    }

    private final java.util.function.Supplier<String> language;
    private final java.util.function.Function<String, String> source;   // language -> dictionary JSON, or null
    private String loaded = "";
    private Map<String, String> exact = new HashMap<>();
    private final Map<String, List<Template>> byPrefix = new HashMap<>();
    private final List<Template> openStart = new ArrayList<>();
    private final Map<String, String> cache = new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 2048; }
    };

    DexiconTranslations(Context context) {
        this(com.eurobuddha.minimacore.i18n.Language::code, lang -> {
            try (InputStream in = context.getApplicationContext().getAssets().open("dexicon-l10n/" + lang + ".json")) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception missing) { return null; }
        });
    }

    DexiconTranslations(java.util.function.Supplier<String> language, java.util.function.Function<String, String> source) {
        this.language = language; this.source = source;
    }

    @Override public synchronized String translate(String english) {
        String lang = language.get();
        if ("en".equals(lang)) return null;
        if (!lang.equals(loaded)) load(lang);
        String hit = cache.get(english);
        if (hit != null) return hit.isEmpty() ? null : hit;
        String out = lookup(english, 0);
        cache.put(english, out == null ? "" : out);
        return out;
    }

    private String lookup(String english, int depth) {
        String r = exact.get(english);
        if (r != null) return r;
        if (depth > 1 || english.isEmpty()) return null;
        List<Template> candidates = new ArrayList<>(openStart);
        for (int k = 1; k <= Math.min(4, english.length()); k++) {
            List<Template> l = byPrefix.get(english.substring(0, k));
            if (l != null) candidates.addAll(l);
        }
        for (Template t : candidates) {
            Matcher m = t.english.matcher(english);
            if (!m.matches()) continue;
            String out = t.translated;
            for (int g = 0; g < t.slots.length; g++) {
                String part = m.group(g + 1);
                String inner = part.matches("(?s).*[A-Za-z]{2}.*") ? lookup(part, depth + 1) : null;   // "Failed: " + an English reason
                out = out.replace("{" + t.slots[g] + "}", inner != null ? inner : part);
            }
            return out;
        }
        return null;
    }

    private void load(String lang) {
        loaded = lang; exact = new HashMap<>(); byPrefix.clear(); openStart.clear(); cache.clear();
        try {
            String json = source.apply(lang);
            if (json == null) return;
            JSONObject all = new JSONObject(json);
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String en = it.next(), tr = all.optString(en, "");
                if (tr.isEmpty() || tr.equals(en)) continue;
                if (!SLOT.matcher(en).find()) { exact.put(en, tr); continue; }
                StringBuilder re = new StringBuilder("^");
                List<Integer> slots = new ArrayList<>();
                Matcher m = SLOT.matcher(en);
                int at = 0;
                while (m.find()) {
                    re.append(Pattern.quote(en.substring(at, m.start()))).append("(.+?)");
                    slots.add(Integer.parseInt(m.group(1)));
                    at = m.end();
                }
                re.append(Pattern.quote(en.substring(at))).append('$');
                int[] s = new int[slots.size()];
                for (int i = 0; i < s.length; i++) s[i] = slots.get(i);
                Template t = new Template(Pattern.compile(re.toString(), Pattern.DOTALL), s, tr);
                String lead = en.substring(0, en.indexOf('{'));
                if (lead.isEmpty()) openStart.add(t);
                else byPrefix.computeIfAbsent(lead.substring(0, Math.min(4, lead.length())), k -> new ArrayList<>()).add(t);
            }
        } catch (Exception missing) {
            android.util.Log.w("DexiconL10n", "no " + lang + " dictionary: " + missing);
        }
    }
}
