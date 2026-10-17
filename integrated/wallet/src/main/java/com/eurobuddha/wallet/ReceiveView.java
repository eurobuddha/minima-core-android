package com.eurobuddha.wallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
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

/**
 * Receive tab: shows one of OUR 64 base wallet addresses (derived locally from the seed by
 * {@link WalletCore} — the same 64 defaults a Minima node derives from this seed) with a QR code.
 * Language.msg(l10n_wallet_new_address) cycles deterministically through the set; every one is tracked and spendable. There
 * is no node round-trip here; addresses are a pure local derivation.
 */
public class ReceiveView extends BaseView {

    private final TextView label;
    private final TextView address;
    private final ImageView qr;

    /** Which of the 64 base addresses is currently shown. */
    private int mIndex = 0;

    public ReceiveView(MainActivity a) {
        super(a, R.layout.pm_wallet_view_receive);
        label = find(R.id.pm_wallet_rcvLabel);
        address = find(R.id.pm_wallet_rcvAddress);
        qr = find(R.id.pm_wallet_rcvQr);

        Button copy = find(R.id.pm_wallet_rcvCopy);
        Language.setText(copy, Language.msg(l10n_wallet_copy_address));
        copy.setOnClickListener(v -> copyAddress());
        Button nextBtn = find(R.id.pm_wallet_rcvRefresh);
        Language.setText(nextBtn, Language.msg(l10n_wallet_new_address));
        nextBtn.setOnClickListener(v -> nextAddress());
        nextBtn.setTextColor(Design.accent());

        root.setBackgroundColor(Design.bg());
        address.setBackgroundColor(Design.surface());
        address.setTextColor(Design.text());
        copy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        copy.setTextColor(Design.onAccent());
        refresh();
    }

    /** Advance to the next derived base address (wraps over however many exist so far). */
    private void nextAddress() {
        AddressBook book = act.addressBook();
        int avail = book == null ? 0 : book.derivedCount();
        if (avail <= 1) {
            Toast.makeText(act, Language.msg(l10n_wallet_addresses_ready, AddressBook.SIZE, avail), Toast.LENGTH_SHORT).show();
            return;
        }
        mIndex = (mIndex + 1) % avail;
        refresh();
    }

    /** The currently-shown Mx address (address book if ready, else the primary). */
    private String currentAddress() {
        AddressBook book = act.addressBook();
        if (book != null && mIndex < book.derivedCount()) return book.addrMx(mIndex);
        mIndex = 0;
        return act.defaultAddress();
    }

    /** Repaints from OUR locally-derived Mx address. */
    @Override
    public void refresh() {
        String addr = currentAddress();
        if (addr == null || addr.isEmpty()) {
            Language.setText(label, Language.msg(l10n_wallet_your_address));
            Language.setText(address, Language.msg(l10n_wallet_deriving_address));
            qr.setImageBitmap(null);
            return;
        }
        Language.setText(label, Language.msg(l10n_wallet_address_index, mIndex + 1, AddressBook.SIZE));
        Language.setText(address, addr);
        renderQr(addr);
    }

    @Override
    public void onShown() {
        refresh();
    }

    /** Encodes the address to a QR bitmap off the UI thread; tag guards against stale results. */
    private void renderQr(final String text) {
        qr.setTag(text);
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                int size = 480;
                BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
                bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {
                        bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                    }
                }
            } catch (Exception e) {
                bmp = null;
            }
            final Bitmap result = bmp;
            act.runOnUiThread(() -> {
                if (act.isDestroyed()) return;
                if (text.equals(qr.getTag())) qr.setImageBitmap(result);
            });
        }).start();
    }

    private void copyAddress() {
        String addr = currentAddress();
        if (addr == null || addr.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Minima address", addr));
        Toast.makeText(act, Language.msg(l10n_wallet_address_copied), Toast.LENGTH_SHORT).show();
    }
}
