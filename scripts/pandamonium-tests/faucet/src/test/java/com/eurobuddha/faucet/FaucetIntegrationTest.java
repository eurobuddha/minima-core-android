package com.eurobuddha.faucet;

import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.minimacore.i18n.Language;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FaucetIntegrationTest {
    static void set(MainActivity a,String key,Object value)throws Exception {
        Field f=MainActivity.class.getDeclaredField(key);f.setAccessible(true);f.set(a,value);
    }
    static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);
    }
    static class Fixture {
        final MainActivity activity=mock(MainActivity.class,CALLS_REAL_METHODS);
        final DirectNodeApi api=mock(DirectNodeApi.class);
        final EditText address=mock(EditText.class);final View banner=mock(View.class);
        final List<Runnable> posted=new ArrayList<>(),timeouts=new ArrayList<>();
        MinimaAPIListener callback;
        Fixture()throws Exception {
            Handler handler=mock(Handler.class);
            when(handler.post(any())).thenAnswer(i->{posted.add(i.getArgument(0));return true;});
            when(handler.postDelayed(any(),anyLong())).thenAnswer(i->{timeouts.add(i.getArgument(0));return true;});
            doAnswer(i->{callback=i.getArgument(1);return null;}).when(api).Command(eq("getaddress"),any());
            set(activity,"mMain",handler);set(activity,"mApi",api);set(activity,"mAddress",address);
            set(activity,"mGetAddrBtn",mock(Button.class));set(activity,"mStatus",mock(TextView.class));set(activity,"mPairingBanner",banner);
        }
        void start()throws Exception {call(activity,"getMyAddress",new Class<?>[0]);}
        void reply(JSONObject json){callback.response(json);posted.remove(0).run();}
    }
    static JSONObject reply(Object status)throws Exception {
        return new JSONObject().put("status",status).put("response",new JSONObject().put("miniaddress","Mx1234567890ABCDEF"));
    }
    @Test public void addressComesFromEmbeddedClientOnlyAfterSuccessfulReply()throws Exception {
        try(MockedStatic<Language> language=mockStatic(Language.class)) {
            Fixture f=new Fixture();f.start();verify(f.api).Command(eq("getaddress"),any());
            verifyNoInteractions(f.address);f.reply(reply(true));verify(f.address).setText("Mx1234567890ABCDEF");
            verify(f.banner).setVisibility(View.GONE);
        }
    }
    @Test public void failedPendingAndMalformedRepliesCannotOverwriteTheAddress()throws Exception {
        try(MockedStatic<Language> language=mockStatic(Language.class)) {
            for(JSONObject bad:new JSONObject[]{reply(false),reply("true"),reply(true).put("pending",true),new JSONObject()}) {
                Fixture f=new Fixture();f.start();f.reply(bad);verifyNoInteractions(f.address);
            }
        }
    }
    @Test public void closedScreenIgnoresLateRepliesAndTimeouts()throws Exception {
        try(MockedStatic<Language> language=mockStatic(Language.class)) {
            Fixture f=new Fixture();f.start();set(f.activity,"mDestroyed",true);
            f.reply(reply(true));f.timeouts.get(0).run();verifyNoInteractions(f.address,f.banner);
        }
    }
    @Test public void replyAfterTimeoutDoesNotReplaceAddress()throws Exception {
        try(MockedStatic<Language> language=mockStatic(Language.class)) {
            Fixture f=new Fixture();f.start();f.timeouts.get(0).run();f.reply(reply(true));verifyNoInteractions(f.address);
        }
    }
    @Test public void responseReadIsUtf8BoundedAndClosesItsStream()throws Exception {
        class Stream extends ByteArrayInputStream {
            boolean closed;Stream(byte[] data){super(data);}
            @Override public void close()throws IOException {closed=true;super.close();}
        }
        Stream small=new Stream("Готово 中文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("Готово 中文",call(null,"readAll",new Class<?>[]{InputStream.class},small));assertTrue(small.closed);
        Stream large=new Stream(new byte[65537]);
        InvocationTargetException error=assertThrows(InvocationTargetException.class,()->call(null,"readAll",new Class<?>[]{InputStream.class},large));
        assertTrue(error.getCause() instanceof IOException);assertTrue(large.closed);
    }
}
