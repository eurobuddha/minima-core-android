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
        BigDecimal[] plan = c.collectPlan(atEnd);
        assertEquals(0, new BigDecimal("100").compareTo(plan[0]));
        assertEquals(0, plan[1].signum());
        // Later tips: cancollect 100, change 0.
        plan = c.collectPlan(new JSONObject().put("cancollect", "100").put("change", "0").put("mustwait", "FALSE"));
        assertEquals(0, new BigDecimal("100").compareTo(plan[0]));
        assertEquals(0, plan[1].signum());
    }

    @Test public void nothingIsCollectibleWithinTheGracePeriod() throws Exception {
        Contract c = Contract.from(ownerCoin());
        BigDecimal[] plan = c.collectPlan(new JSONObject().put("cancollect", "33").put("change", "67").put("mustwait", "TRUE"));
        assertEquals(0, plan[0].signum());
        // A partly vested, collectible contract keeps its change output: payout + change = the coin.
        plan = c.collectPlan(new JSONObject().put("cancollect", "33").put("change", "67").put("mustwait", "FALSE"));
        assertEquals(0, new BigDecimal("33").compareTo(plan[0]));
        assertEquals(0, new BigDecimal("67").compareTo(plan[1]));
    }
}
