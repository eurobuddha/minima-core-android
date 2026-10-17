package com.eurobuddha.mail;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;
/** Existing diagnostic pattern: exact local messages, literal unknown details, bounded recursion. */
public class MailMessagesTest {
    static Object field(Object value,String name)throws Exception {Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    @Test public void stoppedNodeAndUnknownOutcomeRemainDifferent()throws Exception {
        assertEquals(R.string.l10n_vestr_transport_stopped,field(MailMessages.message("The embedded node is not running. No command was sent."),"resource"));
        assertEquals(R.string.l10n_wallet_error_node_unknown,field(MailMessages.message("Node result unavailable. Outcome unknown."),"resource"));
        assertEquals(R.string.l10n_mail_node_retry,field(MailMessages.message("NOT_ENABLED"),"resource"));
    }
    @Test public void cryptoFailureKeepsLiteralDetailsAndBoundsRecursiveMessages()throws Exception {
        String raw="Copy 中文 Русский Українська {count}$1";
        Object value=MailMessages.message("encrypt failed: "+raw);
        assertEquals(R.string.l10n_mail_encrypt_failed,field(value,"resource"));
        assertArrayEquals(new Object[]{raw},(Object[])field(value,"arguments"));
        value=MailMessages.message("encrypt failed: ".repeat(32)+raw);
        int depth=0;
        while(value instanceof Language.Message){value=((Object[])field(value,"arguments"))[0];depth++;}
        Object nested=MailMessages.message("encrypt failed: seal error: sign failed");
        nested=((Object[])field(nested,"arguments"))[0];assertEquals(R.string.l10n_mail_seal_error,field(nested,"resource"));
        nested=((Object[])field(nested,"arguments"))[0];assertEquals(R.string.l10n_mail_sign_failed,field(nested,"resource"));
        assertEquals(8,depth);assertTrue(((String)value).endsWith(raw));
    }
    @Test public void arbitraryNamesAndDiagnosticsAreNotTranslationKeys(){
        for(String raw:new String[]{"Send","Settings","0x1212","Invalid amount.","recipient error 中文 {0}$1"})assertEquals(raw,MailMessages.message(raw));
        assertEquals("",MailMessages.message(null));
        assertEquals("unknown failure",MailMessages.error(new Exception("unknown failure")));
    }
}
