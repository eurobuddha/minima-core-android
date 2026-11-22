package com.eurobuddha.vestr;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.*;

/**
 * A sample contract coin as created in Pandamonium 2.2.75 (invisible and uncollectible there because its 16-byte
 * id failed the 64-hex-only check): coin 0x23456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF01,
 * 100 MINIMA, created at block 1000002, at the contract address
 * 0x3C432D5099AB27EA901079EF54D9A97AB4DB3BD1CFFF670296C31B7C83C1C8BE, state exactly as the node reported it.
 */
public class ContractIdTest {

    static final String COINID = "0x23456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF01";
    static final String SCRIPT = "0x3C432D5099AB27EA901079EF54D9A97AB4DB3BD1CFFF670296C31B7C83C1C8BE";
    static final String UNLOCK = "0xA1B2C3D4E5F60718A1B2C3D4E5F60718A1B2C3D4E5F60718A1B2C3D4E5F60718";
    static final String UID16  = "0x112233445566778899AABBCCDDEEFF00";

    static JSONObject ownerCoin() throws Exception {
        JSONArray st = new JSONArray();
        st.put(sv(0, 1, UNLOCK));
        st.put(sv(1, 2, "100"));
        st.put(sv(2, 2, "1000001"));
        st.put(sv(3, 2, "1000004"));
        st.put(sv(4, 2, "0"));
        st.put(sv(5, 2, "1700000000000"));
        st.put(sv(6, 2, "1700000021278"));
        st.put(sv(7, 2, "0"));
        st.put(sv(8, 2, "1700000141315"));
        st.put(sv(199, 1, UID16));
        return new JSONObject().put("coinid", COINID).put("amount", "100").put("address", SCRIPT)
                .put("tokenid", "0x00").put("created", "1000002").put("state", st);
    }

    static JSONObject sv(int port, int type, String data) throws Exception {
        return new JSONObject().put("port", port).put("type", type).put("data", data);
    }

    static Contract withUid(String uid) throws Exception {
        JSONObject coin = ownerCoin();
        JSONArray st = coin.getJSONArray("state");
        st.getJSONObject(st.length() - 1).put("data", uid);
        return Contract.from(coin);
    }

    @Test public void ownersContractIsShownAndCollectible() throws Exception {
        Contract c = Contract.from(ownerCoin());
        assertTrue(c.safeForCommands());
        assertEquals(COINID, c.coinid);
        assertEquals(SCRIPT, c.address);
        assertEquals(UNLOCK, c.unlockAddr);
        assertEquals(UID16, c.uid);
        assertEquals(0, new BigDecimal("100").compareTo(c.amount));
        assertEquals(0, new BigDecimal("100").compareTo(c.total));
        assertEquals(1000001L, c.startBlock);
        assertEquals(1000004L, c.endBlock);
        assertEquals(0L, c.graceBlocks);
        assertEquals(1000002L, c.createdBlock);
        assertEquals(1700000000000L, c.createdMs);
        assertEquals(1700000021278L, c.startMs);
        assertEquals(1700000141315L, c.endMs);
        // Fully vested at the end block and after it: the whole coin, no change.
        assertEquals(0, new BigDecimal("100").compareTo(c.canCollect(1000004)));
        // Every txnstate the collect sends re-writes the node's own string and passes the command boundary.
        for (int port : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 199})
            assertNull(CommandSafety.failure("txnstate id:vc1 port:" + port + " value:" + Contract.state(c.raw, port)));
    }

    @Test public void idsOf16And32BytesAreAccepted() throws Exception {
        assertTrue(withUid(UID16).safeForCommands());
        assertTrue(withUid("0x" + "AB".repeat(32)).safeForCommands());
        assertTrue(withUid("0x" + "ab".repeat(16)).safeForCommands());   // the node's bytes are the same
        assertTrue(withUid("0x" + "0F".repeat(64)).safeForCommands());   // 64 bytes, the limit
    }

    @Test public void malformedOrInjectedIdsAreRefused() throws Exception {
        for (String bad : new String[]{"", "0x", "0x1", "0x112233445566778899AABBCCDDEEFF0", "0xZZ", "112233445566778899AABBCCDDEEFF00",
                "0x" + "AB".repeat(65), "[0x112233445566778899AABBCCDDEEFF00]", "12345",
                UID16 + " ", UID16 + ";quit", UID16 + "\"", UID16 + " amount:99", "0x01;quit", UID16 + "\n"})
            assertFalse(bad, withUid(bad).safeForCommands());
    }

    @Test public void aNewIdIs32RandomBytes() {
        String a = VestingContract.newContractId(), b = VestingContract.newContractId();
        assertTrue(a, a.matches("0x[0-9A-F]{64}"));
        assertNotEquals(a, b);
        assertTrue(Contract.isContractId(a));
    }

    @Test public void anExpiredContractIsCollectedWithoutAChangeOutput() throws Exception {
        Contract c = Contract.from(ownerCoin());
        // runscript CHECK_MATHS at tip 1000004: owed 3 x SIGDIG(2 100/3)=99, change computed as 1, then the
        // expiry raises cancollect to 100. Paying 100 plus a change of 1 would spend 101 of a 100 coin.
        JSONObject atEnd = new JSONObject().put("cancollect", "100").put("change", "1").put("mustwait", "FALSE");
        BigDecimal[] plan = c.collectPlan(atEnd, 1000004);
        assertEquals(0, new BigDecimal("100").compareTo(plan[0]));
        assertEquals(0, plan[1].signum());
        // Later tips: cancollect 100, change 0.
        plan = c.collectPlan(new JSONObject().put("cancollect", "100").put("change", "0").put("mustwait", "FALSE"), 1000005);
        assertEquals(0, new BigDecimal("100").compareTo(plan[0]));
        assertEquals(0, plan[1].signum());
    }

    @Test public void nothingIsCollectibleWithinTheGracePeriod() throws Exception {
        Contract c = Contract.from(ownerCoin());
        BigDecimal[] plan = c.collectPlan(new JSONObject().put("cancollect", "33").put("change", "67").put("mustwait", "TRUE"), 1000003);
        assertEquals(0, plan[0].signum());
        // A partly vested, collectible contract keeps its change output: payout + change = the coin.
        plan = c.collectPlan(new JSONObject().put("cancollect", "33").put("change", "67").put("mustwait", "FALSE"), 1000003);
        assertEquals(0, new BigDecimal("33").compareTo(plan[0]));
        assertEquals(0, new BigDecimal("67").compareTo(plan[1]));
    }

    /** total 1000, blocks 100 to 1100, grace 50: a coin of {@code amount} created at block {@code created}. */
    static Contract graceCoin(String amount, long created) throws Exception {
        JSONObject coin = ownerCoin().put("amount", amount).put("created", String.valueOf(created));
        JSONArray st = coin.getJSONArray("state");
        st.getJSONObject(1).put("data", "1000");
        st.getJSONObject(2).put("data", "100");
        st.getJSONObject(3).put("data", "1100");
        st.getJSONObject(4).put("data", "50");
        return Contract.from(coin);
    }

    static JSONObject vars(String cancollect, String change, String mustwait) throws Exception {
        return new JSONObject().put("cancollect", cancollect).put("change", change).put("mustwait", mustwait);
    }

    // The CHECK_MATHS variables below are what the node jar's KISS VM returns for these coins and tips; the
    // verdicts are CLEAN_SCRIPT run on the exact collect txn with @BLOCK = the tip (txncheck, relay) and tip + 1.

    @Test public void aChangeCoinOfAgeZeroOffersNothing() throws Exception {
        // The review case: the 900 change coin of a collect mined at 203. CHECK_MATHS says mustwait FALSE (age 0
        // is not GT 0) and cancollect 3; paying 3 + 897 fails the script at 203, 204 ... 252 (@COINAGE LT 50).
        BigDecimal[] plan = graceCoin("900", 203).collectPlan(vars("3", "897", "FALSE"), 203);
        assertEquals(0, plan[0].signum());
        assertEquals(0, new BigDecimal("900").compareTo(plan[1]));
    }

    @Test public void oneBlockShortOfTheGraceOffersNothing() throws Exception {
        // tip 252, age 49: 52 + 848 fails with @BLOCK 252 (txncheck and the node's relay check refuse it).
        assertEquals(0, graceCoin("900", 203).collectPlan(vars("52", "848", "TRUE"), 252)[0].signum());
        assertEquals(0, graceCoin("900", 203).collectPlan(vars("52", "848", "FALSE"), 252)[0].signum());
    }

    @Test public void atTheGraceTheVestedAmountIsOffered() throws Exception {
        // tip 253, age 50: 53 + 847 passes with @BLOCK 253 and 254.
        BigDecimal[] plan = graceCoin("900", 203).collectPlan(vars("53", "847", "FALSE"), 253);
        assertEquals(0, new BigDecimal("53").compareTo(plan[0]));
        assertEquals(0, new BigDecimal("847").compareTo(plan[1]));
    }

    @Test public void fromTheFinalBlockTheGraceDoesNotApply() throws Exception {
        // Created AT the final block 1100 (age 0): the expired branch pays the whole coin, no age check.
        BigDecimal[] plan = graceCoin("300", 1100).collectPlan(vars("300", "0", "FALSE"), 1100);
        assertEquals(0, new BigDecimal("300").compareTo(plan[0]));
        assertEquals(0, plan[1].signum());
        // One block before the final block the grace still applies: 290 + 10 at age 0 fails at 1099.
        assertEquals(0, graceCoin("300", 1099).collectPlan(vars("290", "10", "FALSE"), 1099)[0].signum());
    }

    static JSONObject check(Object status, Object valid) throws Exception {
        JSONObject response = new JSONObject().put("validamounts", true).put("signatures", 0).put("allsignaturesvalid", true);
        if (valid != null) response.put("validtransaction", valid);
        JSONObject reply = new JSONObject().put("command", "txncheck").put("response", response);
        return status == null ? reply : reply.put("status", status);
    }

    @Test public void onlyAValidTxncheckLetsTheCollectBePosted() throws Exception {
        assertTrue(Contract.txnCheckValid(check(true, true)));
        // the node ran the scripts / proofs / amounts and judged the txn invalid
        assertFalse(Contract.txnCheckValid(check(true, false)));
        // malformed or missing: refuse
        assertFalse(Contract.txnCheckValid(check(true, null)));
        assertFalse(Contract.txnCheckValid(check(true, "true")));
        assertFalse(Contract.txnCheckValid(check(false, true)));
        assertFalse(Contract.txnCheckValid(check(null, true)));
        assertFalse(Contract.txnCheckValid(check("true", true)));
        assertFalse(Contract.txnCheckValid(new JSONObject().put("status", true)));
        assertFalse(Contract.txnCheckValid(new JSONObject().put("status", true).put("response", "validtransaction:true")));
        assertFalse(Contract.txnCheckValid(null));
    }
}
