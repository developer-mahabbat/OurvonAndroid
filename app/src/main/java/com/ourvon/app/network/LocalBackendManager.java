package com.ourvon.app.network;

import android.content.Context;
import android.util.Log;

import com.ourvon.app.server.OurvonServer;

public class LocalBackendManager {
  private static final String TAG = "LocalBackend";

  public enum ServerStatus { STOPPED, STARTING, RUNNING, ERROR }

  private ServerStatus status = ServerStatus.STOPPED;
  private OurvonServer server;
  private final Context context;
  private String serverUrl = "http://127.0.0.1:4096";
  private int port = 4096;

  public LocalBackendManager(Context context) {
    this.context = context.getApplicationContext();
  }

  public ServerStatus getStatus() { return status; }
  public String getServerUrl() { return serverUrl; }

  public void startServer(ServerCallback callback) {
    if (status == ServerStatus.RUNNING) {
      if (callback != null) callback.onResult(true, "Already running");
      return;
    }

    status = ServerStatus.STARTING;

    new Thread(() -> {
      try {
        String filesDir = context.getFilesDir().getAbsolutePath();

        // Try ports 4096-4099
        for (int p = 4096; p < 4100; p++) {
          try {
            server = new OurvonServer(p, filesDir);
            server.start();
            port = p;
            serverUrl = "http://127.0.0.1:" + p;
            break;
          } catch (Exception e) {
            if (p == 4099) throw e;
          }
        }

        Log.d(TAG, "Server started on " + serverUrl);
        status = ServerStatus.RUNNING;
        if (callback != null) callback.onResult(true, "Server started on " + serverUrl);
      } catch (Exception e) {
        Log.e(TAG, "Server start failed", e);
        status = ServerStatus.ERROR;
        if (callback != null) callback.onResult(false, e.getMessage());
      }
    }).start();
  }

  public void stopServer() {
    if (server != null) {
      server.stop();
      server = null;
    }
    status = ServerStatus.STOPPED;
  }

  public interface ServerCallback {
    void onResult(boolean success, String message);
  }
}
