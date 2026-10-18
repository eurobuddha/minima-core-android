package com.eurobuddha.pandadex;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DexMessagesTest {
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);
        Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    @Test public void declaredApplicationLabelsUseTheirOwnResources()throws Exception {
        JSONObject fixed=new JSONObject(new String(Files.readAllBytes(Path.of("../../localization/dex-messages.json")), java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("fixed");
        assertEquals(330,fixed.length());
        for(Iterator<String> i=fixed.keys();i.hasNext();) {
            String raw=i.next(),id="l10n_"+fixed.getString(raw).replaceAll("([a-z0-9])([A-Z])","$1_$2").replace('.','_').toLowerCase(Locale.ROOT);
            assertEquals(raw,R.string.class.getField(id).getInt(null),field(DexMessages.message(raw),"resource"));
        }
    }
    @Test public void realNodeLabelsAndUncertainWriteDoNotBecomeSuccessfulResults()throws Exception {
        assertEquals(R.string.l10n_dex_connecting,field(DexMessages.message(MainActivity.nodeLabel(false,false)),"resource"));
        assertEquals(R.string.l10n_dex_node_not_ready,field(DexMessages.message(MainActivity.nodeLabel(false,true)),"resource"));
        assertEquals(R.string.l10n_dex_node_ready,field(DexMessages.message(MainActivity.nodeLabel(true,true)),"resource"));
        assertEquals(R.string.l10n_dex_write_uncertain,field(DexMessages.message(NodeApi.ERR_WRITE_UNCERTAIN),"resource"));
    }
    @Test public void timestampedKnownLogMessagesTranslateAndUnknownPayloadsStayWhole()throws Exception {
        Object known=DexMessages.logLine("01:02:03  "+NodeApi.ERR_WRITE_UNCERTAIN);
        Object[] args=(Object[])field(known,"arguments");assertEquals("01:02:03  ",args[0]);
        assertEquals(R.string.l10n_dex_write_uncertain,field(args[1],"resource"));
        String raw="node: 0x"+"ab".repeat(64)+" 00012.34000 中文 Русский Українська $1 {id}\nNODE ✓";
        Object unknown=DexMessages.logLine("01:02:03  "+raw);
        assertArrayEquals(new Object[]{"01:02:03  ",raw},(Object[])field(unknown,"arguments"));
        assertEquals(raw,DexMessages.message(raw));assertEquals("unstamped",DexMessages.logLine("unstamped"));
    }
    @Test public void completedLogDisplayIsAnImmutableSnapshot()throws Exception {
        String literal="14:02:03  unknown transaction 0x123";
        Language.TextBuilder builder=new Language.TextBuilder().append(DexMessages.logLine(literal));
        Object snapshot=builder.build();builder.append("later event");
        Object[] parts=(Object[])field(snapshot,"arguments");assertEquals(1,parts.length);
        assertArrayEquals(new Object[]{"14:02:03  ","unknown transaction 0x123"},(Object[])field(parts[0],"arguments"));
    }
}
