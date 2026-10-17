package com.eurobuddha.nftwallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;
public class NftMessagesTest {
    static Object field(Object value,String name)throws Exception {Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    @Test public void transportStatesRemainDistinct()throws Exception {
        assertEquals(R.string.l10n_vestr_transport_stopped,field(NftMessages.message("The embedded node is not running. No command was sent."),"resource"));
        assertEquals(R.string.l10n_wallet_error_node_unknown,field(NftMessages.message("Node result unavailable. Outcome unknown."),"resource"));
        assertEquals(R.string.l10n_explorer_node_retry,field(NftMessages.message("NOT_ENABLED"),"resource"));
        assertEquals(R.string.l10n_utxo_rejected,field(NftMessages.message("Node rejected the command."),"resource"));
    }
    @Test public void progressCapturesAreLiteralAndStrict()throws Exception {
        Object value=NftMessages.message("Adding inputs (2/15)…");
        assertEquals(R.string.l10n_utxo_adding_inputs,field(value,"resource"));
        assertArrayEquals(new Object[]{"2","15"},(Object[])field(value,"arguments"));
        value=NftMessages.message("Adding outputs (1/2)…");
        assertEquals(R.string.l10n_utxo_adding_outputs,field(value,"resource"));
        for(String raw:new String[]{"Adding outputs (name/2)…","Adding inputs (2/15)… extra","Send","recipient 中文 {0}$1"})assertEquals(raw,NftMessages.message(raw));
    }
    @Test public void nullAndUnknownFailuresStayLiteral(){assertEquals("",NftMessages.message(null));assertEquals("unknown",NftMessages.error(new Exception("unknown")));}
}
