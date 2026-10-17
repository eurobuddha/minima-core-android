package com.eurobuddha.utxo;
import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.utxo.UtxoLanguagesTest.*;
/** Delayed public-fixture callbacks; never connects to a node or signs. */
@RunWith(AndroidJUnit4.class)
public class UtxoChangeAddressTest {
    private final UtxoLanguagesTest guards=new UtxoLanguagesTest();
    @Before public void prepare(){guards.prepare();}
    @After public void reset(){guards.reset();}
    private static final String AUTO="0x"+"ab".repeat(32), MANUAL="0x"+"cd".repeat(32), NEW="0x"+"ef".repeat(32);
    static class PendingNode extends FixtureNode {
        final List<Cb> replies=new ArrayList<>();PendingNode(Context c){super(c);}
        @Override public void cmd(String cmd,Cb cb){if("getaddress".equals(cmd)){requests.add(cmd);replies.add(cb);}else super.cmd(cmd,cb);}
        void reply(int index,String addr){replies.get(index).onResult(object("status",true,"response",object("address",addr)));}
    }
    private PendingNode fixture(MainActivity a){freeze(a);seedDisplay(a);a.node().onDestroy();PendingNode n=new PendingNode(a);set(a,"node",n);return n;}
    private static void fetch(MainActivity a,boolean user){call(send(a),"fetchChange",new Class<?>[]{boolean.class},user);}
    @Test public void manualEditAndRestoredDraftInvalidatePendingResults(){
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();s.onActivity(a->{PendingNode n=fixture(a);fetch(a,false);input(a,R.id.pm_utxo_sendChange).setText(MANUAL);input(a,R.id.pm_utxo_sendChange).setSelection(7);n.reply(0,AUTO);assertEquals(MANUAL,input(a,R.id.pm_utxo_sendChange).getText().toString());assertEquals(7,input(a,R.id.pm_utxo_sendChange).getSelectionStart());assertEquals(true,get(send(a),"changeTouched"));assertEquals(false,get(send(a),"changeFetching"));fetch(a,true);send(a).setFieldValues(new String[]{MANUAL,"2",NEW,"0"});n.reply(1,AUTO);assertEquals(NEW,input(a,R.id.pm_utxo_sendChange).getText().toString());});
        }
    }
    @Test public void newestNextWinsAndStaleFailureDoesNotFinishNewRequest(){
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();s.onActivity(a->{PendingNode n=fixture(a);fetch(a,true);fetch(a,true);n.replies.get(0).onError("late error");assertEquals(true,get(send(a),"changeFetching"));n.reply(1,NEW);n.reply(0,AUTO);assertEquals(NEW,input(a,R.id.pm_utxo_sendChange).getText().toString());assertEquals(true,get(send(a),"changeTouched"));assertEquals(false,get(send(a),"changeFetching"));input(a,R.id.pm_utxo_sendChange).setText("");send(a).refresh();assertEquals(2,n.requests.size());});
        }
    }
    @Test public void languageChangeKeepsAnUneditedPendingRequestValid(){
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> node=new AtomicReference<>();s.onActivity(a->{node.set(fixture(a));input(a,R.id.pm_utxo_sendChange).setText("");set(send(a),"changeTouched",false);fetch(a,false);Language.select(a,"uk");});settle();s.onActivity(a->{node.get().reply(0,AUTO);assertEquals(AUTO,input(a,R.id.pm_utxo_sendChange).getText().toString());assertEquals(false,get(send(a),"changeTouched"));assertEquals(false,get(send(a),"changeFetching"));assertEquals(Collections.singletonList("getaddress"),node.get().requests);});
        }
    }
}
