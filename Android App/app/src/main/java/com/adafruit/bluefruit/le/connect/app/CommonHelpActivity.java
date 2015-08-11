package com.adafruit.bluefruit.le.connect.app;

import android.graphics.Color;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.TextView;

import com.adafruit.bluefruit.le.connect.BuildConfig;
import com.adafruit.bluefruit.le.connect.R;


public class CommonHelpActivity extends ActionBarActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_commonhelp);

        // Title
        String title = getIntent().getExtras().getString("title");
        getSupportActionBar().setTitle(title);

        // Text
        String asset = getIntent().getExtras().getString("help");
        WebView infoWebView = (WebView)findViewById(R.id.infoWebView);
        infoWebView.setBackgroundColor(Color.TRANSPARENT);
        infoWebView.loadUrl("file:///android_asset/"+asset);
    }
}
