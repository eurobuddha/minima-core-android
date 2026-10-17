package com.eurobuddha.futurecashnext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual merge, cache and runner paths with synthetic public keys; no network or node. */
public class WalletAuditIntegrationTest {
    private static final String A="0x3A985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE24511431532";
    private static final String B="0xA7FFC6F8BF1ED76651C14756A061D662F580FF4DE43B49FA82D80A4B80F8434A";
    private static final String C="0x1111111111111111111111111111111111111111111111111111111111111111";
    private static JSONObject row(String pk,int uses,boolean block) throws Exception {
        return new JSONObject().put("publickey",pk).put("uses",uses).put("size",block?128:64)
                .put("depth",block?4:3).put("maxuses",block?268435456:262144);
    }
    private static JSONArray rows(JSONObject... items) {JSONArray a=new JSONArray();for(JSONObject row:items)a.put(row);return a;}
    private static JSONObject reply(JSONArray keys) throws Exception {
        return new JSONObject().put("status",true).put("response",new JSONObject().put("keys",keys));
    }
    private static Cfg cfg(JSONArray wallet) {
        Cfg cfg=mock(Cfg.class);when(cfg.walletAuditKeys()).thenReturn(wallet);
        when(cfg.get(Cfg.ATRISK_ADDRS,"[]")).thenReturn("[]");return cfg;
    }
    @Test public void combinesBothNodeFamiliesAndSeparateClassicWalletWithoutChangingInputs() throws Exception {
        JSONArray node=rows(row(A,8,false),row(B,2350000,true));JSONArray wallet=rows(row(C,17,false),row(A,12,false));
        String before=node.toString(), wb=wallet.toString();JSONArray combined=AuditKeySet.combine(node,wallet);
        assertEquals(3,combined.length());assertEquals(12,combined.getJSONObject(0).getInt("uses"));
        assertEquals(2350000,combined.getJSONObject(1).getInt("uses"));assertEquals(C,combined.getJSONObject(2).getString("publickey"));
        assertEquals(before,node.toString());assertEquals(wb,wallet.toString());
    }
    @Test public void invalidOrUntrustedDuplicateRemainsIncompleteInsteadOfBeingMasked() throws Exception {
        JSONArray merged=AuditKeySet.combine(rows(row(A,50,false)),rows(row(A,-1,false)));
        assertEquals(2,merged.length());assertEquals(-1,merged.getJSONObject(1).getInt("uses"));
    }
    @Test public void absentSnapshotMalformedRowAndDuplicateNodeKeyAreRejected() throws Exception {
        assertThrows(IllegalStateException.class,()->AuditKeySet.combine(new JSONArray(),null));
        assertThrows(IllegalStateException.class,()->AuditKeySet.combine(new JSONArray().put("bad"),new JSONArray()));
        JSONArray duplicate=rows(row(A,1,false),row(A,2,false));
        assertThrows(IllegalStateException.class,()->AuditKeySet.combine(duplicate,new JSONArray()));
    }
    @Test public void contradictoryFamiliesForOnePublicKeyCannotLookComplete() throws Exception {
        JSONArray node=rows(row(A,2000000,true)),wallet=rows(row(A,4,false));
        assertThrows(IllegalStateException.class,()->AuditKeySet.combine(node,wallet));
    }
    private static Cfg cached(JSONArray node,JSONArray wallet,long age) {
        Cfg cfg=cfg(wallet);when(cfg.is(Cfg.AUDIT_OK,false)).thenReturn(true);
        when(cfg.getLong(Cfg.AUDIT_AT)).thenReturn(System.currentTimeMillis()-age);
        when(cfg.get(Cfg.AUDIT_USES,"")).thenReturn(Audit.usesFingerprint(AuditKeySet.combine(node,wallet)));
        when(cfg.get(Cfg.AUDIT_WALLET_USES,"")).thenReturn(Audit.usesFingerprint(wallet));return cfg;
    }
    @Test public void changedWalletCounterOrSeedInvalidatesEvenFreshAudit() throws Exception {
        JSONArray node=rows(row(A,4,false)),wallet=rows(row(C,9,false));
        for(JSONArray changed:new JSONArray[]{rows(row(C,10,false)),rows(row(B,9,false)),new JSONArray()}) {
            Cfg cfg=cached(node,wallet,10);when(cfg.walletAuditKeys()).thenReturn(changed);Node source=mock(Node.class);
            assertEquals(Audit.Mode.STALE,new Audit(cfg,source).usable().mode);verifyNoInteractions(source);
        }
    }
    @Test public void missingMetadataCannotReuseFreshCachedPermission() throws Exception {
        Cfg cfg=cached(rows(row(A,4,false)),rows(row(C,9,false)),10);
        when(cfg.walletAuditKeys()).thenThrow(new IllegalStateException("missing metadata"));
        assertFalse(new Audit(cfg,mock(Node.class)).usable().ok);
    }
    @Test public void unchangedCombinedKeySetCanBeReconfirmedOffline() throws Exception {
        JSONArray node=rows(row(A,4,false),row(B,2000000,true)),wallet=rows(row(C,9,false));
        Cfg cfg=cached(node,wallet,Audit.FRESH_MS+10000);Node source=mock(Node.class);
        when(source.cmd("keys action:list")).thenReturn(reply(node));
        assertEquals(Audit.Mode.RECONFIRMED,new Audit(cfg,source).usable().mode);
        when(source.cmd("keys action:list")).thenReturn(reply(rows(row(A,5,false),row(B,2000000,true))));
        assertFalse(new Audit(cfg,source).usable().ok);
    }
    @Test public void runnerQueriesAllThreeSetsAndStoresTheCapturedWalletFingerprint() throws Exception {
        runAudit(false,false);
    }
    @Test public void seedOrCounterChangeDuringRequestCannotPublishCompleteAudit() throws Exception {
        runAudit(true,false);
    }
    @Test public void missingWalletReplyKeepsTheCombinedAuditPartial() throws Exception {
        runAudit(false,true);
    }
    private void runAudit(boolean changed,boolean missing) throws Exception {
        JSONArray nodeKeys=rows(row(A,4,false),row(B,2000000,true)),walletKeys=rows(row(C,9,false));
        Node node=mock(Node.class);when(node.cmd("keys action:list")).thenReturn(reply(nodeKeys));
        Cfg cfg=cfg(walletKeys);AuditRunner.Cb done=mock(AuditRunner.Cb.class);
        try(MockedConstruction<AuditApi> mocked=mockConstruction(AuditApi.class)) {
            AuditRunner runner=new AuditRunner(node,cfg,null);runner.run(done);AuditApi api=mocked.constructed().get(0);
            ArgumentCaptor<List<String>> requested=ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<AuditApi.Cb> callback=ArgumentCaptor.forClass(AuditApi.Cb.class);
            verify(api).audit(requested.capture(),anyList(),callback.capture());assertEquals(Arrays.asList(A,B,C),requested.getValue());
            List<KeyAudit.Usage> usage=new ArrayList<>();List<KeyAudit.Reuse> reuse=new ArrayList<>();
            for(String pk:Arrays.asList(A,B,C)) {
                String address=MinimaAddress.fromPublicKey(pk).hex;
                usage.add(new KeyAudit.Usage(pk,address,0,0));
                if(!(missing && C.equals(pk)))reuse.add(new KeyAudit.Reuse(address,false,0));
            }
            if(changed)when(cfg.walletAuditKeys()).thenReturn(rows(row(C,10,false)));
            callback.getValue().onResult(usage,reuse,123456);
            verify(cfg).setBool(Cfg.AUDIT_OK,!changed&&!missing);
            verify(cfg).set(Cfg.AUDIT_WALLET_USES,Audit.usesFingerprint(walletKeys));
            if(changed){verify(done).onError(contains("changed"));verify(done,never()).onDone(any(),anyLong());}
            else{ArgumentCaptor<KeyAudit.Result> result=ArgumentCaptor.forClass(KeyAudit.Result.class);verify(done).onDone(result.capture(),eq(123456L));assertEquals(2,result.getValue().classicKeys);assertEquals(1,result.getValue().blockKeys);assertEquals(!missing,result.getValue().complete);}
        }
    }
    @Test public void missingPublicMetadataStopsBeforeAnyRemoteQuery() throws Exception {
        Node node=mock(Node.class);when(node.cmd("keys action:list")).thenReturn(reply(rows(row(A,4,false))));
        Cfg cfg=cfg(null);when(cfg.walletAuditKeys()).thenThrow(new IllegalStateException("not ready"));
        AuditRunner.Cb done=mock(AuditRunner.Cb.class);
        try(MockedConstruction<AuditApi> mocked=mockConstruction(AuditApi.class)) {
            new AuditRunner(node,cfg,null).run(done);verify(done).onError(contains("minimaWallet"));verifyNoInteractions(mocked.constructed().get(0));
        }
    }
}
