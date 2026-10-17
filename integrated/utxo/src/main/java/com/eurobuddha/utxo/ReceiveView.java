package com.eurobuddha.utxo;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

/** Receive tab: shows the node's default address with a QR code. */
public class ReceiveView extends BaseView {

    private final TextView address;
    private final ImageView qr;

    /** Wires Copy/Refresh buttons, themes the view, and renders any cached address. */
    public ReceiveView(MainActivity a) {
        super(a, R.layout.pm_utxo_view_receive);
        address = find(R.id.pm_utxo_rcvAddress);
        qr = find(R.id.pm_utxo_rcvQr);

        android.widget.Button copy = find(R.id.pm_utxo_rcvCopy);
        copy.setOnClickListener(v -> copyAddress());
        Button refreshBtn = find(R.id.pm_utxo_rcvRefresh);
        refreshBtn.setOnClickListener(v -> fetchFresh());
        refreshBtn.setTextColor(Design.accentText());
        Language.description(qr, Language.msg(l10n_utxo_qr));
        root.setBackgroundColor(Design.bg());
        address.setBackgroundColor(Design.surface());
        address.setTextColor(Design.text());
        copy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        copy.setTextColor(Design.onAccent());
        refresh();
    }

    /** Repaints from the cached default address; never hits the node (onShown does the fetch). */
    @Override
    public void refresh() {
        String addr = act.defaultAddress();
        if (addr == null || addr.isEmpty()) {
            Language.setText(address, Language.msg(l10n_utxo_fetching));
            qr.setImageBitmap(null);
            return;
        }
        Language.setText(address, addr);
        renderQr(addr);
    }

    /** getaddress is no longer fetched on every reload — fetch it lazily when the tab is opened. */
    @Override
    public void onShown() {
        String addr = act.defaultAddress();
        if (addr == null || addr.isEmpty()) fetchFresh();
    }

    /** Asks the node for a fresh getaddress, caches it, then updates the label and QR. */
    private void fetchFresh() {
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                String addr = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                if (!addr.isEmpty()) {
                    act.setDefaultAddress(addr);   // so the display and the Copy button both work
                    Language.setText(address, addr);
                    renderQr(addr);
                }
            }
            @Override public void onError(String message) {
                if (!NodeApi.ERR_NOT_ENABLED.equals(message)) {
                    Toast.makeText(act, UtxoMessages.message(message), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** Encodes the address to a QR bitmap off the UI thread; tag guards against stale results. */
    private void renderQr(final String text) {
        qr.setTag(text);   // remembers which address this bitmap is for
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                int size = 480;
                BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
                int[] px = new int[size * size];
                for (int y = 0; y < size; y++) {
                    int rowOff = y * size;
                    for (int x = 0; x < size; x++) px[rowOff + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
                bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
                bmp.setPixels(px, 0, size, 0, 0, size, size);   // one call, not 230k setPixel calls
            } catch (Exception e) {
                bmp = null;
            }
            final Bitmap result = bmp;
            act.runOnUiThread(() -> {
                // Only apply if the address hasn't changed since this encode started.
                if (text.equals(qr.getTag())) qr.setImageBitmap(result);
            });
        }).start();
    }

    /** Copies the cached default address to the clipboard and toasts confirmation. */
    private void copyAddress() {
        String addr = act.defaultAddress();
        if (addr == null || addr.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(Language.msg(l10n_utxo_address), addr));
        Toast.makeText(act, Language.msg(l10n_utxo_address_copied), Toast.LENGTH_SHORT).show();
    }
}
