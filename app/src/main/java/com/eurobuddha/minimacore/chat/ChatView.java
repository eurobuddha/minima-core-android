package com.eurobuddha.minimacore.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.eurobuddha.minimacore.R;
import com.eurobuddha.minimacore.main.BaseView;
import com.eurobuddha.minimacore.utils.MinimaCMD;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import org.minima.utils.json.JSONObject;

import java.util.ArrayList;

/**
 * The Chat tab: a clean thread list (avatar, name, preview, time, unread badge — no
 * raw hex anywhere), a + FAB that starts a chat by QR scan or pasted address, and a
 * "My address & QR" header that opens the identity sheet. History lives in ChatDb
 * (fed by ChatDrainer); conversations open in ChatThreadActivity.
 */
public class ChatView extends BaseView {

    private static String mMyPub = null;   // cached (stable per datadir)

    private final TextView mEmpty;
    private final TextView mMyName;
    private final ThreadAdapter mAdapter;
    private Runnable mScanRequest = null;  // wired by MainActivity (launcher must register pre-STARTED)
    private EditText mPendingScanTarget = null;
    private final Runnable mOnNewMessage = () -> mActivity.runOnUiThread(this::refreshView);

    public ChatView(Activity zActivity) {
        super(zActivity, R.layout.view_chat);

        mEmpty = getMainView().findViewById(R.id.chat_empty);
        mMyName = getMainView().findViewById(R.id.chat_myname);
        ListView list = getMainView().findViewById(R.id.chat_threads);
        mAdapter = new ThreadAdapter();
        list.setAdapter(mAdapter);

        getMainView().findViewById(R.id.chat_myaddress_row).setOnClickListener(v -> showIdentity());

        FloatingActionButton fab = getMainView().findViewById(R.id.chat_fab);
        fab.setOnClickListener(v -> showNewChat());

        list.setOnItemClickListener((parent, view, position, id) -> {
            ChatDb.Thread t = (ChatDb.Thread) mAdapter.getItem(position);
            openThread(t.pubkey);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            ChatDb.Thread t = (ChatDb.Thread) mAdapter.getItem(position);
            new AlertDialog.Builder(mActivity)
                    .setTitle(t.name)
                    .setItems(new String[]{"Rename", "Delete chat"}, (d, which) -> {
                        if (which == 0) renameContact(t);
                        else deleteContact(t);
                    }).show();
            return true;
        });

        ChatDrainer.UI_LISTENERS.add(mOnNewMessage);
    }

    /** MainActivity wires the QR scanner through here (ActivityResult launchers must pre-register). */
    public void setOnScanRequest(Runnable zRequest) { mScanRequest = zRequest; }

    /** MainActivity routes the scan result back. */
    public void onScanResult(String zContents) {
        if (zContents == null) return;
        String pub = zContents.trim().toLowerCase();
        if (!pub.matches("0x[0-9a-f]{64}")) {
            Toast.makeText(mActivity, "Not a keets address QR", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mPendingScanTarget != null) {
            mPendingScanTarget.setText(pub);
        } else {
            ChatDb.get(mActivity).ensureContact(pub, null);
            openThread(pub);
        }
    }

    @Override
    public void onActivityDestroy() {
        ChatDrainer.UI_LISTENERS.remove(mOnNewMessage);
    }

    private void openThread(String zPubkey) {
        Intent it = new Intent(mActivity, ChatThreadActivity.class);
        it.putExtra("pubkey", zPubkey);
        mActivity.startActivity(it);
    }

    // ---- identity sheet: QR + full address + copy + display name ----------
    private void showIdentity() {
        View v = LayoutInflater.from(mActivity).inflate(R.layout.dialog_chat_identity, null);
        TextView addr = v.findViewById(R.id.identity_address);
        ImageView qr = v.findViewById(R.id.identity_qr);
        EditText name = v.findViewById(R.id.identity_name);

        SharedPreferences prefs = mActivity.getSharedPreferences("main_prefs", Context.MODE_PRIVATE);
        name.setText(prefs.getString("chat_name", ""));

        if (mMyPub != null) {
            addr.setText(mMyPub);
            try {
                Bitmap bm = new BarcodeEncoder().encodeBitmap(mMyPub, BarcodeFormat.QR_CODE, 480, 480);
                qr.setImageBitmap(bm);
            } catch (Exception e) {}
        } else {
            addr.setText("Node starting — try again in a moment");
        }

        v.findViewById(R.id.identity_copy).setOnClickListener(b -> {
            if (mMyPub == null) return;
            ClipboardManager cm = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("keets address", mMyPub));
            Toast.makeText(mActivity, "Address copied", Toast.LENGTH_SHORT).show();
        });

        new AlertDialog.Builder(mActivity)
                .setTitle("My address")
                .setView(v)
                .setPositiveButton("Done", (d, w) -> {
                    prefs.edit().putString("chat_name", name.getText().toString().trim()).apply();
                    refreshView();
                })
                .show();
    }

    // ---- new chat: scan or paste -------------------------------------------
    private void showNewChat() {
        View v = LayoutInflater.from(mActivity).inflate(R.layout.dialog_chat_new, null);
        EditText addrIn = v.findViewById(R.id.newchat_address);
        EditText nameIn = v.findViewById(R.id.newchat_name);

        AlertDialog dlg = new AlertDialog.Builder(mActivity)
                .setTitle("New chat")
                .setView(v)
                .setPositiveButton("Start chat", null)   // set below so bad input doesn't dismiss
                .setNegativeButton("Cancel", null)
                .create();

        v.findViewById(R.id.newchat_scan).setOnClickListener(b -> {
            mPendingScanTarget = addrIn;
            if (mScanRequest != null) mScanRequest.run();
        });

        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String pub = addrIn.getText().toString().trim().toLowerCase();
            String name = nameIn.getText().toString().trim();
            if (!pub.matches("0x[0-9a-f]{64}")) {
                addrIn.setError("A keets address is 0x + 64 hex characters");
                return;
            }
            ChatDb.get(mActivity).putContact(pub, name.equals("") ? null : name);
            dlg.dismiss();
            mPendingScanTarget = null;
            refreshView();
            openThread(pub);
        }));
        dlg.show();
    }

    private void renameContact(ChatDb.Thread zT) {
        EditText input = new EditText(mActivity);
        input.setText(zT.name.startsWith("0x") ? "" : zT.name);
        new AlertDialog.Builder(mActivity)
                .setTitle("Contact name")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    ChatDb.get(mActivity).putContact(zT.pubkey, input.getText().toString().trim());
                    refreshView();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteContact(ChatDb.Thread zT) {
        new AlertDialog.Builder(mActivity)
                .setTitle("Delete chat")
                .setMessage("Delete this chat and its history? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    ChatDb.get(mActivity).removeContact(zT.pubkey);
                    refreshView();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void refreshView() {
        if (mMyPub == null && MinimaCMD.checkMinimaStarted()) {
            MinimaCMD.runMinima("keets action:info", zResult -> {
                if ((Boolean) zResult.getOrDefault("status", false)) {
                    mMyPub = (String) ((JSONObject) zResult.get("response")).get("pubkey");
                }
            });
        }
        SharedPreferences prefs = mActivity.getSharedPreferences("main_prefs", Context.MODE_PRIVATE);
        mMyName.setText(prefs.getString("chat_name", ""));
        mAdapter.reload();
    }

    private class ThreadAdapter extends BaseAdapter {
        private ArrayList<ChatDb.Thread> mThreads = new ArrayList<>();

        void reload() {
            new Thread(() -> {
                ArrayList<ChatDb.Thread> t = ChatDb.get(mActivity).threads();
                mActivity.runOnUiThread(() -> {
                    mThreads = t;
                    mEmpty.setVisibility(t.isEmpty() ? View.VISIBLE : View.GONE);
                    notifyDataSetChanged();
                });
            }, "chat-threads-load").start();
        }

        @Override public int getCount() { return mThreads.size(); }
        @Override public Object getItem(int position) { return mThreads.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : mActivity.getLayoutInflater().inflate(R.layout.chat_thread_row, parent, false);
            ChatDb.Thread t = mThreads.get(position);

            // a contact that is still its own pubkey shows as "New contact" — hex stays out of the list
            String display = t.name.startsWith("0x") ? "New contact" : t.name;
            ((TextView) row.findViewById(R.id.row_name)).setText(display);
            ChatUi.avatar(row.findViewById(R.id.row_avatar), display, t.pubkey);
            ((TextView) row.findViewById(R.id.row_last)).setText(t.lastBody);
            ((TextView) row.findViewById(R.id.row_time)).setText(ChatUi.listTime(t.lastTs));

            TextView unread = row.findViewById(R.id.row_unread);
            if (t.unread > 0) {
                unread.setVisibility(View.VISIBLE);
                unread.setText(t.unread > 99 ? "99+" : "" + t.unread);
            } else {
                unread.setVisibility(View.GONE);
            }
            return row;
        }
    }
}
