package org.minimarex.minimacore.main.views.send;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.minimarex.minimacore.R;

/**
 * Thin host that presents the send-funds form.
 *
 * Reuses {@link SendView} verbatim — all of the send wiring lives there —
 * so this screen is purely a Toolbar + container. Reached from the Wallet
 * tab's "Send" button.
 */
public class SendActivity extends AppCompatActivity {

    private SendView mSend;

    /**
     * The QR scanner. Registered here rather than in SendView because
     * registerForActivityResult must be called before the Activity reaches STARTED -
     * a launcher created later throws. Same ScanContract pattern as apks/ethwallet.
     */
    private ActivityResultLauncher<ScanOptions> mScanLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mScanLauncher = registerForActivityResult(new ScanContract(), result -> {
            //Null contents = the user backed out of the scanner
            if(result != null && result.getContents() != null && mSend != null){
                mSend.setScannedAddress(result.getContents());
            }
        });

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
        mSend = new SendView(this);

        mSend.setOnScanRequest(() -> mScanLauncher.launch(new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan a Minima address")
                .setBeepEnabled(false)
                .setOrientationLocked(false)));

        FrameLayout container = findViewById(R.id.send_container);
        container.addView(mSend.getMainView());
    }
}
