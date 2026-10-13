package com.eurobuddha.minimacore.chat;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

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
    private final Runnable mOnNewMessage = () -> runOnUiThread(() -> {
        ChatDb.get(this).markThreadRead(mPubkey);
        mAdapter.reload();
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_thread);

        mPubkey = getIntent().getStringExtra("pubkey");
        if (mPubkey == null) { finish(); return; }

        mToolbar = findViewById(R.id.chat_thread_toolbar);
        refreshTitle();
        setSupportActionBar(mToolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        mToolbar.setNavigationOnClickListener(v -> finish());
        mToolbar.setOnMenuItemClickListener(this::onMenu);
        mToolbar.inflateMenu(R.menu.chat_thread_menu);

        ListView list = findViewById(R.id.chat_msgs);
        mAdapter = new MsgAdapter();
        list.setAdapter(mAdapter);

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
        mAdapter.reload();
        ChatDrainer.UI_LISTENERS.add(mOnNewMessage);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatDrainer.UI_LISTENERS.remove(mOnNewMessage);
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
            TextView body = row.findViewById(R.id.msg_body);
            TextView attach = row.findViewById(R.id.msg_attach);
            TextView meta = row.findViewById(R.id.msg_meta);

            rowBox.setGravity(m.incoming ? Gravity.START : Gravity.END);
            bubble.setBackgroundResource(m.incoming ? R.drawable.chat_bubble_in : R.drawable.chat_bubble_out);
            int fg = m.incoming ? getColor(R.color.core_text) : Color.WHITE;
            body.setTextColor(fg);
            meta.setTextColor(fg);
            attach.setTextColor(fg);

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
            } else {
                attach.setVisibility(View.GONE);
            }

            String when = mTime.format(new Date(m.ts));
            meta.setText(m.incoming ? when : when + "  " + ChatUi.statusGlyph(m.via));
            return row;
        }
    }
}
