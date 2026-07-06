package com.ourvon.app.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ourvon.app.R;
import com.ourvon.app.adapter.ChatAdapter;
import com.ourvon.app.model.ApiModels;
import com.ourvon.app.network.LocalBackendManager;
import com.ourvon.app.network.OurvonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;

public class MainActivity extends AppCompatActivity {

  private OurvonClient client;
  private LocalBackendManager backend;
  private ChatAdapter adapter;
  private RecyclerView chatList;
  private EditText inputText;
  private View progressOverlay;
  private TextView statusBar;
  private Gson gson = new Gson();
  private List<ApiModels.Message> messages = new ArrayList<>();
  private EventSource sse;
  private String pendingMsgId;
  private StringBuilder pendingText = new StringBuilder();

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

    backend = new LocalBackendManager(this);
    statusBar.setText("Starting server...");
    progressOverlay.setVisibility(View.VISIBLE);

    backend.startServer(new LocalBackendManager.ServerCallback() {
      @Override
      public void onResult(boolean success, String message) {
        runOnUiThread(() -> {
          progressOverlay.setVisibility(View.GONE);
          if (success) {
            client = new OurvonClient(message, null, null);
            statusBar.setText("Ready on " + message);
            adapter.submitList(new ArrayList<>());
            subscribe();
          } else {
            statusBar.setText("Server error: " + message);
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
          }
        });
      }
    });
  }

  private void subscribe() {
    if (sse != null) sse.cancel();
    if (client == null) return;
    sse = client.subscribeEvents("main", new EventSourceListener() {
      @Override public void onOpen(@NonNull EventSource s, @NonNull okhttp3.Response r) {
        runOnUiThread(() -> statusBar.setText("Connected"));
      }
      @Override public void onEvent(@NonNull EventSource s, String id, String type, @NonNull String data) {
        handleEvent(type, data);
      }
      @Override public void onFailure(@NonNull EventSource s, Throwable t, okhttp3.Response r) {
        if (t != null) runOnUiThread(() -> statusBar.setText(t.getMessage()));
      }
    });
  }

  private void handleEvent(String type, String data) {
    try {
      Map<String, Object> ev = gson.fromJson(data, new TypeToken<Map<String, Object>>(){}.getType());
      if (ev == null) return;

      runOnUiThread(() -> {
        switch (type) {
          case "session.next.prompt.admitted":
            pendingMsgId = UUID.randomUUID().toString();
            pendingText.setLength(0);
            addPlaceholder();
            break;
          case "session.next.text.delta":
            if (pendingMsgId != null && ev.containsKey("text")) {
              pendingText.append(ev.get("text"));
              updatePlaceholder(pendingText.toString());
            }
            break;
          case "session.next.tool.called":
            if (ev.containsKey("name"))
              pendingText.append("\n\n\u25B6 Using ").append(ev.get("name")).append("...");
            updatePlaceholder(pendingText.toString());
            break;
          case "session.next.tool.result":
            if (ev.containsKey("result"))
              pendingText.append("\n\u2514 Result received");
            updatePlaceholder(pendingText.toString());
            break;
          case "session.next.step.ended":
          case "session.next.text.ended":
          case "session.idle":
            pendingMsgId = null;
            loadMessages();
            break;
        }
      });
    } catch (Exception ignored) {}
  }

  private void addPlaceholder() {
    List<ApiModels.Message> list = new ArrayList<>(messages);
    list.add(new ApiModels.Message("assistant", ""));
    adapter.submitList(list);
    chatList.smoothScrollToPosition(adapter.getItemCount() - 1);
  }

  private void updatePlaceholder(String text) {
    List<ApiModels.Message> list = new ArrayList<>(messages);
    list.add(new ApiModels.Message("assistant", text));
    adapter.submitList(list);
  }

  private void send() {
    String text = inputText.getText().toString().trim();
    if (text.isEmpty()) return;
    inputText.setText("");

    messages.add(new ApiModels.Message("user", text));
    loadMessages();

    if (client == null) return;
    new Thread(() -> {
      try {
        client.sendPrompt("main", text);
        subscribe();
      } catch (Exception e) {
        runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
      }
    }).start();
  }

  private void loadMessages() {
    adapter.submitList(new ArrayList<>(messages));
    chatList.smoothScrollToPosition(adapter.getItemCount() - 1);
  }

  private void newChat() {
    messages.clear();
    pendingMsgId = null;
    pendingText.setLength(0);
    loadMessages();
    if (client != null) {
      try { client.createSession(null, null); } catch (Exception ignored) {}
      subscribe();
    }
  }

  @Override
  protected void onDestroy() {
    if (sse != null) sse.cancel();
    if (backend != null) backend.stopServer();
    super.onDestroy();
  }
}
