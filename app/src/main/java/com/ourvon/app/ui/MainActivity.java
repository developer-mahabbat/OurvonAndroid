package com.ourvon.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.ourvon.app.R;
import com.ourvon.app.adapter.ChatAdapter;
import com.ourvon.app.model.ApiModels;
import com.ourvon.app.network.LocalBackendManager;
import com.ourvon.app.network.OurvonClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;

public class MainActivity extends AppCompatActivity {

  private static final String PREFS = "ourvon_prefs";
  private static final String KEY_URL = "server_url";

  private OurvonClient client;
  private LocalBackendManager backend;
  private ChatAdapter adapter;
  private RecyclerView chatList;
  private EditText inputText;
  private View inputCard;
  private TextView statusBar;
  private DrawerLayout drawer;

  private String sessionId;
  private final List<ApiModels.Message> messages = new ArrayList<>();
  private EventSource sse;
  private String pendingMsgId;
  private final StringBuilder pendingText = new StringBuilder();
  private final Gson gson = new GsonBuilder().create();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    drawer = findViewById(R.id.drawerLayout);
    chatList = findViewById(R.id.chatList);
    inputText = findViewById(R.id.inputText);
    inputCard = findViewById(R.id.inputCard);
    statusBar = findViewById(R.id.statusBar);
    View sendBtn = findViewById(R.id.sendBtn);
    NavigationView nav = findViewById(R.id.navView);
    setSupportActionBar(findViewById(R.id.toolbar));

    adapter = new ChatAdapter();
    chatList.setLayoutManager(new LinearLayoutManager(this));
    chatList.setAdapter(adapter);

    sendBtn.setOnClickListener(v -> send());
    nav.setNavigationItemSelectedListener(this::onNav);

    backend = new LocalBackendManager(this);
    loadPrefs();
    startEmbeddedServer();
  }

  private void loadPrefs() {
    SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
    String url = p.getString(KEY_URL, "127.0.0.1:4096");
    client = new OurvonClient(url, "ourvon", "");
  }

  private void startEmbeddedServer() {
    statusBar.setText("Starting server...");
    backend.startServer((success, msg) -> runOnUiThread(() -> {
      if (success) {
        loadPrefs();
        connectToServer();
      } else {
        statusBar.setText("Failed: " + msg);
        Snackbar.make(chatList, "Server error: " + msg, Snackbar.LENGTH_LONG).show();
      }
    }));
  }

  private void connectToServer() {
    statusBar.setText("Connecting...");
    new Thread(() -> {
      try {
        Thread.sleep(500);
        if (client.checkHealth()) {
          runOnUiThread(() -> {
            statusBar.setText("OURVON ready");
            inputCard.setVisibility(View.VISIBLE);
            Snackbar.make(chatList, "Connected", Snackbar.LENGTH_SHORT).show();
            createOrResumeSession();
          });
        } else {
          runOnUiThread(() -> statusBar.setText("Server unreachable"));
        }
      } catch (Exception e) {
        runOnUiThread(() -> statusBar.setText("Error: " + e.getMessage()));
      }
    }).start();
  }

  private void createOrResumeSession() {
    new Thread(() -> {
      try {
        List<ApiModels.SessionInfo> sessions = client.listSessions(1);
        if (sessions != null && !sessions.isEmpty()) {
          sessionId = sessions.get(0).id;
          loadMessages();
        } else {
          ApiModels.SessionInfo s = client.createSession(null, null);
          if (s != null) sessionId = s.id;
        }
        if (sessionId != null) subscribe();
      } catch (Exception e) {
        runOnUiThread(() -> statusBar.setText("Session: " + e.getMessage()));
      }
    }).start();
  }

  private void loadMessages() {
    new Thread(() -> {
      try {
        List<ApiModels.Message> msgs = client.getMessages(sessionId, 50);
        runOnUiThread(() -> {
          messages.clear();
          if (msgs != null) messages.addAll(msgs);
          adapter.submitList(new ArrayList<>(messages));
          chatList.smoothScrollToPosition(messages.size() - 1);
        });
      } catch (Exception ignored) {}
    }).start();
  }

  private void subscribe() {
    if (sse != null) sse.cancel();
    sse = client.subscribeEvents(sessionId, new EventSourceListener() {
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
            if (pendingMsgId != null && ev.containsKey("text"))
              pendingText.append(ev.get("text"));
            updatePlaceholder(pendingText.toString());
            break;
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
    ApiModels.Message m = new ApiModels.Message();
    m.id = pendingMsgId;
    m.role = "assistant";
    ApiModels.ContentPart c = new ApiModels.ContentPart();
    c.type = "text";
    c.text = "";
    m.content = Collections.singletonList(c);
    m.createdAt = System.currentTimeMillis();
    messages.add(m);
    adapter.submitList(new ArrayList<>(messages));
    chatList.smoothScrollToPosition(messages.size() - 1);
  }

  private void updatePlaceholder(String text) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (pendingMsgId != null && pendingMsgId.equals(messages.get(i).id)) {
        if (messages.get(i).content != null && !messages.get(i).content.isEmpty()) {
          messages.get(i).content.get(0).text = text;
          adapter.notifyItemChanged(i);
          chatList.smoothScrollToPosition(messages.size() - 1);
        }
        return;
      }
    }
  }

  private void send() {
    String text = inputText.getText().toString().trim();
    if (TextUtils.isEmpty(text)) return;
    if (sessionId == null) {
      Toast.makeText(this, "Backend not connected", Toast.LENGTH_SHORT).show();
      return;
    }
    inputText.setText("");

    ApiModels.Message m = new ApiModels.Message();
    m.id = UUID.randomUUID().toString();
    m.role = "user";
    ApiModels.ContentPart c = new ApiModels.ContentPart();
    c.type = "text";
    c.text = text;
    m.content = Collections.singletonList(c);
    m.createdAt = System.currentTimeMillis();
    messages.add(m);
    adapter.submitList(new ArrayList<>(messages));
    chatList.smoothScrollToPosition(messages.size() - 1);

    new Thread(() -> {
      try { client.sendPrompt(sessionId, text); }
      catch (Exception e) {
        runOnUiThread(() ->
            Snackbar.make(chatList, "Send failed: " + e.getMessage(), Snackbar.LENGTH_LONG).show());
      }
    }).start();
  }

  private boolean onNav(@NonNull MenuItem item) {
    drawer.close();
    int id = item.getItemId();
    if (id == R.id.nav_files) startActivity(new Intent(this, FileBrowserActivity.class));
    else if (id == R.id.nav_providers) startActivity(new Intent(this, ProvidersActivity.class));
    else if (id == R.id.nav_settings) startActivity(new Intent(this, SettingsActivity.class));
    else if (id == R.id.nav_disconnect) disconnect();
    return true;
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main_menu, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.menu_new) newSession();
    else if (item.getItemId() == R.id.menu_interrupt) interrupt();
    return super.onOptionsItemSelected(item);
  }

  private void newSession() {
    if (sse != null) sse.cancel();
    messages.clear();
    adapter.submitList(new ArrayList<>());
    sessionId = null;
    pendingMsgId = null;
    pendingText.setLength(0);
    createOrResumeSession();
  }

  private void interrupt() {
    if (sessionId == null) return;
    new Thread(() -> {
      try { client.interruptSession(sessionId); runOnUiThread(() -> statusBar.setText("Interrupted")); }
      catch (Exception e) { runOnUiThread(() -> statusBar.setText("Interrupt failed")); }
    }).start();
  }

  private void disconnect() {
    if (sse != null) sse.cancel();
    backend.stopServer();
    sessionId = null;
    messages.clear();
    adapter.submitList(new ArrayList<>());
    inputCard.setVisibility(View.GONE);
    statusBar.setText("Disconnected");
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    if (sse != null) sse.cancel();
  }
}
