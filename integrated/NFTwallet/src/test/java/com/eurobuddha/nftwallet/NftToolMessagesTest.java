package com.eurobuddha.nftwallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftMessagesTest.field;
public class NftToolMessagesTest {
    @Test public void nestedLocalFailuresTranslateWithoutChangingUnknownDetails() throws Exception {
        Object outer=NftMessages.message("Distribute aborted (14 address(es) unfunded): batch 2 failed after 3 retries: could not get a change address");
        assertEquals(R.string.l10n_utxo_distribution_aborted_count,field(outer,"resource"));
        Object[] args=(Object[])field(outer,"arguments");assertEquals("14",args[0]);
        assertEquals(R.string.l10n_utxo_batch_retries_failed,field(args[1],"resource"));
        Object[] inner=(Object[])field(args[1],"arguments");assertEquals("2",inner[0]);assertEquals("3",inner[1]);
        assertEquals(R.string.l10n_utxo_change_unavailable,field(inner[2],"resource"));
        Object unknown=NftMessages.message("batch attempt failed: unexpected 中文 {name} $1");
        assertArrayEquals(new Object[]{"unexpected 中文 {name} $1"},(Object[])field(unknown,"arguments"));
    }
    @Test public void actionNamesAndIdentifiersRemainLiteralCaptures() throws Exception {
        Object action=NftMessages.message("Consolidate Send 中文 {amount} $1");
        assertEquals(R.string.l10n_utxo_consolidate_action,field(action,"resource"));
        assertArrayEquals(new Object[]{"Send 中文 {amount} $1"},(Object[])field(action,"arguments"));
        String id="0x1234ABCD";
        Object conflict=NftMessages.message("Not posted: these coins were spent by a different transaction ("+id+").");
        assertEquals(R.string.l10n_utxo_different_transaction,field(conflict,"resource"));
        assertArrayEquals(new Object[]{id},(Object[])field(conflict,"arguments"));
        for(String raw:new String[]{"Split → name coins","Distribute batch 1 → 4 addrs extra","Distribute: posting batch two (4 addresses)…","Token","Send", "Not posted: these coins were spent by a different transaction (name)."})assertEquals(raw,NftMessages.message(raw));
    }
    @Test public void unknownOutcomeAndKnownFailureStayDistinct() throws Exception {
        assertEquals(R.string.l10n_utxo_awaiting_settlement,field(NftMessages.message("node reply timed out — awaiting settlement"),"resource"));
        assertEquals(R.string.l10n_utxo_unspent_not_posted,field(NftMessages.message("The node didn't answer in time and the coins are still unspent — not posted."),"resource"));
        assertEquals(R.string.l10n_utxo_distribution_final_unknown,field(NftMessages.message("Distribute: final batch got no reply from the node in time — see 'posted?' in History."),"resource"));
        assertEquals(R.string.l10n_utxo_distribution_complete,field(NftMessages.message("Distribute complete."),"resource"));
    }
}
