package com.ourvon.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.ourvon.app.R;
import com.ourvon.app.adapter.ChatAdapter;
import com.ourvon.app.model.ApiModels;
import com.ourvon.app.service.NodeService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

  private static final String BASE = "http://127.0.0.1:4097";

  private ChatAdapter adapter;
  private RecyclerView chatList;
  private EditText inputText;
  private View progressOverlay;
  private TextView statusBar;
  private Gson gson = new Gson();
  private List<ApiModels.Message> messages = new ArrayList<>();
  private volatile boolean streaming = false;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    chatList = findViewById(R.id.chatList);
    inputText = findViewById(R.id.inputText);
    progressOverlay = findViewById(R.id.progressOverlay);
    statusBar = findViewById(R.id.statusBar);

    adapter = new ChatAdapter();
    chatList.setLayoutManager(new LinearLayoutManager(this));
    chatList.setAdapter(adapter);

    findViewById(R.id.sendBtn).setOnClickListener(v -> send());
    findViewById(R.id.newChatBtn).setOnClickListener(v -> newChat());

    // Start the Node.js engine service
    startService(new Intent(this, NodeService.class));
    statusBar.setText("Starting engine...");
    waitForServer();
  }

  private void waitForServer() {
    progressOverlay.setVisibility(View.VISIBLE);
    new Thread(() -> {
      for (int i = 0; i < 60; i++) {
        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        try {
          HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/api/health").openConnection();
          c.setConnectTimeout(2000);
          c.setReadTimeout(2000);
          if (c.getResponseCode() == 200) {
            runOnUiThread(() -> {
              statusBar.setText("Ready");
              progressOverlay.setVisibility(View.GONE);
              adapter.submitList(new ArrayList<>(messages));
            });
            return;
          }
          c.disconnect();
        } catch (Exception ignored) {}
      }
      runOnUiThread(() -> {
        statusBar.setText("Timeout - restart app");
        progressOverlay.setVisibility(View.GONE);
      });
    }).start();
  }

  private void send() {
    String text = inputText.getText().toString().trim();
    if (text.isEmpty() || streaming) return;
    inputText.setText("");

    ApiModels.Message userMsg = new ApiModels.Message("user", text);
    messages.add(userMsg);
    adapter.submitList(new ArrayList<>(messages));
    chatList.smoothScrollToPosition(adapter.getItemCount() - 1);

    streaming = true;

    new Thread(() -> {
      try {
        // Build request body
        StringBuilder jsonBody = new StringBuilder();
        jsonBody.append("{\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
          ApiModels.Message m = messages.get(i);
          jsonBody.append("{\"role\":\"").append(m.role).append("\",\"content\":\"");
          jsonBody.append(escape(m.text != null ? m.text : "")).append("\"}");
          if (i < messages.size() - 1) jsonBody.append(",");
        }
        jsonBody.append("]}");

        URL url = new URL(BASE + "/api/chat");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(0); // no timeout for streaming
        OutputStream os = c.getOutputStream();
        os.write(jsonBody.toString().getBytes("UTF-8"));
        os.flush();
        os.close();

        int status = c.getResponseCode();
        if (status != 200) {
          runOnUiThread(() -> Toast.makeText(this, "Server error: " + status, Toast.LENGTH_SHORT).show());
          streaming = false;
          return;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        String line;
        ApiModels.Message currentAssistant = null;
        StringBuilder assistantText = new StringBuilder();

        while ((line = reader.readLine()) != null) {
          if (line.startsWith("event: ")) {
            String eventType = line.substring(7).trim();
            String dataLine = reader.readLine();
            if (dataLine == null) break;
            if (dataLine.startsWith("data: ")) {
              String data = dataLine.substring(6);
              handleSseEvent(eventType, data, currentAssistant, assistantText);
            }
          }
        }
        reader.close();
        c.disconnect();

        // Finalize
        if (assistantText.length() > 0) {
          messages.add(new ApiModels.Message("assistant", assistantText.toString()));
        }
        runOnUiThread(() -> {
          adapter.submitList(new ArrayList<>(messages));
          streaming = false;
        });

      } catch (Exception e) {
        runOnUiThread(() -> {
          Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
          streaming = false;
        });
      }
    }).start();
  }

  private void handleSseEvent(String type, String data, ApiModels.Message current, StringBuilder text) {
    runOnUiThread(() -> {
      try {
        switch (type) {
          case "session.next.prompt.admitted":
            text.setLength(0);
            break;
          case "session.next.text.delta": {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(data).getAsJsonObject();
            if (obj.has("text")) {
              text.append(obj.get("text").getAsString());
              // Create a temp message for display
              List<ApiModels.Message> display = new ArrayList<>(messages);
              display.add(new ApiModels.Message("assistant", text.toString()));
              adapter.submitList(display);
              chatList.smoothScrollToPosition(adapter.getItemCount() - 1);
            }
            break;
          }
          case "session.next.text.ended":
          case "session.next.step.ended":
          case "session.idle":
            break;
        }
      } catch (Exception ignored) {}
    });
  }

  private void newChat() {
    messages.clear();
    adapter.submitList(new ArrayList<>());
  }

  private String escape(String s) {
    return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
  }
}
