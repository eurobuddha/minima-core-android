package com.eurobuddha.statenft;
import com.eurobuddha.minimacore.i18n.Language;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.OutputStream;

/**
 * FILTR on Android: the same editor the MiniDapp ships (assets/filtr — the
 * engine bundle is unchanged; display labels follow the shared selector),
 * hosted in a WebView with three native bridges: save to Photos, send the
 * flattened image to the Single-NFT wizard, send it to the Photo Cartoon
 * pack. The result belongs only to the Activity request that opened this editor.
 */
public class FiltrActivity extends Activity {

    private String requestId;
    private volatile boolean handoffClosed;
    private boolean handoffSent;
    private final java.util.concurrent.atomic.AtomicBoolean sending = new java.util.concurrent.atomic.AtomicBoolean();

    private ValueCallback<Uri[]> filePick;
    private WebView web;
    private final Runnable languageChanged = this::refreshLanguage;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestId = getIntent().getStringExtra(FiltrHandoff.ID);
        if (!FiltrHandoff.validId(requestId)) { finish(); return; }
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                                       FileChooserParams params) {
                if (filePick != null) filePick.onReceiveValue(null);
                filePick = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                startActivityForResult(Intent.createChooser(i, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_choose_image)), 7101);
                return true;
            }
        });
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { refreshLanguage(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return !"file:///android_asset/filtr/filtr.html".equals(request.getUrl().toString());
            }
        });
        Language.addListener(languageChanged);
        web.addJavascriptInterface(new Bridge(), "AndroidFiltr");
        web.loadUrl("file:///android_asset/filtr/filtr.html");

        // Edge-to-edge (forced on Android 15 / SDK 35): without inset handling
        // the WebView draws UNDER the status bar and the nav bar, so FILTR's top
        // action bar and bottom category bar land off-screen and unreachable.
        // A WebView silently ignores setPadding for its own content layout (unlike
        // a plain ViewGroup — this is why padding the WebView directly did nothing),
        // so wrap it in a FrameLayout and pad the FRAME by the system-bar +
        // display-cutout insets (and the IME when open). The frame's paper
        // background fills the inset strips; MainActivity uses the same pattern.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#F2F1EC")); // --paper
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WindowInsetsControllerCompat wic =
                new WindowInsetsControllerCompat(getWindow(), root);
        wic.setAppearanceLightStatusBars(true);      // dark icons on the cream bar
        wic.setAppearanceLightNavigationBars(true);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(sys.left, sys.top, sys.right, Math.max(sys.bottom, ime.bottom));
            return insets;
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 7101 || filePick == null) return;
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        filePick.onReceiveValue(uri == null ? null : new Uri[]{uri});
        filePick = null;
    }

    private void refreshLanguage() {
        if (web != null && "file:///android_asset/filtr/filtr.html".equals(web.getUrl())) {
            web.evaluateJavascript("window.FiltrI18n && FiltrI18n.setLanguage(" +
                    org.json.JSONObject.quote(Language.code()) + ");", null);
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        refreshLanguage();
    }

    @Override protected void onResume() { super.onResume(); refreshLanguage(); }

    @Override protected void onDestroy() {
        handoffClosed = true;
        if (!handoffSent && requestId != null) FiltrHandoff.discard(this,requestId);
        Language.removeListener(languageChanged);
        if (filePick != null) { filePick.onReceiveValue(null); filePick = null; }
        if (web != null) { web.removeJavascriptInterface("AndroidFiltr"); web.destroy(); web = null; }
        super.onDestroy();
    }

    /** Called by WebView's bridge thread; only a tiny result crosses Binder. */
    private void sendResult(String mode, String b64) {
        if (handoffClosed || !sending.compareAndSet(false,true)) return;
        try {
            FiltrHandoff.write(this,requestId,mode,b64);
            runOnUiThread(() -> {
                if (handoffClosed || isFinishing() || isDestroyed()) { FiltrHandoff.discard(this,requestId); return; }
                handoffSent = true;
                setResult(RESULT_OK,new Intent().putExtra(FiltrHandoff.ID,requestId).putExtra(FiltrHandoff.MODE,mode));
                finish();
            });
        } catch (Exception failure) {
            FiltrHandoff.discard(this,requestId); sending.set(false);
            runOnUiThread(() -> {
                if (!handoffClosed && !isFinishing() && !isDestroyed())
                    Toast.makeText(this,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_filtr_failed),Toast.LENGTH_SHORT).show();
            });
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean saving = new java.util.concurrent.atomic.AtomicBoolean();

    private void saveImage(String name,String b64) {
        if(handoffClosed||!saving.compareAndSet(false,true))return;
        final android.content.Context ctx=getApplicationContext();
        try{ArtworkExport.execute(()->{
            boolean saved=false;
            try{
                ArtworkExport.check(()->handoffClosed);
                ArtworkExport.Encoded image=ArtworkExport.filtr(b64);
                String base=name==null?"filtr":name.replaceFirst("\\.[^.]*$","");
                ArtworkExport.save(ctx,ArtworkExport.filename(base,image.extension),image.mime,
                        out->out.write(image.bytes),()->handoffClosed);
                saved=true;
            }catch(Exception failure){}
            final boolean success=saved;
            runOnUiThread(()->exportFinished(success));
        });}catch(java.util.concurrent.RejectedExecutionException full){runOnUiThread(()->exportFinished(false));}
    }
    private void exportFinished(boolean success) {
        saving.set(false);
        if(handoffClosed||isFinishing()||isDestroyed())return;
        Toast.makeText(this,Language.msg(success
                ?com.eurobuddha.minimacore.i18n.R.string.l10n_creative_saved_photos
                :com.eurobuddha.minimacore.i18n.R.string.l10n_creative_save_image_failed),Toast.LENGTH_SHORT).show();
        if(web!=null&&"file:///android_asset/filtr/filtr.html".equals(web.getUrl()))
            web.evaluateJavascript("filtrSaveFinished("+success+");",null);
    }

    private class Bridge {
        @JavascriptInterface public String getLanguage() { return Language.code(); }

        @JavascriptInterface public void toastMsg(String m) {
            runOnUiThread(() -> Toast.makeText(FiltrActivity.this, m, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface public void saveImage(String name,String b64) {
            FiltrActivity.this.saveImage(name,b64);
        }

        @JavascriptInterface public void sendToMint(String b64) {
            sendResult(FiltrHandoff.MINT,b64);
        }

        @JavascriptInterface public void sendToPhoto(String b64) {
            sendResult(FiltrHandoff.PHOTO,b64);
        }
    }
}
