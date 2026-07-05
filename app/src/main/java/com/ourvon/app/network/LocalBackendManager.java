package com.ourvon.app.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class LocalBackendManager {
  private static final String TAG = "LocalBackend";
  private static final String PREFS = "ourvon_prefs";
  private static final String KEY_BACKEND_MODE = "backend_mode";
  private static final String KEY_BINARY_PATH = "binary_path";

  public enum BackendMode { LOCAL, TERMUX, REMOTE }
  public enum BackendStatus { NOT_INSTALLED, STOPPED, STARTING, RUNNING, ERROR }

  private BackendMode mode = BackendMode.LOCAL;
  private BackendStatus status = BackendStatus.NOT_INSTALLED;
  private Process serverProcess;
  private String binaryPath;
  private final Context context;
  private String serverUrl = "http://127.0.0.1:4096";

  public LocalBackendManager(Context context) {
    this.context = context.getApplicationContext();
    loadPrefs();
    detectBackend();
  }

  private void loadPrefs() {
    SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String modeStr = p.getString(KEY_BACKEND_MODE, "local");
    try { mode = BackendMode.valueOf(modeStr.toUpperCase()); }
    catch (Exception e) { mode = BackendMode.LOCAL; }
    binaryPath = p.getString(KEY_BINARY_PATH, "");
  }

  private void detectBackend() {
    // Check 1: Is ourvon binary available in app's native lib dir?
    File nativeBin = new File(context.getApplicationInfo().nativeLibraryDir, "libourvon.so");
    if (nativeBin.exists()) {
      binaryPath = nativeBin.getAbsolutePath();
      mode = BackendMode.LOCAL;
      status = BackendStatus.STOPPED;
      savePrefs();
      return;
    }

    // Check 2: Is ourvon installed via Termux?
    File termuxBin = new File("/data/data/com.termux/files/usr/bin/ourvon");
    if (termuxBin.exists()) {
      binaryPath = termuxBin.getAbsolutePath();
      mode = BackendMode.TERMUX;
      status = BackendStatus.STOPPED;
      savePrefs();
      return;
    }

    // Check 3: Is ourvon in system PATH?
    try {
      Process which = Runtime.getRuntime().exec("which ourvon");
      BufferedReader r = new BufferedReader(new InputStreamReader(which.getInputStream()));
      String line = r.readLine();
      r.close();
      if (line != null && !line.isEmpty()) {
        binaryPath = line.trim();
        mode = BackendMode.LOCAL;
        status = BackendStatus.STOPPED;
        savePrefs();
        return;
      }
    } catch (Exception ignored) {}

    status = BackendStatus.NOT_INSTALLED;
  }

  private void savePrefs() {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_BACKEND_MODE, mode.name().toLowerCase())
        .putString(KEY_BINARY_PATH, binaryPath != null ? binaryPath : "")
        .apply();
  }

  public BackendStatus getStatus() { return status; }
  public BackendMode getMode() { return mode; }
  public String getServerUrl() { return serverUrl; }
  public String getBinaryPath() { return binaryPath; }

  public void setBackendMode(BackendMode newMode, String customPath) {
    this.mode = newMode;
    if (customPath != null) this.binaryPath = customPath;
    savePrefs();
    detectBackend();
  }

  /** Start the local ourvon server */
  public synchronized void startServer(ServerCallback callback) {
    if (status == BackendStatus.RUNNING || status == BackendStatus.STARTING) {
      if (callback != null) callback.onResult(true, "Already running");
      return;
    }

    if (binaryPath == null || binaryPath.isEmpty()) {
      if (callback != null) callback.onResult(false, "Backend not installed");
      return;
    }

    status = BackendStatus.STARTING;

    new Thread(() -> {
      try {
        String cwd = context.getFilesDir().getAbsolutePath();

        ProcessBuilder pb = new ProcessBuilder(
            binaryPath, "serve",
            "--hostname", "127.0.0.1",
            "--port", "4096"
        );
        pb.directory(new File(cwd));
        pb.environment().put("HOME", cwd);
        pb.environment().put("OURVON_SERVER_USERNAME", "ourvon");
        pb.environment().put("OURVON_SERVER_PASSWORD", "");

        // Redirect stderr to stdout
        pb.redirectErrorStream(true);
        serverProcess = pb.start();

        // Wait for "listening on" in output
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(serverProcess.getInputStream()));
        String line;
        long startTime = System.currentTimeMillis();
        boolean started = false;

        while ((line = reader.readLine()) != null) {
          Log.d(TAG, "[ourvon] " + line);
          if (line.contains("listening on") || line.contains("server started")) {
            started = true;
            break;
          }
          if (System.currentTimeMillis() - startTime > 30000) break; // 30s timeout
        }

        if (started) {
          status = BackendStatus.RUNNING;
          if (callback != null) callback.onResult(true, "Server started on " + serverUrl);
        } else {
          status = BackendStatus.ERROR;
          if (callback != null) callback.onResult(false, "Server start timeout");
        }
      } catch (Exception e) {
        Log.e(TAG, "Start failed", e);
        status = BackendStatus.ERROR;
        if (callback != null) callback.onResult(false, e.getMessage());
      }
    }).start();
  }

  /** Stop the local server */
  public void stopServer() {
    if (serverProcess != null) {
      serverProcess.destroy();
      serverProcess = null;
    }
    status = BackendStatus.STOPPED;
  }

  public interface ServerCallback {
    void onResult(boolean success, String message);
  }
}
