package com.eurobuddha.minimacore.launcher.restore;


import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.minima.utils.BIP39;
import com.eurobuddha.minimacore.R;
import com.eurobuddha.minimacore.utils.KeyboardInsets;
import com.eurobuddha.minimacore.main.ResyncJob;
import com.eurobuddha.minimacore.launcher.LauncherActivity;
import com.eurobuddha.minimacore.utils.logger;

public class RestoreWalletSyncActivity extends AppCompatActivity {

    EditText mSeedInput;
    EditText mKeyUsesInput;

    EditText mMegaNode;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.restorewallet_activity);


        Toolbar tb = findViewById(R.id.toolbar);
        tb.setTitle("Restore Wallet");
        setSupportActionBar(tb);
        KeyboardInsets.install(this, findViewById(R.id.restorewallet_main), tb);

        if (com.eurobuddha.minimacore.BuildConfig.PANDAMONIUM) {
            boolean block = com.eurobuddha.minimacore.main.StartupMode.usesBlockKeys(
                    getSharedPreferences("main_prefs", MODE_PRIVATE));
            android.widget.TextView intro = findViewById(R.id.restorewallet_intro);
            intro.setText(getString(block ? R.string.block_mode_title : R.string.classic_mode_title)
                    + "\n\n" + getString(R.string.restore_intro));
        }

        mSeedInput      = findViewById(R.id.restorewallet_seed);
        mKeyUsesInput   = findViewById(R.id.restorewallet_keyuses);
        mMegaNode       = findViewById(R.id.restorewallet_megammr);

        Button checkbutton = findViewById(R.id.restorewallet_button_check);
        checkbutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String seed = mSeedInput.getText().toString().trim();
                if(seed.equals("")){
                    showDialog("Seed Error","Cannot have an empty seed");
                    return;
                }

                //Check it..
                try{
                    String newseed = BIP39.cleanSeedPhrase(seed);
                    setSeedText(newseed);
                }catch (IllegalArgumentException exc){
                    showDialog("Seed Error","There is an invalid word in your seed");
                    return;
                }

                showDialog("Seed Phrase","Your seed phrase is now valid!");
            }
        });

        Button proceedbutton = findViewById(R.id.restorewallet_button_proceed);
        proceedbutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                //Get the seed
                String seed = mSeedInput.getText().toString().trim();
                if(seed.equals("")){
                    showDialog("Seed Error","Cannot have an empty seed");
                    return;
                }

                String keyusesstr = mKeyUsesInput.getText().toString().trim();
                int keyuses;
                try{
                    keyuses = Integer.parseInt(keyusesstr);
                }catch(NumberFormatException exc){
                    showDialog("Key uses","Key uses must be a whole number.");
                    return;
                }
                //Same floor as the in-app seed rebuild: keyuses:0 says no key was ever used, and
                //a reused Winternitz index exposes the private key.
                int maxKeyUses = ResyncJob.maxKeyUses(com.eurobuddha.minimacore.main.StartupMode.usesBlockKeys(
                        getSharedPreferences("main_prefs", MODE_PRIVATE)));
                if(keyuses < ResyncJob.MIN_KEY_USES || keyuses > maxKeyUses){
                    showDialog("Key uses","Key uses must be between "+ResyncJob.MIN_KEY_USES
                            +" and "+maxKeyUses+". Higher than any previous restore of this seed.");
                    return;
                }

                String megammr = mMegaNode.getText().toString().trim();

                //Set the prefs..
                SharedPreferences prefs = getSharedPreferences("main_prefs", MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("SEED_SET", true);
                editor.putString("SEED", seed);
                editor.putInt("KEYUSES", keyuses);
                editor.putString("default_peers", megammr);
                editor.commit();

                //Jump to restore wallet..
                Intent myIntent = new Intent(RestoreWalletSyncActivity.this, SeedSyncServiceActivity.class);
                RestoreWalletSyncActivity.this.startActivity(myIntent);

                //Close the main Laumcher
                LauncherActivity.LAUNCHER_ACTIVITY.finish();

                finish();
            }
        });
    }

    public void setSeedText(String zSeed){
        mSeedInput.post(new Runnable() {
            @Override
            public void run() {
                mSeedInput.setText(zSeed);
            }
        });
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
