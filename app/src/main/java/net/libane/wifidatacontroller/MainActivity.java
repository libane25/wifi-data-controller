package net.libane.wifidatacontroller;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText(getString(R.string.prototype_notice)
                + "\n\nScoped to System Framework. Reboot after an update so Vector reloads it.");
        view.setTextSize(18);
        view.setPadding(36, 48, 36, 48);
        setContentView(view);
    }
}
