package com.eurobuddha.vestr;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** The collect sequence: what is owed to the node when it fails, loses its reply or its screen closes. */
public class CollectRunTest {

    static final String ID = "vc1700000000000";
    static final List<String> CMDS = Arrays.asList(
            "txncreate id:" + ID,
            "txninput id:" + ID + " coinid:0x23456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF01 scriptmmr:true",
            "txnstate id:" + ID + " port:199 value:0x112233445566778899AABBCCDDEEFF00",
            "txncheck id:" + ID,
            "txnpost id:" + ID);

    static JSONObject ok() throws Exception { return new JSONObject().put("status", true); }
    static JSONObject checked(boolean valid) throws Exception {
        return ok().put("response", new JSONObject().put("validtransaction", valid));
    }

    /** Send and answer every command before txnpost. */
    static CollectRun upToThePost() throws Exception {
        CollectRun r = new CollectRun(ID, CMDS);
        for (int i = 0; i < 3; i++) { assertEquals(CMDS.get(i), r.next()); assertEquals(CollectRun.Next.RUN, r.replied(ok())); }
        assertEquals(CMDS.get(3), r.next());
        assertEquals(CollectRun.Next.RUN, r.replied(checked(true)));
        return r;
    }

    @Test public void aFullRunEndsWithThePost() throws Exception {
        CollectRun r = upToThePost();
        assertEquals("txnpost id:" + ID, r.next());
        assertEquals(CollectRun.Next.DONE, r.replied(ok()));
        assertNull(r.next());
        assertNull(r.abandon());
    }

    @Test public void closingTheScreenBeforeThePostOwesATxndelete() throws Exception {
        CollectRun r = new CollectRun(ID, CMDS);
        assertNull("nothing was created yet", r.abandon());
        r.next();                                   // txncreate in flight, its reply never seen
        assertEquals("txndelete id:" + ID, r.abandon());
        assertNull("owed once", r.abandon());
        assertNull("and the run stops", r.next());

        r = upToThePost();                          // everything built and checked, txnpost not sent yet
        assertEquals("txndelete id:" + ID, r.abandon());
    }

    @Test public void closingTheScreenAfterThePostWasSentOwesNothing() throws Exception {
        CollectRun r = upToThePost();
        r.next();                                   // txnpost in flight
        assertNull(r.abandon());
    }

    @Test public void aLostReplyBeforeThePostIsAFailureAfterItUnknown() throws Exception {
        CollectRun r = new CollectRun(ID, CMDS);
        r.next();
        r.replied(ok());
        r.next();
        assertEquals(CollectRun.Next.FAILED, r.noReply());   // the screen's fail() runs txndelete
        assertNull("fail() already deletes", r.abandon());

        r = upToThePost();
        r.next();                                   // txnpost sent, then the write timeout
        assertEquals(CollectRun.Next.UNKNOWN, r.noReply());
        assertNull("never a txndelete once posted", r.abandon());
        assertNull(r.next());
    }

    /** The node's refusal of txnpost: Command.getJSONReply when txnpost throws, or CommandRunner's gates (the whole line). */
    static JSONObject refused(String command, String error) throws Exception {
        return new JSONObject().put("command", command).put("params", new JSONObject().put("id", ID))
                .put("status", false).put("pending", false).put("error", error);
    }

    @Test public void aRefusedPostIsAFailure() throws Exception {
        CollectRun r = upToThePost();
        r.next();
        assertEquals(CollectRun.Next.FAILED, r.replied(refused("txnpost", "Transaction not found : " + ID)));
        assertNull("fail() already deletes", r.abandon());

        r = upToThePost();
        r.next();
        assertEquals(CollectRun.Next.FAILED, r.replied(refused("txnpost id:" + ID + " burn:0.1",
                "You need ADMIN privileges to run this command (burn)")));
        assertEquals(CollectRun.Next.FAILED, refusedAfterThePost(new JSONObject(
                "{\"command\":\"txnpost\",\"params\":{\"id\":\"" + ID + "\"},\"status\":false,\"pending\":false,"
                + "\"error\":\"TxPoW size too large.. 70000/64000\"}")));
    }

    static CollectRun.Next refusedAfterThePost(JSONObject reply) throws Exception {
        CollectRun r = upToThePost();
        assertEquals("txnpost id:" + ID, r.next());
        return r.replied(reply);
    }

    @Test public void aFailureTheSdkMadeUpAfterThePostIsUnknown() throws Exception {
        // the bundled minimaapi.aar invents these when it cannot read the node's reply: the post may have gone through
        assertEquals(CollectRun.Next.UNKNOWN, refusedAfterThePost(new JSONObject().put("status", false)
                .put("error", "Failed to read large response: java.io.FileNotFoundException")));
        assertEquals(CollectRun.Next.UNKNOWN, refusedAfterThePost(new JSONObject().put("status", false)
                .put("error", "Node returned an invalid JSON response")));
        // not txnpost's own answer, not a definite false, or still pending: unknown as well
        assertEquals(CollectRun.Next.UNKNOWN, refusedAfterThePost(refused("txncheck", "Transaction not found : " + ID)));
        assertEquals(CollectRun.Next.UNKNOWN, refusedAfterThePost(refused("txnposted", "?")));
        assertEquals(CollectRun.Next.UNKNOWN, refusedAfterThePost(refused("txnpost", "?").put("status", "false")));
        assertEquals(CollectRun.Next.UNKNOWN, refusedAfterThePost(refused("txnpost", "?").put("pending", true)));
        assertEquals(CollectRun.Next.UNKNOWN, refusedAfterThePost(new JSONObject()));

        CollectRun r = upToThePost();
        r.next();
        assertEquals(CollectRun.Next.UNKNOWN, r.replied(new JSONObject().put("status", false)));
        assertNull("never a txndelete once posted", r.abandon());
        assertNull(r.next());
    }

    @Test public void beforeThePostEveryFailureStillOwesTheTxndelete() throws Exception {
        CollectRun r = new CollectRun(ID, CMDS);
        r.next();
        assertEquals(CollectRun.Next.FAILED, r.replied(new JSONObject().put("status", false)
                .put("error", "Failed to read large response: java.io.FileNotFoundException")));
        r = new CollectRun(ID, CMDS);
        r.next();
        r.replied(ok());
        r.next();
        assertEquals(CollectRun.Next.FAILED, r.replied(refused("txninput", "Coin not found")));
    }

    @Test public void anInvalidCheckStopsTheRunBeforeThePost() throws Exception {
        CollectRun r = new CollectRun(ID, CMDS);
        for (int i = 0; i < 3; i++) { r.next(); r.replied(ok()); }
        assertEquals("txncheck id:" + ID, r.next());
        assertEquals(CollectRun.Next.INVALID, r.replied(checked(false)));
        assertNull("txnpost is never sent", r.next());
        r = new CollectRun(ID, CMDS);
        for (int i = 0; i < 3; i++) { r.next(); r.replied(ok()); }
        r.next();
        assertEquals(CollectRun.Next.INVALID, r.replied(ok()));   // no verdict at all
    }
}
