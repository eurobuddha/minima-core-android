package org.minimarex.minimacore.main.views.send;

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
 * Thin host that presents the send-funds form.
 *
 * Reuses {@link SendView} verbatim — all of the send wiring lives there —
 * so this screen is purely a Toolbar + container. Reached from the Wallet
 * tab's "Send" button.
 */
public class SendActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_send);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.send_main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Toolbar tb = findViewById(R.id.send_toolbar);
        tb.setTitle("Send");
        tb.setNavigationOnClickListener(v -> finish());

        //Host the existing, self-contained send view
        SendView send = new SendView(this);
        FrameLayout container = findViewById(R.id.send_container);
        container.addView(send.getMainView());
    }
}
