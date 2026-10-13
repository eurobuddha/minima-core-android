package com.eurobuddha.minimacore.chat;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.ImageButton;

import com.eurobuddha.minimacore.R;
import com.eurobuddha.minimacore.utils.MinimaCMD;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import org.minima.utils.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * One conversation — messenger-grade: bubbles with day separators and delivery ticks
 * (🕓 sending, ✓ held at a mailbox, ✓✓ delivered direct), a pill input bar, and a
 * toolbar menu (rename / view address / delete). Sends ride
 * `keets action:send to:<pubkey> datahex:<schema json>`; inbound arrives via ChatDrainer.
 */
public class ChatThreadActivity extends AppCompatActivity {

    private String mPubkey;
    private MsgAdapter mAdapter;
    private Toolbar mToolbar;
    private ActivityResultLauncher<String> mPickImage;
    private final Runnable mOnNewMessage = () -> runOnUiThread(() -> {
        ChatDb.get(this).markThreadRead(mPubkey);
        sendReadReceipt();
        mAdapter.reload();
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_thread);

        mPubkey = getIntent().getStringExtra("pubkey");
        if (mPubkey == null) { finish(); return; }

        mPickImage = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) sendImage(uri); });

        mToolbar = findViewById(R.id.chat_thread_toolbar);
        com.eurobuddha.minimacore.utils.KeyboardInsets.install(this, findViewById(R.id.chat_thread_main), mToolbar);
        refreshTitle();
        setSupportActionBar(mToolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        mToolbar.setNavigationOnClickListener(v -> finish());
        mToolbar.setOnMenuItemClickListener(this::onMenu);
        mToolbar.inflateMenu(R.menu.chat_thread_menu);

        ListView list = findViewById(R.id.chat_msgs);
        mAdapter = new MsgAdapter();
        list.setAdapter(mAdapter);

        ImageButton attach = findViewById(R.id.chat_attach);
        attach.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(new String[]{"📷 Photo", "💸 Send payment", "🏷 Request their pay address"}, (d, which) -> {
                    if (which == 0) mPickImage.launch("image/*");
                    else if (which == 1) startPayment();
                    else requestPayAddr(true);
                }).show());

        EditText input = findViewById(R.id.chat_input);
        FloatingActionButton send = findViewById(R.id.chat_send);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.equals("")) return;
            input.setText("");
            sendMessage(text);
        });
    }

    private void refreshTitle() {
        String name = ChatDb.get(this).contactName(mPubkey);
        mToolbar.setTitle(name.startsWith("0x") ? "New contact" : name);
    }

    private boolean onMenu(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.chatmenu_rename) {
            EditText input = new EditText(this);
            String cur = ChatDb.get(this).contactName(mPubkey);
            input.setText(cur.startsWith("0x") ? "" : cur);
            new AlertDialog.Builder(this)
                    .setTitle("Contact name")
                    .setView(input)
                    .setPositiveButton("Save", (d, w) -> {
                        ChatDb.get(this).putContact(mPubkey, input.getText().toString().trim());
                        refreshTitle();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        if (id == R.id.chatmenu_address) {
            View v = LayoutInflater.from(this).inflate(R.layout.dialog_chat_identity, null);
            ((TextView) v.findViewById(R.id.identity_address)).setText(mPubkey);
            v.findViewById(R.id.identity_name).setVisibility(View.GONE);
            try {
                Bitmap bm = new BarcodeEncoder().encodeBitmap(mPubkey, BarcodeFormat.QR_CODE, 480, 480);
                ((ImageView) v.findViewById(R.id.identity_qr)).setImageBitmap(bm);
            } catch (Exception e) {}
            v.findViewById(R.id.identity_copy).setOnClickListener(b -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("keets address", mPubkey));
                Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show();
            });
            new AlertDialog.Builder(this).setTitle("Contact address").setView(v)
                    .setPositiveButton("Done", null).show();
            return true;
        }
        if (id == R.id.chatmenu_delete) {
            new AlertDialog.Builder(this)
                    .setTitle("Delete chat")
                    .setMessage("Delete this chat and its history? This cannot be undone.")
                    .setPositiveButton("Delete", (d, w) -> {
                        ChatDb.get(this).removeContact(mPubkey);
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatDb.get(this).markThreadRead(mPubkey);
        sendReadReceipt();
        mAdapter.reload();
        ChatDrainer.UI_LISTENERS.add(mOnNewMessage);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatDrainer.UI_LISTENERS.remove(mOnNewMessage);
    }

    /** Tell the peer we read up to the newest incoming message (blue ticks their side). */
    private void sendReadReceipt() {
        new Thread(() -> {
            ChatDb db = ChatDb.get(this);
            ChatDb.Msg latest = db.latestIncoming(mPubkey);
            if (latest == null || latest.msgid.equals(db.lastReceipt(mPubkey))) return;
            db.setLastReceipt(mPubkey, latest.msgid);
            org.minima.utils.json.JSONObject r = ChatSchema.base("", "", "");
            r.put("state", "read");
            r.put("ref", latest.msgid);
            MinimaCMD.execute("keets action:send to:" + mPubkey + " datahex:" + ChatSchema.toDataHex(r.toString()));
        }, "chat-receipt").start();
    }

    /** Ask the peer for a Minima pay address (their node auto-answers). */
    private void requestPayAddr(boolean zToast) {
        new Thread(() -> {
            org.minima.utils.json.JSONObject r = ChatSchema.base("", "", "");
            r.put("state", "payaddr-req");
            MinimaCMD.execute("keets action:send to:" + mPubkey + " datahex:" + ChatSchema.toDataHex(r.toString()));
        }, "chat-payaddr-req").start();
        if (zToast) Toast.makeText(this, "Address requested — arrives automatically when they're online", Toast.LENGTH_SHORT).show();
    }

    // ---- payments -------------------------------------------------------------
    private void startPayment() {
        String payaddr = ChatDb.get(this).contactPayAddr(mPubkey);
        if (payaddr.equals("")) {
            requestPayAddr(false);
            Toast.makeText(this, "No pay address for this contact yet — requested it. Try again once they reply.", Toast.LENGTH_LONG).show();
            return;
        }
        View v = getLayoutInflater().inflate(R.layout.dialog_chat_pay, null);
        ((TextView) v.findViewById(R.id.pay_to)).setText(payaddr);
        new AlertDialog.Builder(this)
                .setTitle("Send Minima")
                .setView(v)
                .setPositiveButton("Send", (d, w) -> {
                    String amt = ((EditText) v.findViewById(R.id.pay_amount)).getText().toString().trim();
                    if (!amt.matches("[0-9]+(\\.[0-9]+)?") || amt.equals("0")) {
                        Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    doPayment(payaddr, amt);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doPayment(String zAddr, String zAmt) {
        Toast.makeText(this, "Sending " + zAmt + " Minima…", Toast.LENGTH_SHORT).show();
        MinimaCMD.runMinima("send address:" + zAddr + " amount:" + zAmt, zResult -> {
            boolean ok = (Boolean) zResult.getOrDefault("status", false);
            if (!ok) {
                runOnUiThread(() -> Toast.makeText(this, "Payment failed: " + zResult.get("error"), Toast.LENGTH_LONG).show());
                return;
            }
            String txid = "";
            try { txid = "" + ((JSONObject) zResult.get("response")).get("txpowid"); } catch (Exception e) {}

            SharedPreferences prefs = getSharedPreferences("main_prefs", Context.MODE_PRIVATE);
            org.minima.utils.json.JSONObject pm = ChatSchema.base(prefs.getString("chat_name", ""), "", "");
            pm.put("state", "payment");
            pm.put("amt", zAmt);
            pm.put("tokname", "Minima");
            pm.put("txid", txid);
            pm.put("addr", zAddr);
            String payload = pm.toString();
            String msgid = ChatSchema.decode(payload).id;

            ChatDb.Msg m = new ChatDb.Msg();
            m.msgid = msgid; m.pubkey = mPubkey; m.incoming = false;
            m.body = ""; m.state = "payment"; m.amt = zAmt; m.tokname = "Minima"; m.txid = txid;
            m.ts = System.currentTimeMillis(); m.via = ""; m.read = true;
            ChatDb.get(this).addMessage(m);
            runOnUiThread(() -> mAdapter.reload());

            MinimaCMD.runMinima("keets action:send to:" + mPubkey + " datahex:" + ChatSchema.toDataHex(payload),
                    zR2 -> runOnUiThread(() -> {
                        boolean ok2 = (Boolean) zR2.getOrDefault("status", false);
                        String via = ok2 ? (String) ((JSONObject) zR2.get("response")).get("delivered") : "FAILED";
                        ChatDb.get(this).setVia(msgid, via);
                        mAdapter.reload();
                    }));
        });
    }

    // ---- photos ---------------------------------------------------------------
    private void sendImage(Uri zUri) {
        Toast.makeText(this, "Publishing photo…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                android.graphics.Bitmap bm = BitmapFactory.decodeStream(getContentResolver().openInputStream(zUri));
                if (bm == null) throw new Exception("could not read image");

                //EXIF orientation: cameras store rotation as metadata, decodeStream ignores
                //it — without this, portrait photos arrive rotated (the known bug from the
                //family's earlier chat apps)
                try {
                    java.io.InputStream exifIn = getContentResolver().openInputStream(zUri);
                    android.media.ExifInterface exif = new android.media.ExifInterface(exifIn);
                    int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL);
                    exifIn.close();
                    android.graphics.Matrix mx = new android.graphics.Matrix();
                    switch (o) {
                        case android.media.ExifInterface.ORIENTATION_ROTATE_90:  mx.postRotate(90);  break;
                        case android.media.ExifInterface.ORIENTATION_ROTATE_180: mx.postRotate(180); break;
                        case android.media.ExifInterface.ORIENTATION_ROTATE_270: mx.postRotate(270); break;
                        case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL: mx.postScale(-1, 1); break;
                        case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:   mx.postScale(1, -1); break;
                        case android.media.ExifInterface.ORIENTATION_TRANSPOSE:  mx.postRotate(90);  mx.postScale(-1, 1); break;
                        case android.media.ExifInterface.ORIENTATION_TRANSVERSE: mx.postRotate(270); mx.postScale(-1, 1); break;
                    }
                    if (!mx.isIdentity()) {
                        bm = android.graphics.Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), mx, true);
                    }
                } catch (Exception e) { /* no exif — keep as decoded */ }

                int max = 1280;
                if (bm.getWidth() > max || bm.getHeight() > max) {
                    float sc = Math.min((float) max / bm.getWidth(), (float) max / bm.getHeight());
                    bm = android.graphics.Bitmap.createScaledBitmap(bm,
                            Math.round(bm.getWidth() * sc), Math.round(bm.getHeight() * sc), true);
                }
                java.io.File tmp = new java.io.File(getCacheDir(), "chat-out-" + System.currentTimeMillis() + ".jpg");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos);
                fos.close();

                //file: hand-off — megabyte params break the command tokenizer
                JSONObject pub = MinimaCMD.execute("keets action:publish path:/chat/img-" +
                        System.currentTimeMillis() + ".jpg file:" + tmp.getAbsolutePath());
                tmp.delete();
                if (!(Boolean) pub.getOrDefault("status", false)) throw new Exception("" + pub.get("error"));
                String url = (String) ((JSONObject) pub.get("response")).get("url");

                SharedPreferences prefs = getSharedPreferences("main_prefs", Context.MODE_PRIVATE);
                org.minima.utils.json.JSONObject im = ChatSchema.base(prefs.getString("chat_name", ""), "", url);
                im.put("state", "image");
                String payload = im.toString();
                String msgid = ChatSchema.decode(payload).id;

                ChatDb.Msg m = new ChatDb.Msg();
                m.msgid = msgid; m.pubkey = mPubkey; m.incoming = false;
                m.body = ""; m.state = "image"; m.ref = url;
                m.ts = System.currentTimeMillis(); m.via = ""; m.read = true;
                ChatDb.get(this).addMessage(m);
                runOnUiThread(() -> mAdapter.reload());

                JSONObject sr = MinimaCMD.execute("keets action:send to:" + mPubkey +
                        " datahex:" + ChatSchema.toDataHex(payload));
                String via = (Boolean) sr.getOrDefault("status", false)
                        ? (String) ((JSONObject) sr.get("response")).get("delivered") : "FAILED";
                ChatDb.get(this).setVia(msgid, via);
                runOnUiThread(() -> mAdapter.reload());
            } catch (Exception exc) {
                runOnUiThread(() -> Toast.makeText(this, "Photo failed: " + exc.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "chat-image-send").start();
    }

    private void sendMessage(String zText) {
        SharedPreferences prefs = getSharedPreferences("main_prefs", Context.MODE_PRIVATE);
        String myname = prefs.getString("chat_name", "");
        String payload = ChatSchema.encode(myname, zText, "");
        String msgid = ChatSchema.decode(payload).id;

        // write-before-send: the message exists locally even if the send fails
        ChatDb.Msg m = new ChatDb.Msg();
        m.msgid = msgid; m.pubkey = mPubkey; m.incoming = false;
        m.body = zText; m.name = myname; m.ref = "";
        m.ts = System.currentTimeMillis(); m.via = ""; m.read = true;
        ChatDb.get(this).addMessage(m);
        mAdapter.reload();

        MinimaCMD.runMinima("keets action:send to:" + mPubkey + " datahex:" + ChatSchema.toDataHex(payload),
                zResult -> runOnUiThread(() -> {
                    boolean ok = (Boolean) zResult.getOrDefault("status", false);
                    String via = "FAILED";
                    if (ok) via = (String) ((JSONObject) zResult.get("response")).get("delivered");
                    ChatDb.get(this).setVia(msgid, via);
                    mAdapter.reload();
                    if (!ok) Toast.makeText(this, "Not sent — check the node is running", Toast.LENGTH_LONG).show();
                }));
    }

    // ---- messages + day separators ------------------------------------------
    private static class Item {
        ChatDb.Msg msg;      // null = date separator
        String day;
    }

    private class MsgAdapter extends BaseAdapter {
        private ArrayList<Item> mItems = new ArrayList<>();
        private final DateFormat mTime = DateFormat.getTimeInstance(DateFormat.SHORT);

        void reload() {
            new Thread(() -> {
                ArrayList<ChatDb.Msg> msgs = ChatDb.get(ChatThreadActivity.this).thread(mPubkey);
                ArrayList<Item> items = new ArrayList<>();
                long lastDay = 0;
                for (ChatDb.Msg m : msgs) {
                    if (lastDay == 0 || !ChatUi.sameDay(lastDay, m.ts)) {
                        Item sep = new Item();
                        sep.day = ChatUi.dayLabel(m.ts);
                        items.add(sep);
                    }
                    lastDay = m.ts;
                    Item it = new Item();
                    it.msg = m;
                    items.add(it);
                }
                runOnUiThread(() -> { mItems = items; notifyDataSetChanged(); });
            }, "chat-thread-load").start();
        }

        @Override public int getCount() { return mItems.size(); }
        @Override public Object getItem(int position) { return mItems.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return mItems.get(position).msg == null ? 0 : 1; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Item it = mItems.get(position);

            if (it.msg == null) {
                View row = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.chat_date_row, parent, false);
                ((TextView) row.findViewById(R.id.date_text)).setText(it.day);
                return row;
            }

            ChatDb.Msg m = it.msg;
            View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.chat_msg_row, parent, false);

            LinearLayout rowBox = row.findViewById(R.id.msg_row);
            LinearLayout bubble = row.findViewById(R.id.msg_bubble);
            ImageView image = row.findViewById(R.id.msg_image);
            TextView body = row.findViewById(R.id.msg_body);
            TextView attach = row.findViewById(R.id.msg_attach);
            TextView meta = row.findViewById(R.id.msg_meta);

            rowBox.setGravity(m.incoming ? Gravity.START : Gravity.END);
            bubble.setBackgroundResource(m.incoming ? R.drawable.chat_bubble_in : R.drawable.chat_bubble_out);
            int fg = m.incoming ? getColor(R.color.core_text) : Color.WHITE;
            body.setTextColor(fg);
            meta.setTextColor(fg);
            attach.setTextColor(fg);

            image.setVisibility(View.GONE);
            attach.setVisibility(View.GONE);
            body.setVisibility(View.VISIBLE);

            if ("image".equals(m.state) && m.ref != null && !m.ref.equals("")) {
                body.setVisibility(m.body.equals("") ? View.GONE : View.VISIBLE);
                body.setText(m.body);
                image.setVisibility(View.VISIBLE);
                image.setImageResource(android.R.drawable.stat_sys_download);
                final String ref = m.ref;
                ChatImages.load(ChatThreadActivity.this, ref, bm -> {
                    if (bm != null && ref.equals(m.ref)) image.setImageBitmap(bm);
                });
                image.setOnClickListener(v -> {
                    Intent viewer = new Intent(ChatThreadActivity.this, ChatImageActivity.class);
                    viewer.putExtra("ref", ref);
                    viewer.putExtra("caption", m.body);
                    startActivity(viewer);
                });
            } else if ("payment".equals(m.state)) {
                String who = m.incoming ? "received" : "sent";
                body.setText("💸 " + (m.incoming ? "Payment received" : "Payment sent") + "\n"
                        + m.amt + " " + (m.tokname.equals("") ? "Minima" : m.tokname));
                attach.setVisibility(View.VISIBLE);
                attach.setText("tap for txid");
                final String txid = m.txid;
                attach.setOnClickListener(v -> new AlertDialog.Builder(ChatThreadActivity.this)
                        .setTitle("Transaction id")
                        .setMessage(txid)
                        .setPositiveButton("Copy", (d, w) -> {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("txid", txid));
                            Toast.makeText(ChatThreadActivity.this, "txid copied", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Close", null)
                        .show());
            } else {
                body.setText(m.body);
                if (m.ref != null && !m.ref.equals("")) {
                    attach.setVisibility(View.VISIBLE);
                    attach.setText("📎 Attachment — tap to copy link");
                    final String ref = m.ref;
                    attach.setOnClickListener(v -> {
                        // the FULL keets:// url lands on the clipboard (never truncated)
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("keets media url", ref));
                        Toast.makeText(ChatThreadActivity.this, "Link copied", Toast.LENGTH_SHORT).show();
                    });
                }
            }

            //ticks: 🕓 sending · ✓ at mailbox · ✓✓ delivered · blue ✓✓ read by them
            String when = mTime.format(new Date(m.ts));
            if (m.incoming) {
                meta.setText(when);
            } else if (m.peerread) {
                meta.setText(when + "  ✓✓");
                meta.setTextColor(Color.parseColor("#7DD3FC"));
            } else {
                meta.setText(when + "  " + ChatUi.statusGlyph(m.via));
            }

            //long-press: copy / delete (local)
            bubble.setOnLongClickListener(v -> {
                new AlertDialog.Builder(ChatThreadActivity.this)
                        .setItems(new String[]{"Copy text", "Delete (this device)"}, (d, which) -> {
                            if (which == 0) {
                                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("message", m.body));
                                Toast.makeText(ChatThreadActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                            } else {
                                ChatDb.get(ChatThreadActivity.this).deleteMessage(m.msgid);
                                reload();
                            }
                        }).show();
                return true;
            });
            return row;
        }
    }
}
