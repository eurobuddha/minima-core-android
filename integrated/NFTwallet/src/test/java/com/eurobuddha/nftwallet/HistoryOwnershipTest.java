package com.eurobuddha.nftwallet;
import android.widget.TextView;
import com.eurobuddha.minimacore.i18n.Language;
import org.json.*;
import org.junit.*;
import org.mockito.MockedStatic;
import java.lang.reflect.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.object;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Real HistoryView badge callback with mocked transport and labels; no wallet or node operations. */
public class HistoryOwnershipTest {
    static final String ADDRESS="0x"+"ab".repeat(32),MX="Mx"+"Aa".repeat(24);
    MainActivity act;NodeApi node;HistoryView view;TextView badge;NodeApi.Cb pending;
    Language.Message yes,no,unknown;MockedStatic<Language> language;MockedStatic<Design> design;
    @Before public void setup()throws Exception {
        act=mock(MainActivity.class);node=mock(NodeApi.class);badge=mock(TextView.class);when(act.node()).thenReturn(node);view=view();
        doAnswer(c->{pending=c.getArgument(1);return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        language=mockStatic(Language.class);design=mockStatic(Design.class);yes=mock(Language.Message.class);no=mock(Language.Message.class);unknown=mock(Language.Message.class);
        language.when(()->Language.msg(l10n_history_yours)).thenReturn(yes);language.when(()->Language.msg(l10n_nft_history_not_yours)).thenReturn(no);language.when(()->Language.msg(l10n_nft_history_unverified)).thenReturn(unknown);
    }
    @After public void close(){design.close();language.close();}
    HistoryView view()throws Exception {HistoryView h=mock(HistoryView.class,CALLS_REAL_METHODS);Field f=BaseView.class.getDeclaredField("act");f.setAccessible(true);f.set(h,act);return h;}
    void mark(String address)throws Exception {Method m=HistoryView.class.getDeclaredMethod("markOwnership",String.class,TextView.class);m.setAccessible(true);m.invoke(view,address,badge);}
    JSONObject reply(boolean mine){return object("status",true,"response",object("0x",ADDRESS,"Mx",MX,"relevant",mine));}
    @Test public void reopeningSameAddressAlwaysChecksTheCurrentNode()throws Exception {
        mark(ADDRESS);pending.onResult(reply(true));language.verify(()->Language.setText(badge,yes));language.clearInvocations();
        mark(ADDRESS);pending.onResult(reply(false));language.verify(()->Language.setText(badge,no));verify(node,times(2)).cmd(eq("checkaddress address:"+ADDRESS),any());verify(act,never()).myAddresses();
    }
    @Test public void aNewHistoryViewDoesNotInheritOwnershipFromAnEarlierSession()throws Exception {
        mark(ADDRESS);pending.onResult(reply(true));view=view();language.clearInvocations();mark(ADDRESS);pending.onResult(reply(false));language.verify(()->Language.setText(badge,no));verify(node,times(2)).cmd(eq("checkaddress address:"+ADDRESS),any());
    }
    @Test public void missingFailedAndMismatchedRepliesStayUnverified()throws Exception {
        for(JSONObject bad:new JSONObject[]{null,object("status",false),object("status",true),object("status","true","response",reply(true).getJSONObject("response")),object("status",true,"response",object("0x",ADDRESS)),object("status",true,"response",object("0x",ADDRESS,"relevant","false")),object("status",true,"response",object("0x","0x"+"cd".repeat(32),"relevant",true))}){
            language.clearInvocations();mark(ADDRESS);pending.onResult(bad);language.verify(()->Language.setText(badge,unknown));language.verify(()->Language.setText(badge,no),never());language.verify(()->Language.setText(badge,yes),never());
        }
    }
    @Test public void addressFormsFollowTheirExistingCaseRules()throws Exception {
        mark("0x"+"AB".repeat(32));pending.onResult(reply(true));language.verify(()->Language.setText(badge,yes));
        language.clearInvocations();mark(MX);pending.onResult(reply(false));language.verify(()->Language.setText(badge,no));
        language.clearInvocations();mark(MX);JSONObject bad=reply(true);bad.getJSONObject("response").put("Mx",MX.toLowerCase(java.util.Locale.ROOT));pending.onResult(bad);language.verify(()->Language.setText(badge,unknown));
    }
    @Test public void invalidAddressesCannotReachTheNodeAndTimeoutDoesNotMeanNotYours()throws Exception {
        mark(ADDRESS+";send");verifyNoInteractions(node);language.verify(()->Language.setText(badge,""));
        mark(ADDRESS);pending.onError("timeout");language.verify(()->Language.setText(badge,unknown));language.verify(()->Language.setText(badge,no),never());
    }
}
