package com.example.agpproject;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText(greeting("AGP"));
        setContentView(text);
    }

    /** framework-free, so a JVM JUnit test can execute it */
    public static String greeting(String who) {
        return "Hello, " + who + "!";
    }
}
