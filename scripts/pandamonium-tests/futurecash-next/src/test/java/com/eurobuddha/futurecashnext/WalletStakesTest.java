package com.eurobuddha.futurecashnext;

import org.json.*;
import org.junit.Test;
import org.mockito.MockedConstruction;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Synthetic chain replies only. No node, funds, seeds, signing counters or network. */
public class WalletStakesTest {
    static final String A="0x"+"11".repeat(32), B="0x"+"22".repeat(32), C="0x"+"33".repeat(32);
    static final MinimaAddress.Result NODE=MinimaAddress.fromPublicKey(A), WALLET=MinimaAddress.fromPublicKey(B);
    static JSONObject ok(Object response) throws Exception {return new JSONObject().put("status",true).put("response",response);}
    static JSONArray rows(Object... rows) {JSONArray out=new JSONArray();for(Object row:rows)out.put(row);return out;}
    static JSONObject script(MinimaAddress.Result address,String key) throws Exception {
        return new JSONObject().put("address",address.hex).put("miniaddress",address.mx).put("publickey",key).put("script",address.script);
    }
    static JSONObject coin(String id,String payout) throws Exception {
        return new JSONObject().put("coinid",id).put("address",FutureCashContract.KNOWN_ADDRESS)
                .put("spent",false).put("amount","5000").put("tokenid","0x00").put("created","40")
                .put("state",rows(new JSONObject().put("port",1).put("data","100"),
                        new JSONObject().put("port",2).put("data",payout),new JSONObject().put("port",4).put("data","20")));
    }
    static class Fixture {
        final Node node=mock(Node.class);final Cfg cfg=mock(Cfg.class);final Store store=mock(Store.class);
        Fixture() throws Exception {
            when(store.readyTotals(anyLong(),anyBoolean())).thenReturn(new double[]{0,0});
            when(store.pendingAtRisk(anyLong())).thenReturn(new double[]{0,0,0});
            when(cfg.walletAuditKeys()).thenReturn(rows(new JSONObject().put("publickey",B).put("uses",0)));
            when(cfg.get(Cfg.SCRIPT_ADDRESS,null)).thenReturn(FutureCashContract.KNOWN_ADDRESS);
            when(cfg.atRisk()).thenReturn(new JSONArray());
            when(node.cmd("scripts")).thenReturn(ok(rows(script(NODE,A),script(WALLET,"0x00"))));
            when(node.cmd("keys action:list")).thenReturn(ok(new JSONObject().put("keys",rows(new JSONObject().put("publickey",A)))));
            when(node.cmd("coins relevant:true")).thenReturn(ok(rows(coin(A,NODE.hex))));
            when(node.cmd("coins address:"+FutureCashContract.KNOWN_ADDRESS)).thenReturn(ok(rows(coin(A,NODE.hex),coin(B,WALLET.hex),coin(C,MinimaAddress.fromPublicKey(C).hex))));
            when(node.cmd(startsWith("cointrack enable:true coinid:"))).thenReturn(ok("tracked"));
            when(node.cmd("block")).thenReturn(ok(new JSONObject().put("block","90")));
        }
        Guardian guardian() {return new Guardian(node,cfg,store,(a,b)->{});}
        Wallet wallet() {return new Wallet(node,cfg);}
    }
    @Test public void publicWalletOwnershipDoesNotTurnOtherWatchScriptsIntoOurMoney() throws Exception {
        Fixture f=new Fixture();Wallet.AddressSets set=f.wallet().addressSets();
        assertTrue(Wallet.owns(set.ownDefault,NODE.hex));assertTrue(Wallet.owns(set.ownDefault,WALLET.mx));
        assertFalse(Wallet.owns(set.nodeOwned,WALLET.hex));assertTrue(Wallet.owns(set.walletOwned,WALLET.hex));
        assertFalse(Wallet.owns(set.ownDefault,MinimaAddress.fromPublicKey(C).hex));
        verify(f.node,never()).cmd(startsWith("txnsign"));
    }
    @Test public void listFindsOldWalletStakeOutsideRelevantSetDeduplicatesAndUsesCoinAge() throws Exception {
        Fixture f=new Fixture();List<Guardian.Stake> stakes=f.guardian().listStakes();
        assertEquals(2,stakes.size());assertTrue(stakes.get(0).node);assertFalse(stakes.get(0).wallet);
        assertTrue(stakes.get(1).wallet);assertFalse(stakes.get(1).node);
        assertTrue("coin age unlocks before absolute block",stakes.get(1).ready);
        verify(f.node).cmd("coins address:"+FutureCashContract.KNOWN_ADDRESS);
        verify(f.node,never()).cmd(contains("relevant:false"));
        verify(f.node,never()).cmd(startsWith("newscript"));
    }
    @Test public void contractSearchRejectsForeignOwnersSpoofContractsAndSpentCoins() throws Exception {
        Fixture f=new Fixture();JSONObject spoof=coin(B,WALLET.hex).put("address",C), spent=coin(C,WALLET.hex).put("spent",true);
        when(f.node.cmd("coins address:"+FutureCashContract.KNOWN_ADDRESS)).thenReturn(ok(rows(spoof,spent)));
        assertEquals(1,f.guardian().listStakes().size());
    }
    @Test public void malformedOrFailedSearchCannotBecomeAnEmptySuccessfulScan() throws Exception {
        for(JSONObject bad:new JSONObject[]{new JSONObject(),ok(new JSONObject()),new JSONObject().put("status",false),
                new JSONObject().put("status","true").put("response",new JSONArray()),ok(rows("bad")),ok(new JSONArray()).put("pending",true)}) {
            Fixture f=new Fixture();when(f.node.cmd("coins address:"+FutureCashContract.KNOWN_ADDRESS)).thenReturn(bad);
            assertThrows(IllegalStateException.class,()->f.guardian().listStakes());
        }
    }
    @Test public void unconfiguredWalletRetainsNodeDiscoveryWithoutExtraChainSearch() throws Exception {
        Fixture f=new Fixture();when(f.cfg.walletAuditKeys()).thenReturn(new JSONArray());
        assertEquals(1,f.guardian().listStakes().size());verify(f.node,never()).cmd(startsWith("coins address:"));
    }
    @Test public void seedReplacementRevokesPriorWalletSnapshot() throws Exception {
        Fixture f=new Fixture();Wallet wallet=f.wallet();Wallet.AddressSets prior=wallet.addressSets();assertTrue(wallet.walletUnchanged(prior));
        when(f.cfg.walletAuditKeys()).thenReturn(rows(new JSONObject().put("publickey",C).put("uses",0)));
        assertFalse(wallet.walletUnchanged(prior));assertFalse(wallet.canSweep(WALLET.hex,prior));
    }
    @Test public void onlyMissingWalletScriptsAreRegisteredAndNeverAsNodeOwned() throws Exception {
        Fixture f=new Fixture();when(f.node.cmd("scripts")).thenReturn(ok(rows(script(NODE,A))));
        when(f.node.cmd("newscript trackall:true script:\""+WALLET.script+"\"")).thenReturn(ok(new JSONObject().put("address",WALLET.hex)));
        f.guardian().listStakes();verify(f.node).cmd("newscript trackall:true script:\""+WALLET.script+"\"");
        verify(f.node,never()).cmd(contains(NODE.script));
    }
    @Test public void trackingFailurePreventsPretendingDiscoverySucceeded() throws Exception {
        Fixture f=new Fixture();when(f.node.cmd("scripts")).thenReturn(ok(rows(script(NODE,A))));
        assertThrows(IllegalStateException.class,()->f.guardian().listStakes());
    }
    @Test public void lockedWalletCannotBeCollectedForRescueEvenWhenNodeIsUnlocked() throws Exception {
        Fixture f=new Fixture();when(f.node.cmd("coins coinid:"+B)).thenReturn(ok(rows(coin(B,WALLET.hex))));when(f.cfg.atRisk()).thenReturn(rows(WALLET.hex));when(f.cfg.walletCanSweep(anyString())).thenReturn(false);
        when(f.node.cmd("status")).thenReturn(ok(new JSONObject().put("locked",false)));
        Guardian g=f.guardian();g.reloadAtRisk();Guardian.Stake s=g.listStakes().get(1);
        assertTrue(s.atRisk);assertFalse(s.rescueAvailable);assertFalse(g.collectNow(B).ok);
        verify(f.node,never()).cmd(startsWith("txncreate"));
    }
    @Test public void safeWalletCollectionUsesExistingUnsignedExactPayoutSequence() throws Exception {
        Fixture f=new Fixture();JSONObject c=coin(B,WALLET.hex);when(f.node.cmd("coins coinid:"+B)).thenReturn(ok(rows(c)));
        when(f.node.cmd(startsWith("txn"))).thenReturn(ok(new JSONObject()));
        try(MockedConstruction<Audit> audits=mockConstruction(Audit.class,(mock,context)->when(mock.usable()).thenReturn(new Audit.Verdict(true,1,Audit.Mode.FRESH)))) {
            Wallet.Res result=f.guardian().collectNow(B);assertTrue(result.error,result.ok);
            verify(f.node).cmd(contains("address:"+WALLET.hex+" amount:5000 tokenid:0x00 storestate:false"));
            verify(f.node,never()).cmd(startsWith("txnsign"));verify(f.cfg,never()).walletCanSweep(anyString());
        }
    }
    @Test public void arbitraryContractWithMatchingPortsCannotBeManuallyCollected() throws Exception {
        Fixture f=new Fixture();when(f.node.cmd("coins coinid:"+B)).thenReturn(ok(rows(coin(B,WALLET.hex).put("address",C))));
        assertFalse(f.guardian().collectNow(B).ok);verify(f.node,never()).cmd(startsWith("txncreate"));
    }
    @Test public void absentPublicMetadataIsAnExplicitFailureNotAnEmptyWallet() throws Exception {
        Fixture f=new Fixture();when(f.cfg.walletAuditKeys()).thenThrow(new IllegalStateException("metadata unavailable"));
        assertThrows(IllegalStateException.class,()->f.guardian().listStakes());
    }
    @Test public void autoCollectFindsClassicWalletStakeWhileNodeUsesOtherKeys() throws Exception {
        autoCollect(false,true,true);
    }
    @Test public void autoRescueCannotCollectBeforeTheWalletSignerIsReady() throws Exception {
        autoCollect(true,false,false);
    }
    @Test public void autoRescueUsesWalletReadinessEvenWithLockedNodeVault() throws Exception {
        autoCollect(true,true,true);
    }
    private void autoCollect(boolean risk,boolean walletReady,boolean expectPost) throws Exception {
        Fixture f=new Fixture();Store.CollectRow row=new Store.CollectRow();row.coinid=B;row.payout=WALLET.hex;
        row.amount="5000";row.tokenid="0x00";row.atRisk=risk;row.status=Store.DETECTED;
        when(f.store.collectsWithStatus(Store.DETECTED)).thenReturn(Collections.singletonList(row));
        when(f.store.claimForPost(eq(B),anyString(),eq(90L))).thenReturn(true);
        when(f.cfg.atRisk()).thenReturn(risk?rows(WALLET.hex):new JSONArray());
        when(f.cfg.is(Cfg.AUTO_COLLECT_SAFE,false)).thenReturn(true);when(f.cfg.is(Cfg.ENABLED,false)).thenReturn(true);
        when(f.cfg.get(Cfg.SAFE_ADDRESS,null)).thenReturn(MinimaAddress.fromPublicKey(C).mx);
        when(f.cfg.walletCanSweep(WALLET.hex)).thenReturn(walletReady);
        when(f.node.cmd("status")).thenReturn(ok(new JSONObject().put("locked",true)));
        when(f.node.cmd(startsWith("txn"))).thenReturn(ok(new JSONObject()));
        try(MockedConstruction<Audit> audits=mockConstruction(Audit.class,(mock,context)->when(mock.usable()).thenReturn(new Audit.Verdict(true,1,Audit.Mode.FRESH)))) {
            Guardian guardian=f.guardian();guardian.reconcile(90);
            if(expectPost) {
                verify(f.node).cmd(startsWith("txnpost"));
                verify(f.node).cmd(contains("address:"+WALLET.hex+" amount:5000 tokenid:0x00"));
            } else {
                verify(f.node,never()).cmd(startsWith("txncreate"));
                verify(f.store,never()).claimForPost(anyString(),anyString(),anyLong());
                assertTrue(guardian.status(90).walletLocked);
            }
        }
    }
    @Test public void discoveredWalletStakeIsTrackedButOtherPeoplesStakesAreNot() throws Exception {
        Fixture f=new Fixture();f.guardian().listStakes();
        verify(f.node).cmd("cointrack enable:true coinid:"+B);
        verify(f.node,never()).cmd("cointrack enable:true coinid:"+A);
        verify(f.node,never()).cmd("cointrack enable:true coinid:"+C);
    }
    @Test public void failureToPreserveOldStakeProofIsVisible() throws Exception {
        Fixture f=new Fixture();when(f.node.cmd(startsWith("cointrack"))).thenReturn(new JSONObject().put("status",false));
        assertThrows(IllegalStateException.class,()->f.guardian().listStakes());
    }
    @Test public void duplicateOwnerIsOneStakeWithBothSourceLabels() throws Exception {
        Fixture f=new Fixture();when(f.cfg.walletAuditKeys()).thenReturn(rows(new JSONObject().put("publickey",A).put("uses",0)));
        List<Guardian.Stake> stakes=f.guardian().listStakes();assertEquals(1,stakes.size());
        assertTrue(stakes.get(0).node);assertTrue(stakes.get(0).wallet);
    }
    @Test public void staleWalletSnapshotCannotAuthorizeAManualCollection() throws Exception {
        Fixture f=new Fixture();when(f.node.cmd("coins coinid:"+B)).thenReturn(ok(rows(coin(B,WALLET.hex))));
        JSONArray before=rows(new JSONObject().put("publickey",B).put("uses",0));
        when(f.cfg.walletAuditKeys()).thenReturn(before,rows(new JSONObject().put("publickey",C).put("uses",0)));
        try(MockedConstruction<Audit> audits=mockConstruction(Audit.class,(mock,context)->when(mock.usable()).thenReturn(new Audit.Verdict(true,1,Audit.Mode.FRESH)))) {
            assertFalse(f.guardian().collectNow(B).ok);verify(f.node,never()).cmd(startsWith("txncreate"));
        }
    }

    @Test public void rejectedCoinLookupCannotUseItsResponsePayloadToAuthorizeCollection() throws Exception {
        Fixture f=new Fixture();when(f.node.cmd("coins coinid:"+B)).thenReturn(ok(rows(coin(B,WALLET.hex))).put("status",false));
        assertFalse(f.guardian().collectNow(B).ok);verify(f.node,never()).cmd(startsWith("txncreate"));
        assertFalse(Node.ok(new JSONObject().put("status","true")));
    }
    @Test public void sharedClassicAddressCanUseWalletSignerWhenNodeVaultIsLocked() throws Exception {
        Fixture f=new Fixture();when(f.cfg.walletAuditKeys()).thenReturn(rows(new JSONObject().put("publickey",A).put("uses",0)));
        when(f.node.cmd("status")).thenReturn(ok(new JSONObject().put("locked",true)));
        when(f.cfg.walletCanSweep(NODE.hex)).thenReturn(true);
        Wallet wallet=f.wallet();assertTrue(wallet.canSweep(NODE.hex,wallet.addressSets()));
    }


    @Test public void riskReloadRetainsRetiredNodeKeysEvenWhenAuditCacheIsEmpty() throws Exception {
        Fixture f=new Fixture();
        when(f.node.cmd("scripts")).thenReturn(ok(rows(script(NODE,"0x00"))));
        Guardian g=f.guardian();g.reloadAtRisk();
        assertTrue(g.atRiskSet().contains(Wallet.canonAddr(NODE.hex)));
    }
    @Test public void unavailableOrMalformedVaultStatusCannotAuthorizeSigning() throws Exception {
        Fixture f=new Fixture();
        for(JSONObject bad:new JSONObject[]{new JSONObject(),ok(new JSONObject()),
                ok(new JSONObject().put("locked",false)).put("status",false),
                ok(new JSONObject().put("locked",false)).put("status","true"),
                ok(new JSONObject().put("locked",false)).put("pending",true),
                ok(new JSONObject().put("locked","false"))}) {
            when(f.node.cmd("status")).thenReturn(bad);assertTrue(f.wallet().vaultLocked());
        }
        when(f.node.cmd("status")).thenReturn(ok(new JSONObject().put("locked",false)));
        assertFalse(f.wallet().vaultLocked());
    }
}
