package org.minimarex.minimacore.main;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.minima.system.params.ParamConfigurer;
import org.minimarex.minimacore.R;

import java.util.StringTokenizer;

/**
 * User-configurable node startup parameters.
 *
 * Curated toggles are stored as their own prefs and applied by MinimaService when it
 * builds the boot arg list; the free-text field is the existing minima_extra_params
 * pref that MinimaService has always consumed. Everything takes effect on the next
 * node (re)start.
 */
public class ParamsActivity extends AppCompatActivity {

    //Pref keys - read by MinimaService at boot
    public static final String PREF_PARAM_SERVER  = "PARAM_SERVER";
    public static final String PREF_PARAM_MEGAMMR = "PARAM_MEGAMMR";
    public static final String PREF_PARAM_RPC     = "PARAM_RPC";
    public static final String PREF_EXTRA_PARAMS  = "minima_extra_params";

    //Flags that must never come in via the free-text field: wipe/seed danger
    //(-clean/-genesis/-solo wipe data, -seed/-anyseed/-dbpassword touch the wallet)
    //and flags the app manages itself (-data/-basefolder/-conf/-daemon/-noshutdownhook,
    //-server/-isclient collide with the Server toggle non-deterministically).
    private static final String[] BLOCKED_FLAGS = {
            "-clean", "-genesis", "-solo", "-seed", "-anyseed", "-dbpassword",
            "-data", "-basefolder", "-conf", "-daemon", "-noshutdownhook",
            "-server", "-isclient"
    };

    SwitchMaterial mServerSwitch;
    SwitchMaterial mMegaSwitch;
    SwitchMaterial mRpcSwitch;
    EditText       mExtraInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.params_activity);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.params_main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setTitle("Startup Params");
        setSupportActionBar(tb);

        mServerSwitch = findViewById(R.id.params_switch_server);
        mMegaSwitch   = findViewById(R.id.params_switch_megammr);
        mRpcSwitch    = findViewById(R.id.params_switch_rpc);
        mExtraInput   = findViewById(R.id.params_extra);

        //Load the current values
        SharedPreferences prefs = getSharedPreferences("main_prefs", MODE_PRIVATE);
        mServerSwitch.setChecked(prefs.getBoolean(PREF_PARAM_SERVER, false));
        mMegaSwitch.setChecked(prefs.getBoolean(PREF_PARAM_MEGAMMR, false));
        mRpcSwitch.setChecked(prefs.getBoolean(PREF_PARAM_RPC, false));
        mExtraInput.setText(prefs.getString(PREF_EXTRA_PARAMS, ""));

        Button save = findViewById(R.id.params_button_save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(saveParams()){
                    Toast.makeText(ParamsActivity.this,
                            "Saved - applies when the node restarts", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        });

        Button saverestart = findViewById(R.id.params_button_save_restart);
        saverestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!saveParams()){
                    return;
                }

                new AlertDialog.Builder(ParamsActivity.this)
                        .setTitle("Restart node?")
                        .setMessage("The node will shut down cleanly and start again with the new parameters. " +
                                "This takes a minute - companion apps reconnect automatically.")
                        .setIcon(R.drawable.ic_minima)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener(){
                            public void onClick(DialogInterface dialog, int whichButton) {
                                if(MainActivity.MAIN_ACTIVITY != null){
                                    MainActivity.MAIN_ACTIVITY.restartMinima();
                                }
                                finish();
                            }})
                        .setNegativeButton(android.R.string.no, null)
                        .show();
            }
        });
    }

    /** Validate + persist. Returns false (with a dialog shown) on any problem. */
    private boolean saveParams(){

        String extra = mExtraInput.getText().toString().trim().replaceAll("\\s+", " ");

        //Refuse blocked flags outright - clearer than silently stripping them
        StringTokenizer tok = new StringTokenizer(extra, " ");
        while(tok.hasMoreTokens()){
            String t = tok.nextToken();
            for(String blocked : BLOCKED_FLAGS){
                if(t.equals(blocked)){
                    showDialog("Blocked parameter",
                            t + " cannot be set here.\n\nWipe/seed flags are blocked for safety and " +
                            "app-managed flags (data folder, server mode, ..) are set by the app or the toggles.");
                    return false;
                }
            }
        }

        //Validate against the node's own parser so errors surface NOW, not as a
        //silent skip at boot
        if(!extra.equals("") && !ParamConfigurer.checkParams(extra)){
            showDialog("Invalid parameters",
                    "The node's parameter parser rejected this string. Check flag names and values:\n\n" + extra);
            return false;
        }

        SharedPreferences.Editor editor = getSharedPreferences("main_prefs", MODE_PRIVATE).edit();
        editor.putBoolean(PREF_PARAM_SERVER,  mServerSwitch.isChecked());
        editor.putBoolean(PREF_PARAM_MEGAMMR, mMegaSwitch.isChecked());
        editor.putBoolean(PREF_PARAM_RPC,     mRpcSwitch.isChecked());
        editor.putString(PREF_EXTRA_PARAMS, extra);
        editor.commit();

        return true;
    }

    private void showDialog(String zTitle, String zMessage){
        new AlertDialog.Builder(this)
                .setTitle(zTitle)
                .setMessage(zMessage)
                .setIcon(R.drawable.ic_minima)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface dialog, int whichButton) {

                    }}).show();
    }
}
