package com.eurobuddha.nftwallet;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftShellLanguagesTest.*;
/** Public display fixtures only; no signatures, posting commands or persistent distribution job. */
@RunWith(AndroidJUnit4.class)
public class NftTransactionLanguagesTest {
    private final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    @Before public void prepare(){guards.prepare();}
    @After public void reset(){guards.reset();}
    @Test public void uncertainSendMessageChangesLanguageWithoutChangingDraftOrSending()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<String[]> draft=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));seedDisplay(a);a.goToTab(MainActivity.TAB_SEND);SendView send=send(a);draft.set(send.fieldValues());call(send,"status",new Class<?>[]{CharSequence.class,boolean.class},NftMessages.message(TransactionEvidence.UNKNOWN),false);node.get().requests.clear();});
            android.os.SystemClock.sleep(30000); // Drain queued stopped-node startup toasts before public captures.
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                exact(a.getWindow().getDecorView(),NftMessages.message(TransactionEvidence.UNKNOWN));assertArrayEquals(draft.get(),send(a).fieldValues());assertTrue(node.get().requests.isEmpty());
                TextView label=a.findViewById(R.id.pm_nftwallet_sendStatus);assertTrue(label.getLineCount()>0);assertEquals(0,label.getLayout().getEllipsisCount(label.getLineCount()-1));android.graphics.Rect visible=new android.graphics.Rect();assertTrue(label.getGlobalVisibleRect(visible));assertEquals("Whole outcome warning must be visible",label.getHeight(),visible.height());
                for(String message:new String[]{TransactionEvidence.INVALID,TransactionEvidence.UNKNOWN,TransactionEvidence.SAVE_FAILED,TransactionEvidence.DISTRIBUTION_UNKNOWN})assertTrue(NftMessages.message(message) instanceof Language.Message);
            });guards.screenshot("nft-transaction-unknown-"+code);}
        }
    }
    @Test public void uncertainDistributionStaysPausedAcrossLanguageChanges()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<DistributeJob> job=new AtomicReference<>();AtomicReference<String> state=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));DistributeJob j=new DistributeJob();j.tokenid="0x00";j.tokenName="Minima";j.per="1";j.remaining.add("0x"+"12".repeat(32));j.waiting=j.uncertain=true;job.set(j);state.set(j.toJson().toString());set(a.distribute(),"job",j);a.goToTab(MainActivity.TAB_WALLET);((BaseView[])get(a,"views"))[MainActivity.TAB_WALLET].refresh();node.get().requests.clear();});
            android.os.SystemClock.sleep(30000); // Drain queued stopped-node startup toasts before public captures.
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                exact(a.getWindow().getDecorView(),NftMessages.message(TransactionEvidence.DISTRIBUTION_UNKNOWN));a.distribute().onCoinsUpdated();assertEquals(state.get(),job.get().toJson().toString());assertNull(DistributeJob.load(a));assertTrue(node.get().requests.isEmpty());
            });guards.screenshot("nft-distribution-unknown-"+code);}
            scenario.onActivity(a->set(a.distribute(),"job",null));
        }
    }
}
