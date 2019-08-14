package com.skateboard.lintplugintest;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;




public class TestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Toast.makeText(this,"",Toast.LENGTH_SHORT);
    }
}
