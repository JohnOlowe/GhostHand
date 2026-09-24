package damjay.control.ghosthand;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import damjay.control.ghosthand.net.GhostProtocol;

/**
 * Entry point: pick a role.
 *
 * <p>GhostHand is one APK that runs on both phones. Which half of the code wakes up
 * is decided here - {@link HostActivity} (capture + serve) or {@link GuestActivity}
 * (discover + decode + display). Keeping both roles in one app means you install the
 * same build on two phones and never wonder which one has which flavour.
 *
 * <p>This class extends the framework {@link Activity}, not AppCompatActivity: the
 * whole project is deliberately free of AndroidX so it can be compiled by
 * {@code toolchain/build.sh} against nothing but the platform {@code android.jar}.
 * The cost is that views are found with {@code findViewById} instead of view
 * binding - the ids are the same ones declared in the layout XML.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView footer = findViewById(R.id.txtFooter);
        footer.setText(getString(R.string.main_footer,
                GhostProtocol.DEFAULT_PORT, GhostProtocol.HEADER_SIZE));

        View hostCard = findViewById(R.id.cardHost);
        hostCard.setOnClickListener(v ->
                startActivity(new Intent(this, HostActivity.class)));

        View guestCard = findViewById(R.id.cardGuest);
        guestCard.setOnClickListener(v ->
                startActivity(new Intent(this, GuestActivity.class)));
    }
}
