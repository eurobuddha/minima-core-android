package com.eurobuddha.utxo;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.utxo.DistributeResumeTest.*;

/** Saved plans are an admission boundary, not permission to infer a successful payment. */
public class DistributeJobValidationTest {
    interface Change { void apply(JSONObject value) throws Exception; }
    void rejected(Change change) throws Exception {
        try(Flow f=new Flow()) {
            DistributeManager original=f.start(15);original.close();
            JSONObject saved=new JSONObject(f.prefs.values.get("job"));change.apply(saved);
            String raw=saved.toString();f.prefs.values.put("job",raw);
            DistributeJob loaded=DistributeJob.load(f.act);assertNotNull(loaded);assertTrue(loaded.invalid||loaded.legacy);
            DistributeManager resumed=f.manager();resumed.onCoinsUpdated();assertTrue(resumed.isBusy());assertFalse(resumed.reserve());
            assertEquals(raw,f.prefs.values.get("job"));assertEquals(1,f.builds.size());assertEquals(1,f.commands.size());
        }
    }
    @Test public void missingTypedFieldsNeverDefaultIntoAnExecutableJob() throws Exception {
        String[] keys={"schema","tokenid","tokenName","per","remaining","batchesDone","maxBatches","expectedChangeCoinId","nextChangeAddr","expectedChangeAmt","waiting","uncertain","atBlock","retryCount","currentInternalId","lastConfirmedId","pendingRecipients","batchIntent"};
        for(String key:keys)rejected(j->j.remove(key));
        for(String key:new String[]{"schema","batchesDone","maxBatches","atBlock","retryCount","pendingRecipients"})rejected(j->j.put(key,"1"));
        for(String key:new String[]{"waiting","uncertain"})rejected(j->j.put(key,"true"));
        rejected(j->j.put("per",1));rejected(j->j.put("remaining",new JSONArray().put(5)));
    }
    @Test public void invalidBoundsOrCommandValuesCannotReachAResumedBuilder() throws Exception {
        for(Object[] item:new Object[][]{{"schema",3},{"batchesDone",-1},{"batchesDone",5},{"maxBatches",0},{"maxBatches",5},{"atBlock",-1},{"atBlock",2147483648L},{"retryCount",5},{"pendingRecipients",15},{"pendingRecipients",0},{"per","0"},{"per","-1"},{"per","1;send"},{"tokenid","0x00;send"},{"currentInternalId","bad;id"},{"expectedChangeCoinId","0x00"}})rejected(j->j.put((String)item[0],item[1]));
        rejected(j->j.put("remaining",new JSONArray(java.util.Collections.nCopies(57,RECIPIENT))));
    }
    @Test public void alteredRecipientPrefixTokenOrBatchEconomicsStayUnresolved() throws Exception {
        rejected(j->j.getJSONArray("remaining").put(0,ADDRESS));
        rejected(j->j.put("per","2"));
        rejected(j->j.put("tokenid","0x11"));
        rejected(j->j.put("pendingRecipients",13));
        rejected(j->j.put("waiting",false));
        rejected(j->j.put("remaining",new JSONArray(java.util.Collections.nCopies(31,RECIPIENT))));
        // A balanced but burning intent is still not an authorized distribution plan.
        rejected(j->{JSONObject plan=j.getJSONObject("batchIntent");plan.getJSONArray("outputs").getJSONObject(14).put("amount","15");plan.put("burn","1");});
    }
    @Test public void validRoundTripPreservesUnicodeAndRepeatedRecipientsWithoutAliasing() throws Exception {
        try(Flow f=new Flow()) {
            f.start(15);DistributeJob saved=DistributeJob.load(f.act);saved.tokenName="熊 · Ведмідь · Медведь";assertTrue(saved.save(f.act));
            DistributeJob copy=DistributeJob.load(f.act);assertEquals(saved.tokenName,copy.tokenName);assertEquals(15,copy.remaining.size());assertEquals(saved.batchIntent.toString(),copy.batchIntent.toString());
            copy.remaining.clear();copy.batchIntent.getJSONArray("outputs").getJSONObject(0).put("amount","99");
            DistributeJob fresh=DistributeJob.load(f.act);assertEquals(15,fresh.remaining.size());assertEquals("1",fresh.batchIntent.getJSONArray("outputs").getJSONObject(0).getString("amount"));
        }
    }
    @Test public void corruptTrailingDataAndLegacyRecordsRemainByteForBytePreserved() {
        for(String raw:new String[]{"{} trailing","{}\u0000","[]","null","{\"remaining\":[\"literal\"]}"})try(Flow f=new Flow()) {
            f.prefs.values.put("job",raw);DistributeJob loaded=DistributeJob.load(f.act);assertNotNull(loaded);assertTrue(loaded.invalid||loaded.legacy);assertFalse(loaded.save(f.act));assertEquals(raw,f.prefs.values.get("job"));
        }
    }
}
