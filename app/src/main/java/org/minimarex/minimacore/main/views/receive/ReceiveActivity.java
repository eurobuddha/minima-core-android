package org.minimarex.minimacore.main.views.receive;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.minimarex.minimacore.R;

/**
 * Thin host that presents the receive (address + QR) screen.
 *
 * Reuses {@link ReceiveView} verbatim. Reached from the Wallet tab's
 * "Receive" button.
 */
public class ReceiveActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_receive);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.receive_main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Toolbar tb = findViewById(R.id.receive_toolbar);
        tb.setTitle("Receive");
        tb.setNavigationOnClickListener(v -> finish());

        //Host the existing, self-contained receive view
        ReceiveView receive = new ReceiveView(this);
        FrameLayout container = findViewById(R.id.receive_container);
        container.addView(receive.getMainView());
    }
}
