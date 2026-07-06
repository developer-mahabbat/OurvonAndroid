package com.ourvon.app.network;

import android.content.Context;
import android.util.Log;

import com.ourvon.app.server.OurvonServer;

public class LocalBackendManager {
  private static final String TAG = "LocalBackend";

  public enum Status { STARTING, RUNNING, ERROR }

  private Status status = Status.STARTING;
  private OurvonServer server;
  private final Context context;
  private String serverUrl = "http://127.0.0.1:4096";

  public LocalBackendManager(Context context) {
    this.context = context.getApplicationContext();
  }

  public Status getStatus() { return status; }
  public String getServerUrl() { return serverUrl; }

  public void startServer(ServerCallback callback) {
    new Thread(() -> {
      try {
        String filesDir = context.getFilesDir().getAbsolutePath();
        int port = 4096;
        server = new OurvonServer(port, filesDir);
        server.start();
        serverUrl = "http://127.0.0.1:" + port;
        Log.d(TAG, "Server on " + serverUrl);
        status = Status.RUNNING;
        if (callback != null) callback.onResult(true, serverUrl);
      } catch (Exception e) {
        Log.e(TAG, "Start failed", e);
        status = Status.ERROR;
        if (callback != null) callback.onResult(false, e.getMessage());
      }
    }).start();
  }

  public void stopServer() {
    if (server != null) {
      server.stop();
      server = null;
    }
    status = Status.STARTING;
  }

  public interface ServerCallback {
    void onResult(boolean success, String message);
  }
}
