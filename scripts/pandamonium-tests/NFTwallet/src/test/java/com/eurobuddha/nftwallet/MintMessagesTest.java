package com.eurobuddha.nftwallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftMessagesTest.field;
/** Covers the boundaries between translated diagnostics and literal public/user data. */
public class MintMessagesTest {
    @Test public void nestedValidationKeepsSeparateResources() throws Exception {
        Object value=MintMessages.message("Name: required.");
        assertEquals(R.string.l10n_nft_mint_field_name,field(value,"resource"));
        Object inner=((Object[])field(value,"arguments"))[0];
        assertEquals(R.string.l10n_nft_mint_required,field(inner,"resource"));
    }
    @Test public void notificationNamesStayLiteralEvenWhenTheyMatchPhaseNames() throws Exception {
        Object value=MintMessages.message("CREATE · stamped 01 of 20");
        assertEquals(R.string.l10n_nft_mint_service_progress,field(value,"resource"));
        assertArrayEquals(new Object[]{"CREATE","01","20"},(Object[])field(value,"arguments"));
        value=MintMessages.message("Collection DONE -> STAMP");
        Object[] args=(Object[])field(value,"arguments");
        assertEquals("DONE",args[0]);assertEquals(R.string.l10n_nft_mint_stamp_phase,field(args[1],"resource"));
    }
    @Test public void reservedMetadataNamesAndTransactionIdsStayLiteral() throws Exception {
        Object value=MintMessages.message("“CREATE” is a reserved field — use the form inputs for it.");
        assertArrayEquals(new Object[]{"CREATE"},(Object[])field(value,"arguments"));
        value=MintMessages.message("unbalanced txn DONE");
        assertArrayEquals(new Object[]{"DONE"},(Object[])field(value,"arguments"));
    }
    @Test public void numericPatternsRejectMalformedAndTrailingPayloads() throws Exception {
        Object value=MintMessages.message("Waiting for tokencreate confirmation (001/32 blocks)");
        assertArrayEquals(new Object[]{"001","32"},(Object[])field(value,"arguments"));
        for(String raw:new String[]{"Waiting for tokencreate confirmation (one/32 blocks)","Item #2 extra","CREATE 中文 Україна $1 {0}","unknown failure"})assertEquals(raw,MintMessages.message(raw));
        assertEquals("",MintMessages.message(null));
    }
    @Test public void recursionIsBoundedAndTransportFallbackRemainsRecognized() throws Exception {
        Object value=MintMessages.message("Failed: ".repeat(20)+"required.");
        for(int i=0;i<8;i++){assertTrue(value instanceof Language.Message);value=((Object[])field(value,"arguments"))[0];}
        assertEquals("Failed: ".repeat(12)+"required.",value);
        assertEquals(R.string.l10n_vestr_transport_stopped,field(MintMessages.message("The embedded node is not running. No command was sent."),"resource"));
    }
}
