package com.eurobuddha.salon;

import android.content.Context;
import android.os.Looper;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.media.MediaManifest;
import com.eurobuddha.maxima.core.media.MediaService;
import com.eurobuddha.minimacore.i18n.Language;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Salon's sealed DM and mx1 media formats over the host's existing Maxima transport. */
public final class MaximaLink {
    static final String APPLICATION = "salon_dm_v1";
    static final String MEDIA_PREFIX = "mx1:";
    static final long MESH_MAX_BYTES = MediaService.MAX_MESH_FILE_BYTES;
    private static volatile Backend backend;

    private static final class Backend {
        final Context context;
        final Supplier<MaximaNode> node;
        final Supplier<MediaService> media;
        final Executor worker, callbacks;
        boolean ready;
        Backend(Context context, Supplier<MaximaNode> node, Supplier<MediaService> media,
                Executor worker, Executor callbacks) {
            this.context=context.getApplicationContext();this.node=node;this.media=media;
            this.worker=worker;this.callbacks=callbacks;
        }
    }

    /** Installed once by Pandamonium; MinimaService retains ownership of start/stop. */
    public static void install(Context context, Supplier<MaximaNode> node,
            Supplier<MediaService> media, Executor worker, Executor callbacks) {
        backend = new Backend(context,node,media,worker,callbacks);
        refresh();
    }

    private MaximaLink() {}
    static void connect(Context ignored) { refresh(); }

    /** Host lifecycle/relay events trigger the existing durable Salon outbox retry. */
    public static void refresh() {
        Backend b=backend;
        if (b==null) return;
        boolean notify;
        synchronized(b) {
            boolean ready=ready(b);
            notify=ready && !b.ready;
            b.ready=ready;
        }
        if (notify) b.callbacks.execute(MainActivity::onMaximaReady);
    }

    private static boolean ready(Backend b) {
        MaximaNode node=b.node.get();
        return node!=null && !node.myAddresses().isEmpty();
    }
    static boolean isReady(Context ignored) { Backend b=backend;return b!=null && ready(b); }
    static String myAddresses(Context ignored) {
        Backend b=backend;MaximaNode node=b==null?null:b.node.get();
        // Never advertise the old standalone Parlons address cached by the donor APK.
        return node==null?"":String.join(",",node.myAddresses());
    }

    interface SendCb {
        void onSent(String status, boolean pending);
        void onFailed(String error);
    }

    static void sendDm(Context context, String addresses, String sealedHex, SendCb cb) {
        Backend b=backend;
        if(b==null) {cb.onFailed(Language.text(context,l10n_salon_transport_not_ready));return;}
        final byte[] payload;
        try {
            String hex=sealedHex==null?"":sealedHex.replaceFirst("^(0x|0X)","");
            if(hex.isEmpty() || hex.length()>512*1024 || (hex.length()&1)!=0 || !hex.matches("[0-9a-fA-F]+"))
                throw new IllegalArgumentException();
            payload=Hex.from(hex);
        } catch (RuntimeException invalid) {
            b.callbacks.execute(()->cb.onFailed(Language.text(b.context,l10n_salon_transport_invalid_message)));return;
        }
        try {
            b.worker.execute(()->{
                String accepted=null;
                try {
                    MaximaNode node=b.node.get();
                    if(node!=null && addresses!=null) {
                        int tried=0;
                        for(String address:addresses.split(",")) {
                            address=address.trim();if(address.isEmpty())continue;
                            if(++tried>8)break;
                            try {
                                if(address.regionMatches(true,0,"MAX#",0,4)) address=node.resolvePermanent(address);
                                MaximaSender.Result result=node.sendRaw(address,APPLICATION,payload);
                                if(result!=null && ("OK".equals(result.statusName)||"UNKNOWN".equals(result.statusName))) {
                                    accepted=result.statusName;break;
                                }
                            } catch (Exception routeUnavailable) { /* Try another advertised relay route. */ }
                        }
                    }
                } catch (RuntimeException unavailable) { /* Leave the caller's persisted outbox pending. */ }
                final String status=accepted;
                // The transport owns its timeout. Do not create a second, earlier timeout/retry race.
                b.callbacks.execute(()->{
                    if(status!=null) cb.onSent(status,"UNKNOWN".equals(status));
                    else cb.onFailed(Language.text(b.context,l10n_salon_transport_send_failed));
                });
            });
        } catch (RejectedExecutionException busy) {
            b.callbacks.execute(()->cb.onFailed(Language.text(b.context,l10n_salon_transport_busy)));
        }
    }

    /** Already on Maxima's ordered background receive lane; reuse signature-checked intake. */
    public static void onMessage(MaximaMessage message, MiniData msgid) {
        Backend b=backend;
        if(b==null || message==null || message.mApplication==null
                || !APPLICATION.equals(message.mApplication.toString()) || message.mData==null) return;
        // Receiving traffic must not create a Salon identity in an unused integrated app.
        if(SalonStore.get(b.context,"msgseed").isEmpty())return;
        byte[] data=message.mData.getBytes();
        if(data.length==0 || data.length>256*1024)return;
        SalonNotifyReceiver.intakeDm(b.context,Hex.to(data),
                "mx-"+(msgid==null?message.msgid().to0xString():msgid.to0xString()));
    }

    static boolean isMediaRef(String value) { return value!=null && value.startsWith(MEDIA_PREFIX); }
    private static Backend mediaBackend() throws Exception {
        Backend b=backend;
        if(b==null)throw new IllegalStateException("Embedded Maxima transport unavailable");
        if(Looper.getMainLooper()!=null && Looper.myLooper()==Looper.getMainLooper())
            throw new IllegalStateException(Language.text(b.context,l10n_salon_transport_background_only));
        return b;
    }
    static String putMedia(byte[] bytes,String mime) throws Exception {
        Backend b=mediaBackend();
        if(bytes==null || bytes.length==0 || bytes.length>MESH_MAX_BYTES)
            throw new IllegalArgumentException(Language.text(b.context,l10n_salon_transport_media_size));
        MediaService service=b.media.get();
        if(service==null || !ready(b))throw new IllegalStateException(Language.text(b.context,l10n_salon_transport_not_ready));
        MediaManifest manifest=service.publish(bytes,mime==null?"application/octet-stream":mime);
        return MEDIA_PREFIX+Base64.getUrlEncoder().encodeToString(manifest.encode().getBytes(StandardCharsets.UTF_8));
    }
    static byte[] getMedia(String ref) throws Exception {
        Backend b=mediaBackend();
        if(!isMediaRef(ref) || ref.length()>128*1024)
            throw new IllegalArgumentException(Language.text(b.context,l10n_salon_transport_invalid_media));
        MediaManifest manifest=MediaManifest.decode(new String(Base64.getUrlDecoder().decode(ref.substring(4)),StandardCharsets.UTF_8));
        if(manifest==null)throw new IllegalArgumentException(Language.text(b.context,l10n_salon_transport_invalid_media));
        MediaService service=b.media.get();
        if(service==null)throw new IllegalStateException(Language.text(b.context,l10n_salon_transport_not_ready));
        // The service verifies chunk hashes, size, AES-GCM authentication and the full-file hash.
        // A cached file can still be opened while the node has no live relay address.
        return service.fetch(manifest);
    }
}
