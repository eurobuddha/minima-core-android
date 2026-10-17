package com.eurobuddha.utxo;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.utxo.UtxoMessagesTest.field;
public class UtxoHistoryMessagesTest {
    @Test public void nestedLocalFailuresTranslateWithoutChangingUnknownDetails() throws Exception {
        Object outer=UtxoMessages.message("Distribute aborted (14 address(es) unfunded): batch 2 failed after 3 retries: could not get a change address");
        assertEquals(R.string.l10n_utxo_distribution_aborted_count,field(outer,"resource"));
        Object[] args=(Object[])field(outer,"arguments");assertEquals("14",args[0]);
        assertEquals(R.string.l10n_utxo_batch_retries_failed,field(args[1],"resource"));
        Object[] inner=(Object[])field(args[1],"arguments");assertEquals("2",inner[0]);assertEquals("3",inner[1]);
        assertEquals(R.string.l10n_utxo_change_unavailable,field(inner[2],"resource"));
        Object unknown=UtxoMessages.message("batch attempt failed: unexpected 中文 {name} $1");
        assertArrayEquals(new Object[]{"unexpected 中文 {name} $1"},(Object[])field(unknown,"arguments"));
    }
    @Test public void actionNamesAndIdentifiersRemainLiteralCaptures() throws Exception {
        Object action=UtxoMessages.message("Consolidate Send 中文 {amount} $1");
        assertEquals(R.string.l10n_utxo_consolidate_action,field(action,"resource"));
        assertArrayEquals(new Object[]{"Send 中文 {amount} $1"},(Object[])field(action,"arguments"));
        String id="0x1234ABCD";
        Object conflict=UtxoMessages.message("Not posted: these coins were spent by a different transaction ("+id+").");
        assertEquals(R.string.l10n_utxo_different_transaction,field(conflict,"resource"));
        assertArrayEquals(new Object[]{id},(Object[])field(conflict,"arguments"));
        for(String raw:new String[]{"Split → name coins","Distribute batch 1 → 4 addrs extra","Distribute: posting batch two (4 addresses)…","Token","Send", "Not posted: these coins were spent by a different transaction (name)."})assertEquals(raw,UtxoMessages.message(raw));
    }
    @Test public void unknownOutcomeAndKnownFailureStayDistinct() throws Exception {
        assertEquals(R.string.l10n_utxo_awaiting_settlement,field(UtxoMessages.message("node reply timed out — awaiting settlement"),"resource"));
        assertEquals(R.string.l10n_utxo_unspent_not_posted,field(UtxoMessages.message("The node didn't answer in time and the coins are still unspent — not posted."),"resource"));
        assertEquals(R.string.l10n_utxo_distribution_final_unknown,field(UtxoMessages.message("Distribute: final batch got no reply from the node in time — see 'posted?' in History."),"resource"));
        assertEquals(R.string.l10n_utxo_distribution_complete,field(UtxoMessages.message("Distribute complete."),"resource"));
    }
    @Test public void reshuffleLabelsPreserveClassificationAndGrossAmounts() throws Exception {
        NodeTx split=new NodeTx();split.direction="self";split.tokenid="0x00";split.tokenName="Minima";split.amount="0";
        split.inputs="[{\"amount\":\"6\",\"tokenid\":\"0x00\"}]";
        split.outputs="[{\"amount\":\"2\",\"tokenid\":\"0x00\"},{\"amount\":\"2\",\"tokenid\":\"0x00\"},{\"amount\":\"2\",\"tokenid\":\"0x00\"}]";
        String inputs=split.inputs,outputs=split.outputs;
        assertTrue(split.isSplit());assertFalse(split.isConsolidation());assertEquals("6  Minima",split.grossDisplay());
        assertEquals(R.plurals.l10n_history_split,field(split.reshuffleLabel(),"resource"));assertEquals(3,field(split.reshuffleLabel(),"count"));
        assertEquals(inputs,split.inputs);assertEquals(outputs,split.outputs);
        NodeTx merge=new NodeTx();merge.direction="self";merge.inputs=outputs;merge.outputs=inputs;
        assertTrue(merge.isConsolidation());assertEquals(R.plurals.l10n_history_consolidation,field(merge.reshuffleLabel(),"resource"));
        assertEquals(3,field(merge.reshuffleLabel(),"count"));
    }
}
