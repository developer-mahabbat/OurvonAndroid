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

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ourvon.app.R;
import com.ourvon.app.adapter.ChatAdapter;
import com.ourvon.app.model.ApiModels;
import com.ourvon.app.network.LocalBackendManager;
import com.ourvon.app.network.OurvonClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;

public class MainActivity extends AppCompatActivity {

  private static final String PREFS = "ourvon_prefs";
  private static final String KEY_URL = "server_url";
  private static final String KEY_USER = "username";
  private static final String KEY_PASS = "password";

  private OurvonClient client;
  private LocalBackendManager backend;
  private ChatAdapter adapter;
  private RecyclerView chatList;
  private EditText inputText;
  private ExtendedFloatingActionButton connectBtn;
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
    connectBtn = findViewById(R.id.connectBtn);
    statusBar = findViewById(R.id.statusBar);
    View sendBtn = findViewById(R.id.sendBtn);
    NavigationView nav = findViewById(R.id.navView);
    setSupportActionBar(findViewById(R.id.toolbar));

    adapter = new ChatAdapter();
    chatList.setLayoutManager(new LinearLayoutManager(this));
    chatList.setAdapter(adapter);

    sendBtn.setOnClickListener(v -> send());
    connectBtn.setOnClickListener(v -> connect());
    nav.setNavigationItemSelectedListener(this::onNav);

    // Initialize local backend manager
    backend = new LocalBackendManager(this);
    loadPrefs();
    autoConnect();
  }

  private void loadPrefs() {
    SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
    // Default to localhost for local backend mode
    String url = p.getString(KEY_URL, "127.0.0.1:4096");
    String user = p.getString(KEY_USER, "ourvon");
    String pass = p.getString(KEY_PASS, "");
    client = new OurvonClient(url, user, pass);
  }

  // ────────── Connect ──────────

  private void autoConnect() {
    // First try: check if local server is already running
    new Thread(() -> {
      try {
        if (client.checkHealth()) {
          runOnUiThread(() -> onConnected("Connected to local backend"));
          return;
        }
      } catch (Exception ignored) {}

      // Check what state the backend is in
      LocalBackendManager.BackendStatus bs = backend.getStatus();
      runOnUiThread(() -> {
        if (backend.isSetupNeeded()) {
          connectBtn.setText("Setup Backend");
          statusBar.setText("First run: download Node.js + CLI (~30MB)");
        } else if (bs == LocalBackendManager.BackendStatus.STOPPED) {
          connectBtn.setText("Start Local Backend");
          statusBar.setText("Backend installed, ready to start");
        } else {
          connectBtn.setText("Connect");
          statusBar.setText("Backend: " + bs.name());
        }
        connectBtn.setVisibility(View.VISIBLE);
      });
    }).start();
  }

  private void connect() {
    LocalBackendManager.BackendStatus bs = backend.getStatus();

    if (backend.isSetupNeeded()) {
      // Self-contained setup: download Node.js + CLI
      connectBtn.setText("Downloading...");
      connectBtn.setEnabled(false);
      statusBar.setText("Setting up OURVON backend...");

      backend.setupBackend(new LocalBackendManager.SetupCallback() {
        @Override public void onProgress(String msg, int pct) {
          runOnUiThread(() -> statusBar.setText(msg));
        }
        @Override public void onResult(boolean success, String msg) {
          runOnUiThread(() -> {
            connectBtn.setEnabled(true);
            if (success) {
              statusBar.setText("Backend installed. Starting...");
              startBackend();
            } else {
              connectBtn.setText("Retry Setup");
              statusBar.setText("Setup failed: " + msg);
              Snackbar.make(chatList, "Download failed. Check internet.", Snackbar.LENGTH_LONG).show();
            }
          });
        }
      });
      return;
    }

    if (bs == LocalBackendManager.BackendStatus.STOPPED) {
      startBackend();
      return;
    }

    // Backend is already running, connect
    connectBtn.setText("Connecting...");
    connectBtn.setEnabled(false);
    new Thread(() -> {
      try {
        if (client.checkHealth()) {
          runOnUiThread(() -> onConnected("Connected to OURVON backend"));
        } else {
          runOnUiThread(() -> {
            connectBtn.setText("Connect");
            connectBtn.setEnabled(true);
            Toast.makeText(this, "Backend unreachable", Toast.LENGTH_LONG).show();
          });
        }
      } catch (Exception e) {
        runOnUiThread(() -> {
          connectBtn.setText("Connect");
          connectBtn.setEnabled(true);
          statusBar.setText("Error: " + e.getMessage());
        });
      }
    }).start();
  }

  private void startBackend() {
    connectBtn.setText("Starting...");
    connectBtn.setEnabled(false);
    statusBar.setText("Starting OURVON backend...");

    backend.startServer((success, msg) -> runOnUiThread(() -> {
      connectBtn.setEnabled(true);
      if (success) {
        new Thread(() -> {
          try {
            if (client.checkHealth()) {
              runOnUiThread(() -> onConnected(msg));
            } else {
              connectBtn.setText("Connect");
              statusBar.setText("Backend started but unreachable");
            }
          } catch (Exception e) {
            connectBtn.setText("Connect");
            statusBar.setText("Error: " + e.getMessage());
          }
        }).start();
      } else {
        connectBtn.setText("Retry");
        statusBar.setText("Failed: " + msg);
        Toast.makeText(this, "Start failed: " + msg, Toast.LENGTH_LONG).show();
      }
    }));
  }

  private void onConnected(String msg) {
    connectBtn.setVisibility(View.GONE);
    inputCard.setVisibility(View.VISIBLE);
    statusBar.setText(msg);
    Snackbar.make(chatList, "OURVON backend ready", Snackbar.LENGTH_SHORT).show();
    createOrResumeSession();
  }

  private void showSetupGuide() {
    Snackbar.make(chatList,
        "OURVON backend not found. Install via Termux:\n" +
        "1. pkg install nodejs\n" +
        "2. npm install -g ourvon-ai\n" +
        "3. ourvon serve",
        Snackbar.LENGTH_INDEFINITE)
        .setAction("Settings", v ->
            startActivity(new Intent(this, SettingsActivity.class)))
        .show();
  }

  // ────────── Session ──────────

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

  // ────────── SSE ──────────

  private void subscribe() {
    if (sse != null) { sse.cancel(); sse = null; }
    sse = client.subscribeEvents(sessionId, new EventSourceListener() {
      @Override public void onOpen(@NonNull EventSource s, @NonNull okhttp3.Response r) {
        runOnUiThread(() -> statusBar.setText("OURVON backend active"));
      }
      @Override public void onEvent(@NonNull EventSource s, String id, String type, @NonNull String data) {
        handleEvent(type, data);
      }
      @Override public void onFailure(@NonNull EventSource s, Throwable t, okhttp3.Response r) {
        if (t != null) runOnUiThread(() -> statusBar.setText(t.getMessage()));
      }
    });
  }

  // ────────── Events ──────────

  private void handleEvent(String type, String data) {
    try {
      ApiModels.SseEvent ev = gson.fromJson(data, ApiModels.SseEvent.class);
      if (ev == null) return;

      runOnUiThread(() -> {
        switch (type) {
          case "session.next.prompt.admitted":
            pendingMsgId = ev.id != null ? ev.id : UUID.randomUUID().toString();
            pendingText.setLength(0);
            addPlaceholder();
            break;

          case "session.next.text.delta":
            if (pendingMsgId != null) {
              pendingText.append(ev.getTextDelta());
              updatePlaceholder(pendingText.toString());
            }
            break;

          case "session.next.text.ended":
            if (pendingMsgId != null) {
              updatePlaceholder(pendingText.toString());
            }
            break;

          case "session.next.tool.called":
            if (pendingMsgId != null && ev.data != null) {
              String n = ev.data.containsKey("name") ? ev.data.get("name").toString() : "tool";
              pendingText.append("\n\n\u25B6 Using ").append(n).append("...");
              updatePlaceholder(pendingText.toString());
            }
            break;

          case "session.next.tool.input.started":
            if (pendingMsgId != null && ev.data != null) {
              pendingText.append("\n[Executing tool...]");
              updatePlaceholder(pendingText.toString());
            }
            break;

          case "session.next.step.ended":
          case "session.idle":
            pendingMsgId = null;
            loadMessages();
            break;
        }
      });
    } catch (Exception e) {
      e.printStackTrace();
    }
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

  // ────────── Send ──────────

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

  // ────────── Navigation ──────────

  private boolean onNav(@NonNull MenuItem item) {
    drawer.close();
    int id = item.getItemId();
    if (id == R.id.nav_files) {
      startActivity(new Intent(this, FileBrowserActivity.class));
    } else if (id == R.id.nav_providers) {
      startActivity(new Intent(this, ProvidersActivity.class));
    } else if (id == R.id.nav_settings) {
      startActivity(new Intent(this, SettingsActivity.class));
    } else if (id == R.id.nav_sessions) {
      listSessions();
    } else if (id == R.id.nav_disconnect) {
      disconnect();
    }
    return true;
  }

  private void listSessions() {
    new Thread(() -> {
      try {
        List<ApiModels.SessionInfo> list = client.listSessions(10);
        StringBuilder sb = new StringBuilder("Sessions:\n");
        for (ApiModels.SessionInfo s : list) {
          sb.append("- ").append(s.id.substring(0, 8)).append("... ")
            .append(s.status != null ? s.status : "").append("\n");
        }
        runOnUiThread(() ->
            Snackbar.make(chatList, sb.toString(), Snackbar.LENGTH_LONG).show());
      } catch (Exception e) {
        runOnUiThread(() ->
            Snackbar.make(chatList, "Error: " + e.getMessage(), Snackbar.LENGTH_LONG).show());
      }
    }).start();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main_menu, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.menu_new) {
      newSession();
      return true;
    } else if (item.getItemId() == R.id.menu_interrupt) {
      interrupt();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void newSession() {
    if (sse != null) { sse.cancel(); sse = null; }
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
      try {
        client.interruptSession(sessionId);
        runOnUiThread(() -> statusBar.setText("Interrupted"));
      } catch (Exception e) {
        runOnUiThread(() -> statusBar.setText("Interrupt failed"));
      }
    }).start();
  }

  private void disconnect() {
    if (sse != null) { sse.cancel(); sse = null; }
    backend.stopServer();
    sessionId = null;
    messages.clear();
    adapter.submitList(new ArrayList<>());
    inputCard.setVisibility(View.GONE);
    connectBtn.setVisibility(View.VISIBLE);
    connectBtn.setText("Connect");
    connectBtn.setEnabled(true);
    statusBar.setText("Disconnected");
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    if (sse != null) sse.cancel();
  }
}
