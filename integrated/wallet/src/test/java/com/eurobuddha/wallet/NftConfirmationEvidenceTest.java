package com.eurobuddha.wallet;

import android.os.Handler;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import com.eurobuddha.minimacore.i18n.Language;
import org.mockito.MockedStatic;
import com.eurobuddha.wallet.comms.NodeApi;
import com.eurobuddha.wallet.comms.NodeLink;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedConstruction;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Reuses NFT Wallet's departure-evidence fixture on the actual minimaWallet polling path. */
public class NftConfirmationEvidenceTest {
    @Test public void onlySuccessfulCompleteCoinListsCanConfirmDeparture() throws Exception {
        List<Runnable> polls=new ArrayList<>();
        AtomicReference<JSONObject> response=new AtomicReference<>();
        AlertDialog dialog=mock(AlertDialog.class);
        try(MockedStatic<Language> bindings=mockStatic(Language.class, invocation -> {
                // Rendering is covered on-device; this fixture tests only evidence and scheduling.
                if (invocation.getMethod().getName().equals("setText")) return null;
                return invocation.callRealMethod();
            }); MockedConstruction<Handler> handlers=mockConstruction(Handler.class,(h,c)->{
                when(h.post(any(Runnable.class))).thenAnswer(i->{polls.add(i.getArgument(0));return true;});
                when(h.postDelayed(any(Runnable.class),anyLong())).thenAnswer(i->{polls.add(i.getArgument(0));return true;});
            });
            MockedConstruction<TextView> text=mockConstruction(TextView.class);
            MockedConstruction<ScrollView> scroll=mockConstruction(ScrollView.class);
            MockedConstruction<WalletDialog> builders=mockConstruction(WalletDialog.class,
                withSettings().defaultAnswer(RETURNS_SELF),(b,c)->when(b.create()).thenReturn(dialog))) {
            MainActivity activity=mock(MainActivity.class,RETURNS_DEEP_STUBS);
            // Density lookup in dp() needs a concrete metrics object, not a stubbed final field.
            when(activity.getResources().getDisplayMetrics()).thenReturn(new android.util.DisplayMetrics());
            NodeLink node=mock(NodeLink.class);when(activity.node()).thenReturn(node);
            doAnswer(i->{((NodeApi.Cb)i.getArgument(0)).onResult(response.get());return null;})
                .when(node).coinsRelevant(any(NodeApi.Cb.class));
            NftView view=mock(NftView.class,CALLS_REAL_METHODS);doNothing().when(view).refresh();
            Field act=BaseView.class.getDeclaredField("act");act.setAccessible(true);act.set(view,activity);
            java.util.concurrent.atomic.AtomicBoolean authorized=new java.util.concurrent.atomic.AtomicBoolean(true);
            Field session=NftView.class.getDeclaredField("session");session.setAccessible(true);session.set(view,(java.util.function.BooleanSupplier)authorized::get);
            List<String> remaining=new ArrayList<>(Arrays.asList("0xaa","0xbb"));
            Class<?> group=Class.forName("com.eurobuddha.wallet.NftView$Group");
            Method track=NftView.class.getDeclaredMethod("trackConfirmations",group,String.class,CharSequence.class,List.class,List.class);
            track.setAccessible(true);track.invoke(view,null,"fixture-recipient","fixture-header",remaining,new ArrayList<>(Arrays.asList("0x11","0x22")));
            List<JSONObject> invalid=new ArrayList<>();
            invalid.add(null);
            invalid.add(new JSONObject());
            invalid.add(new JSONObject().put("status",false).put("response",new JSONArray()));
            invalid.add(new JSONObject().put("status","true").put("response",new JSONArray()));
            invalid.add(new JSONObject().put("status",true));
            invalid.add(new JSONObject().put("status",true).put("response",new JSONObject()));
            for(Object bad:new Object[]{JSONObject.NULL,"bad",new JSONObject(),new JSONObject().put("coinid","bad id"),new JSONObject().put("coinid",4)})
                invalid.add(new JSONObject().put("status",true).put("response",new JSONArray().put(new JSONObject().put("coinid","0xaa")).put(bad)));
            invalid.add(new JSONObject().put("status",true).put("response",new JSONArray().put(new JSONObject().put("coinid","0xaa")).put(new JSONObject().put("coinid","0xAA"))));
            for(JSONObject bad:invalid) {
                response.set(bad);assertEquals(1,polls.size());polls.remove(0).run();
                assertEquals(Arrays.asList("0xaa","0xbb"),remaining);
                verify(dialog,never()).dismiss();verify(activity,never()).reload();
            }
            response.set(new JSONObject().put("status",true).put("response",new JSONArray().put(new JSONObject().put("coinid","0xAA")).put(new JSONObject().put("coinid","0xBB"))));
            polls.remove(0).run();assertEquals(Arrays.asList("0xaa","0xbb"),remaining);
            response.set(new JSONObject().put("status",true).put("response",new JSONArray().put(new JSONObject().put("coinid","0xbb"))));
            polls.remove(0).run();assertEquals(Arrays.asList("0xbb"),remaining);
            verify(dialog,never()).dismiss();verify(activity,never()).reload();
            response.set(new JSONObject().put("status",true).put("response",new JSONArray()));
            polls.remove(0).run();assertTrue(remaining.isEmpty());assertTrue(polls.isEmpty());
            verify(dialog).dismiss();verify(activity).reload();verify(view).refresh();
            assertEquals(2,builders.constructed().size());
            verify(builders.constructed().get(1)).setTitle(any(CharSequence.class));
            clearInvocations(node);
            List<String> abandoned=new ArrayList<>(Arrays.asList("0xcc"));
            track.invoke(view,null,"fixture-recipient","fixture-header",abandoned,new ArrayList<>());
            authorized.set(false);assertEquals(1,polls.size());polls.remove(0).run();
            assertEquals(Arrays.asList("0xcc"),abandoned);assertTrue(polls.isEmpty());verifyNoInteractions(node);
        }
    }
}
