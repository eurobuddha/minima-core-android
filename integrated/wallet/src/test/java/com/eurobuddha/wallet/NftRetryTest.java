package com.eurobuddha.wallet;

import android.content.DialogInterface;
import android.widget.Toast;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.wallet.comms.NodeApi;
import com.eurobuddha.wallet.comms.NodeLink;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Fresh public coin fixtures only. No signing, broadcast, node or real wallet. */
public class NftRetryTest {
    private static final String ADDRESS="0x"+"11".repeat(32), RECIPIENT="0x"+"22".repeat(32);
    @SuppressWarnings("unchecked") private static Map<String,Long> pending() throws Exception {
        Field f=NftView.class.getDeclaredField("PENDING");f.setAccessible(true);return (Map<String,Long>)f.get(null);
    }
    @After public void clean() throws Exception {pending().clear();}
    private static Object group() throws Exception {
        Class<?> type=Class.forName("com.eurobuddha.wallet.NftView$Group");
        Constructor<?> c=type.getDeclaredConstructor();c.setAccessible(true);Object g=c.newInstance();
        Field id=type.getDeclaredField("tokenid");id.setAccessible(true);id.set(g,"0xab");
        Field meta=type.getDeclaredField("meta");meta.setAccessible(true);
        org.minima.utils.json.JSONObject token=new org.minima.utils.json.JSONObject();token.put("name","Fixture NFT");token.put("decimals","0");
        meta.set(g,Nft.meta(token));return g;
    }
    private static JSONObject coin(String id)throws Exception {
        return new JSONObject().put("coinid",id).put("tokenid","0xab").put("address",ADDRESS)
                .put("amount","1").put("token",new JSONObject().put("name","Fixture NFT").put("decimals","0"));
    }
    private static JSONObject reply(Object...rows)throws Exception {
        JSONArray list=new JSONArray();for(Object row:rows)list.put(row);
        return new JSONObject().put("status",true).put("response",list);
    }
    private static class Fixture extends NftSessionTest.Fixture implements AutoCloseable {
        final NodeLink node=mock(NodeLink.class);
        final AtomicReference<NodeApi.Cb> response=new AtomicReference<>();
        final List<DialogInterface.OnClickListener> approvals=new ArrayList<>();
        final MockedStatic<Toast> toast;
        final MockedStatic<Language> language;
        final MockedConstruction<WalletDialog> dialogs;
        final Object group=group();
        Fixture()throws Exception {
            when(act.node()).thenReturn(node);when(act.keyIndexForAddress(anyString())).thenReturn(null);when(act.keyIndexForAddress(ADDRESS)).thenReturn(0);
            when(act.keyUses()).thenReturn(mock(KeyUses.class));
            doAnswer(i->{response.set(i.getArgument(0));return null;}).when(node).coinsRelevant(any());
            toast=mockStatic(Toast.class, i->mock(Toast.class));
            language=mockStatic(Language.class,i->{
                if(i.getMethod().getName().equals("setText"))return null;
                if(i.getMethod().getName().equals("text"))return "unavailable";
                return i.callRealMethod();
            });
            dialogs=mockConstruction(WalletDialog.class,withSettings().defaultAnswer(RETURNS_SELF),
                    (b,c)->when(b.setPositiveButton(any(CharSequence.class),any())).thenAnswer(i->{approvals.add(i.getArgument(1));return b;}));
        }
        void request()throws Exception {call("requestRetry",group,Arrays.asList("0xaa","0xbb"),RECIPIENT);}
        void noSigning(){verify(act,never()).factory();verify(act,never()).resolveInputs(anyList(),any());verify(node,never()).publish(any(),any());}
        @Override public void close(){dialogs.close();language.close();toast.close();}
    }
    @Test public void timeoutAndElapsedTimeNeverClearPending()throws Exception {
        pending().put("0xaa",1L);pending().put("0xbb",2L);
        Method check=NftView.class.getDeclaredMethod("isPending",String.class);check.setAccessible(true);
        assertEquals(true,check.invoke(null,"0xaa"));assertEquals(Long.valueOf(1),pending().get("0xaa"));
        try(Fixture f=new Fixture()) {
            when(f.act.getResources()).thenReturn(mock(android.content.res.Resources.class));
            when(f.act.getResources().getDisplayMetrics()).thenReturn(new android.util.DisplayMetrics());
            try(MockedConstruction<android.widget.TextView> text=mockConstruction(android.widget.TextView.class);
                MockedConstruction<android.widget.ScrollView> scroll=mockConstruction(android.widget.ScrollView.class)) {
                f.call("offerRetry",f.group,RECIPIENT,"header",Arrays.asList("0xaa","0xbb"),2,Collections.emptyList());
                assertEquals(2,pending().size());verify(f.node,never()).coinsRelevant(any());f.noSigning();
            }
        }
    }
    @Test public void freshReplyOpensReviewWithoutSigningOrReleasingPresentCoins()throws Exception {
        pending().put("0xaa",1L);pending().put("0xbb",2L);
        try(Fixture f=new Fixture()) {
            f.request();assertNotNull(f.response.get());assertTrue(f.approvals.isEmpty());
            verify(f.act,never()).coins();
            f.response.get().onResult(reply(coin("0xaa")));
            assertEquals(1,f.approvals.size());assertEquals(Long.valueOf(1),pending().get("0xaa"));
            assertFalse(pending().containsKey("0xbb"));f.noSigning();
            // Closing/cancelling this new review leaves the present coin pending.
            assertTrue(pending().containsKey("0xaa"));
        }
    }
    @Test public void incompleteMalformedAndForeignRepliesNeverReleaseOrReview()throws Exception {
        List<JSONObject> bad=Arrays.asList(null,new JSONObject(),reply().put("status",false),reply().put("status","true"),
                new JSONObject().put("status",true),reply(coin("0xaa"),"bad"),reply(coin("0xaa"),coin("0xaa")),
                reply(coin("0xaa").put("address","foreign")),reply(coin("0xaa").put("tokenid","0xcd")),
                reply(coin("0xaa").put("token",JSONObject.NULL)),reply(coin("0xAA")));
        try(Fixture f=new Fixture()) {
            for(JSONObject value:bad) {
                pending().put("0xaa",1L);pending().put("0xbb",2L);f.request();f.response.get().onResult(value);
                assertEquals(String.valueOf(value),2,pending().size());assertTrue(f.approvals.isEmpty());f.noSigning();
            }
            f.request();f.response.get().onError("offline");assertEquals(2,pending().size());assertTrue(f.approvals.isEmpty());
        }
    }
    @Test public void unstampedFreshLockedEditionCannotBeRetried()throws Exception {
        pending().put("0xaa",1L);
        JSONObject c=coin("0xaa");c.getJSONObject("token").put("script",
                "LET s=PREVSTATE(0) IF s EQ 0 AND SIGNEDBY(0xEE) THEN RETURN TRUE ENDIF RETURN SAMESTATE(0 1) AND VERIFYOUT(@INPUT GETOUTADDR(@INPUT) @AMOUNT @TOKENID TRUE)");
        c.put("state",new JSONObject().put("0","0"));
        try(Fixture f=new Fixture()){f.request();f.response.get().onResult(reply(c));assertTrue(pending().containsKey("0xaa"));assertTrue(f.approvals.isEmpty());f.noSigning();}
    }
    @Test public void lateReplyAndStaleReviewCannotAuthorizeAnotherAttempt()throws Exception {
        pending().put("0xaa",1L);
        try(Fixture f=new Fixture()) {
            f.request();pending().put("0xaa",3L);f.response.get().onResult(reply(coin("0xaa")));
            assertTrue(f.approvals.isEmpty());assertEquals(Long.valueOf(3),pending().get("0xaa"));
            f.request();f.response.get().onResult(reply(coin("0xaa")));assertEquals(1,f.approvals.size());
            pending().put("0xaa",4L);f.approvals.get(0).onClick(null,0);
            assertEquals(Long.valueOf(4),pending().get("0xaa"));f.noSigning();
            f.request();f.current.set(false);f.response.get().onResult(reply());
            assertEquals(Long.valueOf(4),pending().get("0xaa"));assertEquals(1,f.approvals.size());f.noSigning();
        }
    }
    @Test public void onlyNewExplicitApprovalReleasesTheReviewedCoin()throws Exception {
        pending().put("0xaa",1L);
        try(Fixture f=new Fixture()) {
            SeedVault vault=mock(SeedVault.class);when(f.act.vault()).thenReturn(vault);
            // Stop at the signing gate. The test never constructs a transaction.
            doThrow(new SeedVault.SigningNotAllowedException("fixture refusal")).when(vault).assertSigningAllowed();
            f.request();f.response.get().onResult(reply(coin("0xaa")));assertTrue(pending().containsKey("0xaa"));
            verify(vault,never()).assertSigningAllowed();
            f.approvals.get(0).onClick(null,0);verify(vault).assertSigningAllowed();
            assertFalse(pending().containsKey("0xaa"));f.noSigning();
        }
    }
}
