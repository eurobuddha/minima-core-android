package com.eurobuddha.salon;

import android.content.Context;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.net.Frame;
import com.eurobuddha.maxima.core.media.MediaService;
import com.eurobuddha.maxima.core.store.BlobStore;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;

public class EmbeddedMaximaTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private Context context;
    private MaximaNode node;
    private MediaService media;
    private MockedStatic<Language> language;
    @Before public void setup() {
        context=mock(Context.class);when(context.getApplicationContext()).thenReturn(context);
        node=mock(MaximaNode.class);when(node.myAddresses()).thenReturn(Arrays.asList("MxHost@relay:9001"));
        media=mock(MediaService.class);
        language=mockStatic(Language.class);
        language.when(()->Language.text(any(),anyInt(),any())).thenReturn("unavailable");
        MaximaLink.install(context,()->node,()->media,Runnable::run,Runnable::run);
    }
    @After public void close() {language.close();MaximaLink.install(context,()->null,()->null,Runnable::run,Runnable::run);}
    @Test public void sendRunsOnProvidedWorkerAndPreservesSealedBytesAndApplication() throws Exception {
        List<Runnable> work=new ArrayList<>(),callbacks=new ArrayList<>();
        MaximaLink.install(context,()->node,()->media,work::add,callbacks::add);callbacks.clear();
        when(node.sendRaw(anyString(),anyString(),any())).thenReturn(MaximaSender.Result.of(Frame.RESPONSE_OK));
        MaximaLink.SendCb cb=mock(MaximaLink.SendCb.class);
        MaximaLink.sendDm(context,"MxPeer@relay:9001","0x0123abcd",cb);
        verify(node,never()).sendRaw(anyString(),anyString(),any());verifyNoInteractions(cb);
        work.remove(0).run();verifyNoInteractions(cb);
        callbacks.remove(0).run();
        verify(node).sendRaw(eq("MxPeer@relay:9001"),eq("salon_dm_v1"),aryEq(new byte[]{1,35,(byte)171,(byte)205}));
        verify(cb).onSent("OK",false);verify(cb,never()).onFailed(any());
        verify(context,never()).sendBroadcast(any());
    }
    @Test public void unknownIsMailboxAcceptanceAndDoesNotDuplicateAcrossRoutes() throws Exception {
        when(node.sendRaw(anyString(),anyString(),any())).thenReturn(MaximaSender.Result.of(Frame.RESPONSE_UNKNOWN));
        MaximaLink.SendCb cb=mock(MaximaLink.SendCb.class);
        MaximaLink.sendDm(context,"first,second","ab",cb);
        verify(cb).onSent("UNKNOWN",true);verify(cb,never()).onFailed(any());
        verify(node,times(1)).sendRaw(anyString(),eq("salon_dm_v1"),any());
    }
    @Test public void hardFailureTriesNextRouteAndResolvesPermanentAddress() throws Exception {
        when(node.sendRaw(eq("old"),anyString(),any())).thenThrow(new java.io.IOException("old route"));
        when(node.resolvePermanent("MAX#peer")).thenReturn("resolved");
        when(node.sendRaw(eq("resolved"),anyString(),any())).thenReturn(MaximaSender.Result.of(Frame.RESPONSE_OK));
        MaximaLink.SendCb cb=mock(MaximaLink.SendCb.class);
        MaximaLink.sendDm(context,"old, MAX#peer","ab",cb);
        verify(node).resolvePermanent("MAX#peer");verify(cb).onSent("OK",false);
    }
    @Test public void overloadFailsWithoutDispatchingAndLeavesOutboxDecisionToCaller() throws Exception {
        MaximaLink.install(context,()->node,()->media,task->{throw new RejectedExecutionException();},Runnable::run);
        MaximaLink.SendCb cb=mock(MaximaLink.SendCb.class);
        MaximaLink.sendDm(context,"peer","ab",cb);
        verify(cb).onFailed(any());verify(cb,never()).onSent(anyString(),anyBoolean());
        verify(node,never()).sendRaw(anyString(),anyString(),any());
    }
    @Test public void malformedHexAndEmptyAddressListNeverSend() throws Exception {
        for(String hex:new String[]{"a","zz","", "0x"}) {
            MaximaLink.SendCb cb=mock(MaximaLink.SendCb.class);MaximaLink.sendDm(context,"peer",hex,cb);verify(cb).onFailed(any());
        }
        MaximaLink.SendCb cb=mock(MaximaLink.SendCb.class);MaximaLink.sendDm(context,", ,","ab",cb);verify(cb).onFailed(any());
        verify(node,never()).sendRaw(anyString(),anyString(),any());
    }
    @Test public void readinessTracksHostRestartAndNeverUsesStandaloneCachedAddress() {
        AtomicReference<MaximaNode> current=new AtomicReference<>();
        try(MockedStatic<MainActivity> activity=mockStatic(MainActivity.class)) {
            MaximaLink.install(context,current::get,()->media,Runnable::run,Runnable::run);
            assertFalse(MaximaLink.isReady(context));assertEquals("",MaximaLink.myAddresses(context));
            current.set(node);MaximaLink.refresh();MaximaLink.refresh();
            assertTrue(MaximaLink.isReady(context));assertEquals("MxHost@relay:9001",MaximaLink.myAddresses(context));
            activity.verify(MainActivity::onMaximaReady,times(1));
            current.set(null);MaximaLink.refresh();assertFalse(MaximaLink.isReady(context));
            assertEquals("",MaximaLink.myAddresses(context));
            current.set(node);MaximaLink.refresh();activity.verify(MainActivity::onMaximaReady,times(2));
            verify(context,never()).getSharedPreferences(anyString(),anyInt());
        }
    }
    @Test public void inboundSalonPayloadUsesExistingSignatureCheckedIntakeAndStableTransportId() {
        try(MockedStatic<SalonStore> store=mockStatic(SalonStore.class);MockedStatic<SalonNotifyReceiver> intake=mockStatic(SalonNotifyReceiver.class)) {
            store.when(()->SalonStore.get(context,"msgseed")).thenReturn("wrapped");
            MaximaLink.onMessage(message("salon_dm_v1"),new MiniData("0x1234"));
            intake.verify(()->SalonNotifyReceiver.intakeDm(eq(context),eq("0123"),eq("mx-0x1234")));
            MaximaLink.onMessage(message("maxima_chat_v1"),new MiniData("0x5678"));
            intake.verifyNoMoreInteractions();
        }
    }
    @Test public void unusedSalonDoesNotCreateKeysForIncomingTraffic() {
        try(MockedStatic<SalonStore> store=mockStatic(SalonStore.class);MockedStatic<SalonNotifyReceiver> intake=mockStatic(SalonNotifyReceiver.class)) {
            store.when(()->SalonStore.get(context,"msgseed")).thenReturn("");
            MaximaLink.onMessage(message("salon_dm_v1"),new MiniData("0x1234"));
            intake.verifyNoInteractions();
        }
    }
    @Test public void mediaRoundTripsFullBytesThroughExistingServiceAndManifestEvenWhenOfflineLater() throws Exception {
        MediaService service=new MediaService(null,new BlobStore(temporary.newFolder(),2*1024*1024));
        MaximaLink.install(context,()->node,()->service,Runnable::run,Runnable::run);
        byte[] bytes=new byte[500*1024];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31+7);
        String ref=MaximaLink.putMedia(bytes,"image/png");assertTrue(ref.startsWith("mx1:"));
        when(node.myAddresses()).thenReturn(Collections.emptyList());
        assertArrayEquals(bytes,MaximaLink.getMedia(ref));verify(context,never()).sendBroadcast(any());
    }
    @Test public void invalidOrOversizeMediaIsRejectedBeforeServiceWork() {
        assertThrows(IllegalArgumentException.class,()->MaximaLink.putMedia(new byte[16*1024*1024+1],"image/png"));
        assertThrows(IllegalArgumentException.class,()->MaximaLink.getMedia("invalid"));
        assertThrows(IllegalArgumentException.class,()->MaximaLink.getMedia("mx1:"+"a".repeat(128*1024)));
        verifyNoInteractions(media);
    }
    private MaximaMessage message(String app) {
        MaximaMessage msg=new MaximaMessage();msg.mApplication=new MiniString(app);msg.mData=new MiniData("0x0123");return msg;
    }
}
