package com.ourvon.app.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.ourvon.app.R;

public class SettingsActivity extends AppCompatActivity {

  private static final String PREFS = "ourvon_prefs";
  private static final String KEY_URL = "server_url";
  private static final String KEY_USER = "username";
  private static final String KEY_PASS = "password";

  private TextInputEditText urlInput, userInput, passInput;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_settings);

    urlInput = findViewById(R.id.serverUrl);
    userInput = findViewById(R.id.serverUser);
    passInput = findViewById(R.id.serverPass);
    MaterialButton saveBtn = findViewById(R.id.saveBtn);

    SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
    urlInput.setText(p.getString(KEY_URL, "127.0.0.1:4096"));
    userInput.setText(p.getString(KEY_USER, "ourvon"));
    passInput.setText(p.getString(KEY_PASS, ""));

    saveBtn.setOnClickListener(v -> {
      String url = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
      String user = userInput.getText() != null ? userInput.getText().toString().trim() : "ourvon";
      String pass = passInput.getText() != null ? passInput.getText().toString().trim() : "";
      if (url.isEmpty()) {
        Toast.makeText(this, "Server URL required", Toast.LENGTH_SHORT).show();
        return;
      }
      getSharedPreferences(PREFS, MODE_PRIVATE).edit()
          .putString(KEY_URL, url)
          .putString(KEY_USER, user)
          .putString(KEY_PASS, pass)
          .apply();
      Toast.makeText(this, "Saved. Reconnect from main screen.", Toast.LENGTH_LONG).show();
      finish();
    });
  }
}
