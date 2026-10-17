package com.eurobuddha.terminalide.ide;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.app.Activity;
import android.content.Intent;
import android.text.format.DateUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.terminalide.BaseView;
import com.eurobuddha.terminalide.NodeApi;
import com.eurobuddha.terminalide.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Script library tab: list, create (blank/template), open in editor, duplicate/delete,
 *  plus a browser for scripts already registered on the node (with import). */
public class ScriptsView extends BaseView {

    ScriptDB mDB;
    NodeApi mNode;
    ListView mList;
    TextView mEmpty;
    List<ScriptDB.Script> mScripts = new ArrayList<>();

    public ScriptsView(Activity zActivity) {
        super(zActivity, R.layout.pm_terminalide_view_scripts);

        mDB = new ScriptDB(zActivity);
        mList = mMainView.findViewById(R.id.pm_terminalide_scripts_list);
        mEmpty = mMainView.findViewById(R.id.pm_terminalide_scripts_empty);

        Button add = mMainView.findViewById(R.id.pm_terminalide_scripts_new);
        add.setOnClickListener(v -> newScriptDialog());

        Button node = mMainView.findViewById(R.id.pm_terminalide_scripts_node);
        node.setOnClickListener(v -> showNodeScripts());

        mList.setOnItemClickListener((parent, view, pos, id) -> openEditor(mScripts.get(pos).id));
        mList.setOnItemLongClickListener((parent, view, pos, id) -> {
            scriptActionsDialog(mScripts.get(pos));
            return true;
        });

        refreshView();
    }

    @Override
    public void refreshView() {
        mScripts = mDB.getAll();
        List<CharSequence> labels = new ArrayList<>();
        for (ScriptDB.Script s : mScripts) {
            CharSequence when = Language.since(s.modified);
            String deployed = s.address.isEmpty() ? "" : "  ⛓ " + shorten(s.address);
            labels.add(Language.join(s.name, "\n", Language.msg(l10n_terminal_editor_chars, s.source.trim().length()), " · ", when, deployed));
        }
        mList.setAdapter(new ArrayAdapter<CharSequence>(mActivity, android.R.layout.simple_list_item_1, labels) {
            @Override public android.view.View getView(int position, android.view.View recycled, android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, recycled, parent);
                Language.setText(view.findViewById(android.R.id.text1), getItem(position));
                return view;
            }
        });
        mEmpty.setVisibility(mScripts.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private static String shorten(String addr) {
        return addr.length() > 14 ? addr.substring(0, 10) + "…" + addr.substring(addr.length() - 4) : addr;
    }

    private void newScriptDialog() {
        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                .setTitle(Language.msg(l10n_terminal_editor_new_from))
                .setItems(new CharSequence[]{Language.msg(l10n_terminal_editor_blank), Language.msg(l10n_terminal_editor_signature), Language.msg(l10n_terminal_editor_timelock), Language.msg(l10n_terminal_editor_hashlock), Language.msg(l10n_terminal_editor_multisig), Language.msg(l10n_terminal_editor_random), Language.msg(l10n_terminal_editor_covenant)}, (d, which) -> nameDialog(which))
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void nameDialog(int templateIdx) {
        EditText name = new EditText(mActivity);
        Language.hint(name, Language.msg(l10n_terminal_editor_name));
        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                .setTitle(Language.msg(l10n_terminal_editor_name_title))
                .setView(name)
                .setPositiveButton(Language.msg(l10n_terminal_editor_create), (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = TemplateText.name(templateIdx);
                    long id = mDB.insert(n, TemplateText.source(templateIdx));
                    refreshView();
                    openEditor(id);
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void scriptActionsDialog(ScriptDB.Script s) {
        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                .setTitle(s.name)
                .setItems(new CharSequence[]{Language.msg(l10n_terminal_editor_open), Language.msg(l10n_terminal_editor_duplicate), Language.msg(l10n_backup_delete)}, (d, which) -> {
                    if (which == 0) {
                        openEditor(s.id);
                    } else if (which == 1) {
                        mDB.insert(Language.msg(l10n_terminal_template_copy, s.name).toString(), s.source);
                        refreshView();
                    } else {
                        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                                .setTitle(Language.msg(l10n_terminal_editor_delete_title, s.name))
                                .setMessage(Language.msg(l10n_terminal_editor_delete_body))
                                .setPositiveButton(Language.msg(l10n_backup_delete), (dd, ww) -> {
                                    mDB.delete(s.id);
                                    refreshView();
                                    Toast.makeText(mActivity, Language.msg(l10n_terminal_ide_deleted), Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                                .show();
                    }
                })
                .setNegativeButton(Language.msg(l10n_language_close), null)
                .show();
    }

    private void openEditor(long scriptId) {
        Intent i = new Intent(mActivity, ScriptEditorActivity.class);
        i.putExtra("script_id", scriptId);
        mActivity.startActivity(i);
    }

    public void setNodeApi(NodeApi zNode) {
        mNode = zNode;
    }

    @Override
    public void onDestroy() {
        if (mDB != null) mDB.close();
    }

    /** Browse scripts registered on the node (the `scripts` command) and import them. */
    private void showNodeScripts() {
        if (mNode == null) return;
        Toast.makeText(mActivity, Language.msg(l10n_terminal_editor_loading), Toast.LENGTH_SHORT).show();
        mNode.cmd("scripts", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) {
                    Toast.makeText(mActivity, Language.msg(l10n_terminal_editor_no_node_scripts), Toast.LENGTH_SHORT).show();
                    return;
                }
                int n = arr.length();
                CharSequence[] labels = new CharSequence[n];
                String[] scripts = new String[n];
                String[] addresses = new String[n];
                for (int i = 0; i < n; i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s == null) {   // malformed element — don't NPE
                        scripts[i] = "";
                        addresses[i] = "";
                        labels[i] = Language.msg(l10n_terminal_editor_unreadable);
                        continue;
                    }
                    scripts[i] = s.optString("script", "");
                    addresses[i] = s.optString("address", "");
                    boolean def = s.optBoolean("default", false);
                    String snippet = scripts[i].length() > 60
                            ? scripts[i].substring(0, 60) + "…" : scripts[i];
                    labels[i] = Language.join(def ? Language.msg(l10n_terminal_editor_wallet) : "", shorten(addresses[i]), "\n", snippet);
                }
                ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                        .setTitle(Language.msg(l10n_terminal_editor_node_scripts, n))
                        .setItems(labels, (d, which) -> nodeScriptActions(
                                addresses[which], scripts[which]))
                        .setNegativeButton(Language.msg(l10n_language_close), null)
                        .show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(mActivity, Language.msg(l10n_terminal_editor_node_error, com.eurobuddha.terminalide.terminal.TerminalMessages.message(message)), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void nodeScriptActions(String address, String script) {
        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                .setTitle(shorten(address))
                .setMessage(script)
                .setPositiveButton(Language.msg(l10n_terminal_editor_import), (d, w) -> {
                    long id = mDB.insert(Language.msg(l10n_terminal_template_from_node, shorten(address)).toString(), script);
                    mDB.setAddress(id, address);
                    refreshView();
                    openEditor(id);
                })
                .setNeutralButton(Language.msg(l10n_wallet_copy_address), (d, w) -> {
                    android.content.ClipboardManager cb = (android.content.ClipboardManager)
                            mActivity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("address", address));
                    Toast.makeText(mActivity, Language.msg(l10n_history_copied), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }
}
