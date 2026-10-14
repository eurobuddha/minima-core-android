package com.eurobuddha.minimacore.maxchat;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
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
import com.eurobuddha.minimacore.chat.ChatUi;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.ChatMedia;
import com.eurobuddha.maxima.core.chat.ChatPay;
import com.eurobuddha.maxima.core.chat.Receipt;
import com.eurobuddha.maxima.core.contacts.Contact;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * One Parlons conversation, over the app's own Maxima transport (MaxChat).
 * Same screens and bubbles as the keets thread; the engine underneath is the
 * Parlons ChatEngine, so receipts and the resend ladder match what the peer's
 * Parlons app expects. v1 renders photos/payments as labelled text - the
 * Parlons media mesh is a follow-up.
 */
public class MaxThreadActivity extends AppCompatActivity {

    private String mPeer;
    private MsgAdapter mAdapter;
    private final Runnable mOnChange = () -> runOnUiThread(() -> {
        ChatEngine chat = MaxChat.chat();
        if (chat != null) {
            chat.markRead(mPeer);
        }
        mAdapter.reload();
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_thread);

        mPeer = getIntent().getStringExtra("peer");
        if (mPeer == null) {
            finish();
            return;
        }

        Toolbar tb = findViewById(R.id.chat_thread_toolbar);
        // Same inset handling as the keets thread: without it the input row sits
        // under the gesture bar on tall screens (seen on the Fold).
        com.eurobuddha.minimacore.utils.KeyboardInsets.install(this,
                findViewById(R.id.chat_thread_main), tb);
        setSupportActionBar(tb);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        tb.setNavigationOnClickListener(v -> finish());
        // After setSupportActionBar the bar owns the title - set it THERE, or
        // the manifest label ("Minima Core") wins.
        getSupportActionBar().setTitle(title());
        getSupportActionBar().setSubtitle("Parlons");

        ListView list = findViewById(R.id.chat_msgs);
        mAdapter = new MsgAdapter();
        list.setAdapter(mAdapter);

        EditText input = findViewById(R.id.chat_input);
        findViewById(R.id.chat_attach).setVisibility(View.GONE);   // media: follow-up
        findViewById(R.id.chat_send).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }
            ChatEngine chat = MaxChat.chat();
            MaximaNode node = MaxChat.node();
            Contact c = node == null ? null : node.contact(mPeer);
            if (chat == null || c == null) {
                Toast.makeText(this, "Parlons transport not ready", Toast.LENGTH_SHORT).show();
                return;
            }
            input.setText("");
            new Thread(() -> {
                try {
                    chat.send(c, text);
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        input.setText(text);   // never eat a typed message
                        Toast.makeText(this,
                                "Send failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
                runOnUiThread(mAdapter::reload);
            }, "maxchat-send").start();
        });

        MaxChat.UI_LISTENERS.add(mOnChange);
        mOnChange.run();
    }

    @Override
    protected void onDestroy() {
        MaxChat.UI_LISTENERS.remove(mOnChange);
        super.onDestroy();
    }

    private String title() {
        MaximaNode node = MaxChat.node();
        Contact c = node == null ? null : node.contact(mPeer);
        return c != null && !c.name.isEmpty() ? c.name : "Parlons contact";
    }

    private static String ticks(ChatEngine.Entry zE) {
        if (!zE.mine) {
            return "";
        }
        String s = zE.state == null ? "" : zE.state;
        if (s.contains(Receipt.READ) || s.contains(Receipt.DELIVERED)) {
            return "  ✓✓";
        }
        if (s.contains(Receipt.SENT)) {
            return "  ✓";
        }
        return "  🕓";
    }

    private class MsgAdapter extends BaseAdapter {
        private ArrayList<Object> mItems = new ArrayList<>();   // String day label | Entry
        private final DateFormat mTime = DateFormat.getTimeInstance(DateFormat.SHORT);
        // Reloads race during a message burst; only the newest snapshot may land.
        private final java.util.concurrent.atomic.AtomicInteger mGen =
                new java.util.concurrent.atomic.AtomicInteger();

        void reload() {
            ChatEngine chat = MaxChat.chat();
            if (chat == null) {
                return;
            }
            final int gen = mGen.incrementAndGet();
            new Thread(() -> {
                List<ChatEngine.Entry> conv = chat.conversation(mPeer);
                ArrayList<Object> items = new ArrayList<>();
                long lastDay = 0;
                for (ChatEngine.Entry e : conv) {
                    if (lastDay == 0 || !ChatUi.sameDay(lastDay, e.time)) {
                        items.add(ChatUi.dayLabel(e.time));
                    }
                    lastDay = e.time;
                    items.add(e);
                }
                runOnUiThread(() -> {
                    if (gen != mGen.get()) {
                        return;   // a newer snapshot is on its way
                    }
                    mItems = items;
                    notifyDataSetChanged();
                });
            }, "maxchat-thread-load").start();
        }

        @Override public int getCount() { return mItems.size(); }
        @Override public Object getItem(int position) { return mItems.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) {
            return mItems.get(position) instanceof String ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object it = mItems.get(position);
            if (it instanceof String) {
                View row = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.chat_date_row, parent, false);
                ((TextView) row.findViewById(R.id.date_text)).setText((String) it);
                return row;
            }

            ChatEngine.Entry e = (ChatEngine.Entry) it;
            View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.chat_msg_row, parent, false);

            LinearLayout rowBox = row.findViewById(R.id.msg_row);
            LinearLayout bubble = row.findViewById(R.id.msg_bubble);
            ImageView image = row.findViewById(R.id.msg_image);
            TextView body = row.findViewById(R.id.msg_body);
            TextView attach = row.findViewById(R.id.msg_attach);
            TextView meta = row.findViewById(R.id.msg_meta);

            boolean incoming = !e.mine;
            rowBox.setGravity(incoming ? Gravity.START : Gravity.END);
            bubble.setBackgroundResource(incoming
                    ? R.drawable.chat_bubble_in : R.drawable.chat_bubble_out);
            int fg = incoming ? getColor(R.color.core_text) : Color.WHITE;
            body.setTextColor(fg);
            meta.setTextColor(fg);
            attach.setTextColor(fg);

            image.setVisibility(View.GONE);
            attach.setVisibility(View.GONE);
            body.setVisibility(View.VISIBLE);
            body.setText(ChatMedia.isMedia(e.body) ? "📷 Photo (view in Parlons)"
                    : ChatPay.isPayment(e.body)
                        ? "💸 " + ChatPay.amount(e.body) + " " + ChatPay.tokenName(e.body)
                        : e.body);
            meta.setText(mTime.format(new java.util.Date(e.time)) + ticks(e));
            return row;
        }
    }
}
