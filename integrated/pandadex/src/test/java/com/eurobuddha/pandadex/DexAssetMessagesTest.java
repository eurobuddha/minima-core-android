package com.eurobuddha.pandadex;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.json.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class DexAssetMessagesTest {
    @Before public void setup(){MakerConfig.resetStorageForTests();}
    @After public void cleanup(){MakerConfig.resetStorageForTests();}
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static Object[] args(Object value)throws Exception{return (Object[])field(value,"arguments");}
    private static void resource(Object value,int id)throws Exception{assertEquals(id,field(value,"resource"));}
    @Test public void realBalanceBreakdownKeepsConfirmedLockedAndUnconfirmedSeparate()throws Exception {
        JSONObject row=new TestJson().put("tokenid","0x00").put("sendable","7.5").put("confirmed","10").put("unconfirmed","2.25").put("coins",4);
        MainActivity.BalanceMeta b=MainActivity.balanceMeta(new TestJson().put("status",true).put("response",row),"0x00");
        Object display=AssetsTab.balanceBreakdown(b.confirmed,b.locked(),b.unconfirmed,b.coins,b.atMs);
        resource(display,R.string.l10n_dex_asset_breakdown);Object[] a=args(display);
        assertEquals("10",a[0]);assertEquals("2.5",a[1]);assertEquals("2.25",a[2]);assertEquals(4,a[3]);
        resource(a[4],R.string.l10n_pools_seconds_ago);assertEquals(new BigDecimal("7.5"),b.sendable);
        JSONObject empty=new TestJson().put("status",true).put("response",new JSONArray());
        assertEquals(0,MainActivity.balanceMeta(empty,"0x00").atMs);
        assertTrue(MainActivity.balanceMeta(empty,DexContract.USDT_ID).atMs>0);
    }
    @Test public void balanceAndReceivePlaceholdersKeepDisconnectedAndFailedStates()throws Exception {
        resource(DexMessages.message(MainActivity.balanceMessage(false,false,false)),R.string.l10n_dex_node_connecting);
        resource(DexMessages.message(MainActivity.balanceMessage(false,true,false)),R.string.l10n_pools_node_unavailable);
        resource(DexMessages.message(MainActivity.balanceMessage(true,true,true)),R.string.l10n_dex_balance_retry);
        resource(DexMessages.message(MainActivity.balanceMessage(true,true,false)),R.string.l10n_dex_balance_loading);
        resource(DexMessages.message(MainActivity.receiveLoadingMessage(true,true)),R.string.l10n_dex_receive_loading);
        resource(DexMessages.message(MainActivity.receiveLoadingMessage(false,true)),R.string.l10n_pools_node_unavailable);
    }
    @Test public void savedOrdersRemainDistinctAndCommandDetailsStayLiteral()throws Exception {
        Object current=DexMessages.message("in PandaDEX orders 00012.34000");resource(current,R.string.l10n_dex_in_orders);
        Object saved=DexMessages.message("in saved PandaDEX orders 00012.34000");resource(saved,R.string.l10n_dex_in_saved_orders);
        assertArrayEquals(new Object[]{"00012.34000"},args(current));assertArrayEquals(args(current),args(saved));
        String command="txnpost id:0x"+"ab".repeat(64)+" payload:中文 Русский Українська {id} $1\nSafety pause cleared.";
        assertEquals(command,DexMessages.message(command));
        Object unknown=AssetsTab.balanceBreakdown(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,0,0);
        resource(args(unknown)[4],R.string.l10n_nft_wallet_never);
    }
    @Test public void failedMakerDisarmCannotReportRecoveryReady() {
        MakerWithdrawalDurabilityTest.Memory memory=new MakerWithdrawalDurabilityTest.Memory();MakerConfig config=MakerSnapshotWriteTest.seed(memory);
        memory.failAt=memory.writes+1;
        assertFalse(MainActivity.disarmMakerForRecovery(config));
        assertEquals(Boolean.TRUE,memory.disk.get("armed"));assertEquals(Boolean.FALSE,memory.visible.get("armed"));
        assertFalse(MakerConfig.storageHealthy());
        assertTrue(MainActivity.disarmMakerForRecovery(config));assertFalse(memory.restart().armed);assertTrue(MakerConfig.storageHealthy());
    }
    @Test public void recoveryDisarmReloadsLatestMakerReceiptsBeforeSaving() {
        MakerWithdrawalDurabilityTest.Memory memory=new MakerWithdrawalDurabilityTest.Memory();MakerConfig current=MakerSnapshotWriteTest.seed(memory);
        MakerConfig stale=new MakerConfig(memory.prefs());assertTrue(current.prepareCreate("A1","0xaabb",BigDecimal.TEN,100));
        assertTrue(MainActivity.disarmMakerForRecovery(stale));MakerConfig restarted=memory.restart();
        assertFalse(restarted.armed);assertEquals("0xaabb",restarted.preparedOrderId());
        assertEquals(BigDecimal.TEN,restarted.asks.get(0).sizeMinima);
    }
}
