package org.minimarex.minimacore.main.views.logs;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.R;
import org.minimarex.minimacore.main.BaseView;
import org.minimarex.minimacore.utils.LogBuffer;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.MinimaCMDListener;
import org.minimarex.minimacore.utils.logger;

import java.util.ArrayList;

/**
 * Live tail of the node's own log (MINIMALOG events buffered in LogBuffer by
 * MinimaService). Pause/Clear/Share + a substring filter + verbosity chips that
 * drive the node's `logs <subsystem>:true|false` command.
 */
public class LogsView extends BaseView implements LogBuffer.Sink {

    public int MAX_LOG_LENGTH = 30000;

    //Chip keys = the node `logs` command params (response carries same keys as booleans)
    private static final String[] CHIP_KEYS =
            {"scripts", "mining", "blocks", "networking", "ibd", "maxima"};

    ScrollView mScroller;
    TextView   mMainText;
    EditText   mFilter;
    Button     mPauseBut;
    ChipGroup  mChips;

    boolean mPaused = false;

    //Seed the chip states from the node once (when it's up)
    boolean mChipsSeeded = false;

    public LogsView(Activity zActivity){
        super(zActivity, R.layout.view_logs);

        logger.log("Logs View created..");

        mScroller = getMainView().findViewById(R.id.logs_scroller);

        mMainText = mMainView.findViewById(R.id.logs_maintext);
        mMainText.setTypeface(Typeface.MONOSPACE);
        mMainText.setTextIsSelectable(true);

        mFilter   = mMainView.findViewById(R.id.logs_filter);
        mFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a){}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c){}
            @Override
            public void afterTextChanged(Editable s) {
                renderSnapshot();
            }
        });

        mPauseBut = mMainView.findViewById(R.id.logs_pause);
        mPauseBut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPaused = !mPaused;
                mPauseBut.setText(mPaused ? "Resume" : "Pause");
                if(!mPaused){
                    //Catch up with everything buffered while frozen
                    renderSnapshot();
                }
            }
        });

        Button clear = mMainView.findViewById(R.id.logs_clear);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogBuffer.clear();
                mMainText.setText("");
            }
        });

        Button share = mMainView.findViewById(R.id.logs_share);
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = mMainText.getText().toString();
                if(text.trim().isEmpty()){
                    return;
                }
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "Minima node log");
                send.putExtra(Intent.EXTRA_TEXT, text);
                getActivity().startActivity(Intent.createChooser(send, "Share node log"));
            }
        });

        //Verbosity chips
        mChips = mMainView.findViewById(R.id.logs_chips);
        for(String key : CHIP_KEYS){
            Chip chip = new Chip(zActivity);
            chip.setText(key);
            chip.setCheckable(true);
            chip.setTag(key);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean on = ((Chip)v).isChecked();
                    MinimaCMD.runMinima("logs "+key+":"+(on ? "true" : "false"), new MinimaCMDListener() {
                        @Override
                        public void cmdResult(JSONObject zResult) {
                            seedChipsFrom(zResult);
                        }
                    });
                }
            });
            mChips.addView(chip);
        }
    }

    @Override
    public void refreshView(){
        //Live-tail while (and after) the tab is shown
        LogBuffer.setSink(this);
        renderSnapshot();

        //Seed chip states from the node's current flags (once, when the node is up)
        if(!mChipsSeeded && MinimaCMD.checkMinimaStarted()){
            mChipsSeeded = true;
            MinimaCMD.runMinima("logs", new MinimaCMDListener() {
                @Override
                public void cmdResult(JSONObject zResult) {
                    seedChipsFrom(zResult);
                }
            });
        }
    }

    /** LogBuffer.Sink - called on the appender's thread. */
    @Override
    public void onLine(String zLine){
        if(mPaused || !matchesFilter(zLine)){
            return;
        }
        appendText(zLine);
    }

    private boolean matchesFilter(String zLine){
        String f = mFilter == null ? "" : mFilter.getText().toString().trim();
        if(f.isEmpty()){
            return true;
        }
        return zLine.toLowerCase().contains(f.toLowerCase());
    }

    /** Re-render the whole (filtered) buffer - used on show / resume / filter change. */
    private void renderSnapshot(){
        ArrayList<String> lines = LogBuffer.snapshot();
        StringBuilder sb = new StringBuilder();
        for(String l : lines){
            if(matchesFilter(l)){
                sb.append(l).append('\n');
            }
        }
        String text = sb.toString();
        if(text.length() > MAX_LOG_LENGTH){
            text = text.substring(text.length() - MAX_LOG_LENGTH);
        }
        final String ftext = text;
        mMainText.post(new Runnable() {
            @Override
            public void run() {
                mMainText.setText(ftext);
                scrollToBottom();
            }
        });
    }

    private void seedChipsFrom(JSONObject zResult){
        try{
            Object respobj = zResult.get("response");
            if(!(respobj instanceof JSONObject)){
                return;
            }
            JSONObject resp = (JSONObject) respobj;
            mChips.post(new Runnable() {
                @Override
                public void run() {
                    for(int i=0; i<mChips.getChildCount(); i++){
                        Chip chip = (Chip) mChips.getChildAt(i);
                        Object val = resp.get((String) chip.getTag());
                        if(val != null){
                            chip.setChecked("true".equals(val.toString()));
                        }
                    }
                }
            });
        }catch(Exception ignore){}
    }

    public void appendText(String zText) {
        mMainText.post(new Runnable() {
            @Override
            public void run() {
                String text = mMainText.getText().toString();
                int len     = text.length();
                if(len > MAX_LOG_LENGTH){
                    String newtext = text.substring(len-MAX_LOG_LENGTH,len)+"\n" + zText;
                    mMainText.setText(newtext);
                }else{
                    mMainText.append("\n" + zText);
                }
                scrollToBottom();
            }
        });
    }

    private void scrollToBottom(){
        mScroller.post(new Runnable() {
            @Override
            public void run() {
                mScroller.scrollTo(0,1000000);
            }
        });
    }
}
