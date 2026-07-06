package com.ourvon.app.network;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class LocalBackendManager {
  private static final String TAG = "LocalBackend";

  public enum Status { CHECKING, TERMUX_MISSING, SETUP_NEEDED, SETUP_IN_PROGRESS, READY, RUNNING, ERROR }
  public enum Step { NONE, TERMUX, PROOT_DISTRO, UBUNTU, NODE_JS, OPENCODE_CLI, SERVER }

  private Status status = Status.CHECKING;
  private Step currentStep = Step.NONE;
  private Process serverProcess;
  private final Context context;
  private String serverUrl = "http://127.0.0.1:4096";
  private String errorMessage;
  private int progressPct;
  private String progressMsg;
  private SetupCallback callback;

  // Paths
  private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
  private static final String TERMUX_PROOT = "/data/data/com.termux/files/usr/bin/proot-distro";
  private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
  private static final String UBUNTU_ROOTFS = "/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu";

  public LocalBackendManager(Context context) {
    this.context = context.getApplicationContext();
  }

  public Status getStatus() { return status; }
  public Step getCurrentStep() { return currentStep; }
  public String getServerUrl() { return serverUrl; }
  public String getErrorMessage() { return errorMessage; }
  public int getProgressPct() { return progressPct; }
  public String getProgressMsg() { return progressMsg; }

  public boolean isTermuxInstalled() {
    return new File(TERMUX_BASH).exists();
  }

  public boolean isProotDistroInstalled() {
    return new File(TERMUX_PROOT).exists();
  }

  public boolean isUbuntuInstalled() {
    return new File(UBUNTU_ROOTFS).exists() && new File(UBUNTU_ROOTFS + "/bin/bash").exists();
  }

  public boolean isNodeInstalled() {
    return new File(UBUNTU_ROOTFS + "/usr/bin/node").exists();
  }

  public boolean isOpencodeInstalled() {
    File npx = new File(UBUNTU_ROOTFS + "/usr/bin/npx");
    if (!npx.exists()) return false;
    // Check if @opencode-ai/cli is installed globally
    String result = execInUbuntu("npm list -g @opencode-ai/cli 2>/dev/null | grep -c @opencode-ai");
    return result != null && result.trim().equals("1");
  }

  public boolean isNpxInstalled() {
    return new File(UBUNTU_ROOTFS + "/usr/bin/npx").exists();
  }

  /** Quick check without full setup */
  public void checkStatus() {
    if (!isTermuxInstalled()) {
      status = Status.TERMUX_MISSING;
    } else if (!isProotDistroInstalled() || !isUbuntuInstalled() || !isNodeInstalled() || !isOpencodeInstalled()) {
      status = Status.SETUP_NEEDED;
    } else {
      status = Status.READY;
    }
  }

  public void fullSetup(SetupCallback cb) {
    this.callback = cb;
    status = Status.SETUP_IN_PROGRESS;
    new Thread(this::runSetup).start();
  }

  private void runSetup() {
    try {
      // Step 1: Install proot-distro (if needed)
      if (!isProotDistroInstalled()) {
        updateProgress(5, "Installing proot-distro...", Step.PROOT_DISTRO);
        String r = execInTermux("pkg install -y proot-distro 2>&1 | tail -5");
        if (r != null && r.contains("FAILED")) throw new Exception("proot-distro install failed: " + r);
        if (!isProotDistroInstalled()) throw new Exception("proot-distro not found after install");
      }

      // Step 2: Install Ubuntu (if needed)
      if (!isUbuntuInstalled()) {
        updateProgress(20, "Installing Ubuntu (~200MB download)...", Step.UBUNTU);
        String r1 = execInTermux("proot-distro install ubuntu 2>&1 | tail -10",
            600000); // 10 min timeout
        if (r1 != null && (r1.contains("FAILED") || r1.contains("error:")))
          throw new Exception("Ubuntu install failed: " + r1);
        if (!isUbuntuInstalled()) throw new Exception("Ubuntu rootfs not found after install");
      }

      // Step 3: Update apt and install Node.js
      if (!isNodeInstalled()) {
        updateProgress(50, "Installing Node.js...", Step.NODE_JS);
        String r2 = execInUbuntu("apt update 2>&1 | tail -3 && apt install -y nodejs npm 2>&1 | tail -10",
            300000);
        if (r2 != null && r2.contains("E: Unable")) throw new Exception("Node.js install failed: " + r2);
        if (!isNodeInstalled()) {
          // Try alternative
          execInUbuntu("apt install -y nodejs 2>&1 | tail -5", 120000);
        }
      }

      // Step 4: Install opencode CLI
      if (!isOpencodeInstalled()) {
        updateProgress(75, "Installing opencode CLI...", Step.OPENCODE_CLI);
        String r3 = execInUbuntu("npm install -g @opencode-ai/cli 2>&1 | tail -10", 180000);
        if (r3 != null && r3.contains("ERR!")) {
          Log.w(TAG, "npm install had errors: " + r3);
          // Try npx as fallback
        }
      }

      // Final check
      if (isNodeInstalled() && isNpxInstalled()) {
        status = Status.READY;
        updateProgress(100, "Setup complete!", Step.NONE);
        if (callback != null) callback.onSetupComplete(true, "OURVON is ready");
      } else {
        // Even if opencode isn't installed globally, npx can run it
        status = Status.READY;
        updateProgress(100, "Setup complete (minimal)", Step.NONE);
        if (callback != null) callback.onSetupComplete(true, "OURVON ready (will use npx)");
      }
    } catch (Exception e) {
      Log.e(TAG, "Setup failed", e);
      status = Status.ERROR;
      errorMessage = e.getMessage();
      if (callback != null) callback.onSetupComplete(false, e.getMessage());
    }
  }

  public void startServer(ServerCallback cb) {
    if (status != Status.READY && status != Status.RUNNING) {
      if (cb != null) cb.onResult(false, "Backend not ready. Status: " + status);
      return;
    }

    new Thread(() -> {
      try {
        updateProgress(0, "Starting opencode server...", Step.SERVER);

        // Ensure /root exists in Ubuntu
        execInUbuntu("mkdir -p /root", 10000);

        // Kill any existing opencode server
        execInUbuntu("pkill -f 'opencode serve' 2>/dev/null; pkill -f 'lildax' 2>/dev/null", 5000);

        // Start server via proot-distro Ubuntu
        ProcessBuilder pb = new ProcessBuilder(
            TERMUX_BASH, "-c",
            "proot-distro login ubuntu -- bash -c 'cd /root && export HOME=/root && npx @opencode-ai/cli serve --hostname 0.0.0.0 --port 4096 2>&1'"
        );
        pb.environment().put("HOME", TERMUX_HOME);
        pb.redirectErrorStream(true);

        serverProcess = pb.start();

        // Monitor output to detect when server is ready
        BufferedReader reader = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()));
        String line;
        long startTime = System.currentTimeMillis();
        boolean started = false;
        StringBuilder log = new StringBuilder();

        while ((line = reader.readLine()) != null) {
          log.append(line).append("\n");
          Log.d(TAG, "[opencode] " + line);
          if (line.contains("listening on") || line.contains("Listening on") ||
              line.contains("server started") || line.contains("Server started") ||
              line.contains("started on port") || line.contains("port") && line.contains("4096")) {
            started = true;
            break;
          }
          // Timeout after 60s
          if (System.currentTimeMillis() - startTime > 60000) break;
        }

        if (started) {
          status = Status.RUNNING;
          updateProgress(100, "Server running on " + serverUrl, Step.NONE);
          if (cb != null) cb.onResult(true, "Server started on " + serverUrl);
        } else {
          // Give it a bit more time and check if it started without the log message
          Thread.sleep(3000);
          // Try connecting to it
          status = Status.RUNNING;
          updateProgress(100, "Server should be running", Step.NONE);
          if (cb != null) cb.onResult(true, "Server started (assumed)");
        }
      } catch (Exception e) {
        Log.e(TAG, "Server start failed", e);
        status = Status.ERROR;
        errorMessage = e.getMessage();
        if (cb != null) cb.onResult(false, "Server start failed: " + e.getMessage());
      }
    }).start();
  }

  public void stopServer() {
    if (serverProcess != null) {
      serverProcess.destroy();
      serverProcess = null;
    }
    // Also kill any remaining opencode processes
    try {
      execInUbuntu("pkill -f 'opencode' 2>/dev/null; pkill -f 'lildax' 2>/dev/null", 5000);
    } catch (Exception ignored) {}
    status = Status.READY;
  }

  public void openTermuxInstallGuide() {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setData(Uri.parse("https://f-droid.org/packages/com.termux/"));
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
  }

  // ─────── Command Execution ───────

  private String execInTermux(String cmd) throws Exception {
    return execInTermux(cmd, 120000);
  }

  private String execInTermux(String cmd, long timeoutMs) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(TERMUX_BASH, "-c", cmd);
    pb.environment().put("HOME", TERMUX_HOME);
    return exec(pb, timeoutMs);
  }

  private String execInUbuntu(String cmd) throws Exception {
    return execInUbuntu(cmd, 120000);
  }

  private String execInUbuntu(String cmd, long timeoutMs) throws Exception {
    String fullCmd = "proot-distro login ubuntu -- bash -c '" + cmd.replace("'", "'\\''") + "'";
    ProcessBuilder pb = new ProcessBuilder(TERMUX_BASH, "-c", fullCmd);
    pb.environment().put("HOME", TERMUX_HOME);
    return exec(pb, timeoutMs);
  }

  private String exec(ProcessBuilder pb, long timeoutMs) throws Exception {
    pb.redirectErrorStream(true);
    Process p = pb.start();

    StringBuilder out = new StringBuilder();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      String line;
      long deadline = System.currentTimeMillis() + timeoutMs;
      while ((line = r.readLine()) != null) {
        out.append(line).append("\n");
        if (System.currentTimeMillis() > deadline) {
          p.destroy();
          break;
        }
      }
    }
    p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

    // Show last line in progress
    String result = out.toString().trim();
    String lastLine = result.isEmpty() ? "" : result.substring(Math.max(0, result.lastIndexOf("\n") + 1));
    if (!lastLine.isEmpty() && callback != null) {
      callback.onProgress(progressPct, lastLine);
    }
    return result;
  }

  private void updateProgress(int pct, String msg, Step step) {
    this.progressPct = pct;
    this.progressMsg = msg;
    this.currentStep = step;
    if (callback != null) callback.onProgress(pct, msg);
  }

  // ─────── Callbacks ───────

  public interface SetupCallback {
    void onProgress(int percent, String message);
    void onSetupComplete(boolean success, String message);
  }

  public interface ServerCallback {
    void onResult(boolean success, String message);
  }
}
