package com.eurobuddha.terminalide;

import android.app.Activity;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.eurobuddha.minimaapi.MinimaAPILogger;
import com.eurobuddha.terminalide.receiver.ReceiverDB;

public class LogsView extends BaseView {

    ReceiverDB mLogsDB;

    TextView mMainText;

    public LogsView(Activity zActivity) {
        super(zActivity, R.layout.pm_terminalide_view_logs);

        mLogsDB = new ReceiverDB(zActivity);

        mMainText = getMainView().findViewById(R.id.pm_terminalide_logs_maintext);
    }

    @Override
    public void refreshView() {

        JSONArray logs = mLogsDB.getAllMessages();
        int logsize = logs.length();

        StringBuilder fulllogs = new StringBuilder();

        for(int i=0;i<logsize;i++){
            try {
                JSONObject row = logs.getJSONObject(i);

                String event   = row.getString("event");
                String data    = row.getString("data");

                if(event.equals("MINIMALOG")){

                    //Get the data..
                    JSONObject datajson = new JSONObject(data);

                    fulllogs.append(datajson.getString("message"));
                    fulllogs.append("\n");
                }

            } catch (JSONException e) {

            }
        }

        setMainText(fulllogs.toString());
    }

    @Override
    public void onDestroy() {
        if (mLogsDB != null) mLogsDB.close();
    }

    public void setMainText(String zText){
        mMainText.post(new Runnable() {
            @Override
            public void run() {
                mMainText.setText(zText);
            }
        });
    }
}
