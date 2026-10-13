package com.eurobuddha.minimacore.main.views.logs;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.eurobuddha.minimacore.R;

/**
 * Hosts the existing LogsView as a full-screen activity. Logs moved off the main
 * tab row (Chat took its slot, 1.9.20) into the toolbar overflow menu.
 */
public class LogsActivity extends AppCompatActivity {

    private LogsView mLogsView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        setTitle("Logs");
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mLogsView = new LogsView(this);
        ((FrameLayout) findViewById(R.id.logs_container)).addView(mLogsView.getMainView());
        mLogsView.setDisplayActive(true);
        mLogsView.refreshView();
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mLogsView != null) {
            mLogsView.setDisplayActive(false);
            mLogsView.onActivityDestroy();
        }
    }
}
