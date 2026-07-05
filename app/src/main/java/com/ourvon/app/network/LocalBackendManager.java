package com.ourvon.app.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public class LocalBackendManager {
  private static final String TAG = "LocalBackend";
  private static final String PREFS = "ourvon_prefs";
  private static final String KEY_MODE = "backend_mode";
  private static final String KEY_NODE_PATH = "node_path";
  private static final String KEY_CLI_PATH = "cli_path";

  public enum BackendMode { NATIVE, SELF_CONTAINED, TERMUX, SYSTEM_PATH }
  public enum BackendStatus { NOT_INSTALLED, SETUP_NEEDED, SETUP_DOWNLOADING, SETUP_EXTRACTING, STOPPED, STARTING, RUNNING, ERROR }

  private BackendMode mode = BackendMode.NATIVE;
  private BackendStatus status = BackendStatus.NOT_INSTALLED;
  private Process serverProcess;
  private final Context context;
  private String nodeBinPath;
  private String cliEntryPath;
  private String serverUrl = "http://127.0.0.1:4096";

  // Node.js download URLs for different Android architectures
  private static final String NODE_RELEASE = "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v18.0.0";
  private static final String NODE_FILE_ARM64 = "nodejs-mobile-android-arm64.tar.gz";
  private static final String NODE_FILE_ARM = "nodejs-mobile-android-arm.tar.gz";
  private static final String NODE_FILE_X64 = "nodejs-mobile-android-x64.tar.gz";
  private static final String NODE_FILE_X86 = "nodejs-mobile-android-x86.tar.gz";

  public LocalBackendManager(Context context) {
    this.context = context.getApplicationContext();
    loadPrefs();
    detectBackend();
  }

  private void loadPrefs() {
    SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String modeStr = p.getString(KEY_MODE, "native");
    try { mode = BackendMode.valueOf(modeStr.toUpperCase()); }
    catch (Exception e) { mode = BackendMode.NATIVE; }
    nodeBinPath = p.getString(KEY_NODE_PATH, "");
    cliEntryPath = p.getString(KEY_CLI_PATH, "");
  }

  private void savePrefs() {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_MODE, mode.name().toLowerCase())
        .putString(KEY_NODE_PATH, nodeBinPath != null ? nodeBinPath : "")
        .putString(KEY_CLI_PATH, cliEntryPath != null ? cliEntryPath : "")
        .apply();
  }

  public BackendStatus getStatus() { return status; }
  public BackendMode getMode() { return mode; }
  public String getServerUrl() { return serverUrl; }
  public String getNodePath() { return nodeBinPath; }
  public String getCliPath() { return cliEntryPath; }

  private void detectBackend() {
    // Check 1: Bundled native lib (libourvon.so)
    File nativeBin = new File(context.getApplicationInfo().nativeLibraryDir, "libourvon.so");
    if (nativeBin.exists()) {
      nodeBinPath = nativeBin.getAbsolutePath();
      mode = BackendMode.NATIVE;
      status = BackendStatus.STOPPED;
      savePrefs();
      return;
    }

    // Check 2: Bundled APK assets (pre-packaged Node.js + CLI)
    File dataNodeDir = new File(context.getFilesDir(), "node");
    File dataCliDir = new File(context.getFilesDir(), "ourvon-cli");
    File dataNodeBin = new File(dataNodeDir, "bin/node");
    if (!dataNodeBin.exists()) dataNodeBin = new File(dataNodeDir, "node");

    if (!dataNodeBin.exists()) {
      // Check if assets exist and need extracting
      try {
        String[] assets = context.getAssets().list("node");
        if (assets != null && assets.length > 0) {
          status = BackendStatus.SETUP_NEEDED;
          return; // Assets present but not yet extracted
        }
      } catch (Exception ignored) {}
    }

    // Check 3: Self-contained Node.js + CLI in app's private dir (previously extracted)
    File cliEntry = findCliEntry(dataCliDir);
    if (dataNodeBin.exists() && cliEntry != null) {
      nodeBinPath = dataNodeBin.getAbsolutePath();
      cliEntryPath = cliEntry.getAbsolutePath();
      mode = BackendMode.SELF_CONTAINED;
      status = BackendStatus.STOPPED;
      savePrefs();
      return;
    }
    if (dataNodeBin.exists() || dataCliDir.exists()) {
      status = BackendStatus.SETUP_NEEDED;
      return;
    }

    // Check 4: Termux
    File termuxBin = new File("/data/data/com.termux/files/usr/bin/ourvon");
    if (termuxBin.exists()) {
      nodeBinPath = termuxBin.getAbsolutePath();
      mode = BackendMode.TERMUX;
      status = BackendStatus.STOPPED;
      savePrefs();
      return;
    }

    // Check 5: System PATH
    try {
      Process which = Runtime.getRuntime().exec("which ourvon");
      BufferedReader r = new BufferedReader(new InputStreamReader(which.getInputStream()));
      String line = r.readLine();
      r.close();
      if (line != null && !line.isEmpty()) {
        nodeBinPath = line.trim();
        mode = BackendMode.SYSTEM_PATH;
        status = BackendStatus.STOPPED;
        savePrefs();
        return;
      }
    } catch (Exception ignored) {}

    status = BackendStatus.SETUP_NEEDED;
  }

  private File findCliEntry(File cliDir) {
    File[] candidates = {
        new File(cliDir, "packages/cli/src/index.ts"),
        new File(cliDir, "index.js"),
        new File(cliDir, "dist/index.js"),
        new File(cliDir, "build/index.js"),
        new File(cliDir, "lib/index.js"),
    };
    for (File f : candidates) {
      if (f.exists()) return f;
    }
    // Recursive search for any index.js or index.ts
    if (cliDir.exists()) {
      try {
        java.util.Stack<File> stack = new java.util.Stack<>();
        stack.push(cliDir);
        while (!stack.isEmpty()) {
          File dir = stack.pop();
          File[] files = dir.listFiles();
          if (files == null) continue;
          for (File f : files) {
            String name = f.getName();
            if (f.isDirectory()) stack.push(f);
            else if (name.equals("index.js") || name.equals("index.ts")) return f;
          }
        }
      } catch (Exception ignored) {}
    }
    return null;
  }

  public boolean isSelfContainedMode() {
    return mode == BackendMode.SELF_CONTAINED && status == BackendStatus.STOPPED;
  }

  public boolean isSetupNeeded() {
    return status == BackendStatus.SETUP_NEEDED ||
           status == BackendStatus.NOT_INSTALLED;
  }

  private String getNodeArchDir() {
    String abi = Build.CPU_ABI != null ? Build.CPU_ABI.toLowerCase() : "arm64-v8a";
    if (abi.contains("arm64") || abi.contains("aarch64")) return "arm64";
    if (abi.contains("x86_64") || abi.contains("x64")) return "x64";
    if (abi.contains("arm")) return "arm";
    if (abi.contains("x86")) return "x86";
    return "arm64";
  }

  private String getNodeArchiveName() {
    String arch = getNodeArchDir();
    switch (arch) {
      case "arm64": return NODE_FILE_ARM64;
      case "arm": return NODE_FILE_ARM;
      case "x64": return NODE_FILE_X64;
      case "x86": return NODE_FILE_X86;
      default: return NODE_FILE_ARM64;
    }
  }

  private String getNodeDownloadUrl() {
    return NODE_RELEASE + "/" + getNodeArchiveName();
  }

  /** Extract bundled assets or download Node.js + CLI to app's private directory */
  public void setupBackend(SetupCallback callback) {
    status = BackendStatus.SETUP_DOWNLOADING;
    new Thread(() -> {
      try {
        File dataDir = context.getFilesDir();
        File nodeDir = new File(dataDir, "node");
        File cliDir = new File(dataDir, "ourvon-cli");
        boolean extracted = false;

        // Step 1: Try extracting from bundled APK assets first
        if (!new File(nodeDir, "bin/node").exists() && !new File(nodeDir, "node").exists()) {
          try {
            String[] nodeAssets = context.getAssets().list("node");
            if (nodeAssets != null && nodeAssets.length > 0) {
              runOnMain(() -> {
                if (callback != null) callback.onProgress("Extracting Node.js from APK...", 10);
              });
              copyAssetDir("node", nodeDir);
              File nbin = new File(nodeDir, "bin/node");
              if (nbin.exists()) nbin.setExecutable(true);
              File ndir = new File(nodeDir, "node");
              if (ndir.exists()) ndir.setExecutable(true);
              extracted = true;
              runOnMain(() -> {
                if (callback != null) callback.onProgress("Node.js ready", 40);
              });
            }
          } catch (Exception ignored) {}
        }

        // Step 2: If not in assets, download Node.js
        if (!new File(nodeDir, "bin/node").exists() && !new File(nodeDir, "node").exists()) {
          runOnMain(() -> {
            if (callback != null) callback.onProgress("Downloading Node.js runtime (~25MB)...", 0);
          });
          File nodeTar = new File(dataDir, "node.tar.gz");
          try {
            downloadFile(getNodeDownloadUrl(), nodeTar, callback);
          } catch (Exception e) {
            Log.e(TAG, "Node.js download failed", e);
            status = BackendStatus.ERROR;
            if (callback != null) callback.onResult(false, "Node.js download failed: " + e.getMessage());
            return;
          }
          runOnMain(() -> {
            if (callback != null) callback.onProgress("Extracting Node.js...", 50);
          });
          extractTarGz(nodeTar, nodeDir);
          nodeTar.delete();
          File nodeBin = new File(nodeDir, "bin/node");
          if (nodeBin.exists()) nodeBin.setExecutable(true);
          File altNode = new File(nodeDir, "node");
          if (altNode.exists()) altNode.setExecutable(true);
          extracted = true;
        }

        // Step 3: Try extracting CLI from bundled assets
        if (findCliEntry(cliDir) == null) {
          runOnMain(() -> {
            if (callback != null) callback.onProgress("Setting up OURVON CLI...", 60);
          });
          try {
            String[] cliAssets = context.getAssets().list("ourvon-cli");
            if (cliAssets != null && cliAssets.length > 0) {
              copyAssetDir("ourvon-cli", cliDir);
              extracted = true;
              runOnMain(() -> {
                if (callback != null) callback.onProgress("CLI ready", 80);
              });
            }
          } catch (Exception ignored) {}
        }

        // Step 4: If CLI not in assets, try download
        if (findCliEntry(cliDir) == null) {
          runOnMain(() -> {
            if (callback != null) callback.onProgress("Downloading OURVON CLI...", 60);
          });
          File cliTar = new File(dataDir, "cli.tar.gz");
          try {
            downloadFile("https://github.com/developer-mahabbat/OurvonAndroid/releases/download/cli-latest/ourvon-cli.tar.gz", cliTar, callback);
            extractTarGz(cliTar, cliDir);
            cliTar.delete();
          } catch (Exception e) {
            Log.w(TAG, "CLI download failed: " + e.getMessage());
            status = BackendStatus.ERROR;
            if (callback != null) callback.onResult(false, "CLI not available: " + e.getMessage());
            return;
          }
          extracted = true;
        }

        // Refresh detection
        detectBackend();

        if (status == BackendStatus.STOPPED) {
          runOnMain(() -> {
            if (callback != null) callback.onProgress("Setup complete!", 100);
          });
          if (callback != null) callback.onResult(true, extracted ? "Backend installed" : "Already up to date");
        } else {
          status = BackendStatus.ERROR;
          if (callback != null) callback.onResult(false, "Setup incomplete - missing files after extraction");
        }
      } catch (Exception e) {
        Log.e(TAG, "Setup failed", e);
        status = BackendStatus.ERROR;
        if (callback != null) callback.onResult(false, e.getMessage());
      }
    }).start();
  }

  private void copyAssetDir(String assetPath, File destDir) throws Exception {
    String[] list = context.getAssets().list(assetPath);
    if (list == null) return;
    destDir.mkdirs();
    for (String name : list) {
      String fullPath = assetPath + "/" + name;
      File target = new File(destDir, name);
      try {
        // Check if it's a directory by trying to list it
        String[] sub = context.getAssets().list(fullPath);
        if (sub != null && sub.length > 0) {
          copyAssetDir(fullPath, target);
        } else {
          try (InputStream in = context.getAssets().open(fullPath);
               OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
          }
          // Make files in bin/ executable
          if (name.equals("node") || name.equals("ourvon") || fullPath.contains("/bin/")) {
            target.setExecutable(true);
          }
        }
      } catch (java.io.FileNotFoundException e) {
        // Not a directory and not a file - skip
      }
    }
  }

  /** Start the server using whatever backend was detected/setup */
  public synchronized void startServer(ServerCallback callback) {
    if (status == BackendStatus.RUNNING || status == BackendStatus.STARTING) {
      if (callback != null) callback.onResult(true, "Already running");
      return;
    }

    if (status == BackendStatus.SETUP_NEEDED || status == BackendStatus.NOT_INSTALLED) {
      if (callback != null) callback.onResult(false, "Backend not installed");
      return;
    }

    status = BackendStatus.STARTING;

    new Thread(() -> {
      try {
        String cwd = context.getFilesDir().getAbsolutePath();
        ProcessBuilder pb;

        if (mode == BackendMode.NATIVE) {
          pb = new ProcessBuilder(nodeBinPath, "serve", "--hostname", "127.0.0.1", "--port", "4096");
        } else if (mode == BackendMode.SELF_CONTAINED) {
          pb = new ProcessBuilder(nodeBinPath, cliEntryPath, "serve", "--hostname", "127.0.0.1", "--port", "4096");
        } else if (mode == BackendMode.TERMUX || mode == BackendMode.SYSTEM_PATH) {
          pb = new ProcessBuilder(nodeBinPath, "serve", "--hostname", "127.0.0.1", "--port", "4096");
        } else {
          if (callback != null) callback.onResult(false, "Unknown backend mode");
          return;
        }

        pb.directory(new File(cwd));
        pb.environment().put("HOME", cwd);
        pb.environment().put("OURVON_SERVER_USERNAME", "ourvon");
        pb.environment().put("OURVON_SERVER_PASSWORD", "");
        // Make sure node can find its libs when self-contained
        if (mode == BackendMode.SELF_CONTAINED) {
          File nodeDir = new File(context.getFilesDir(), "node");
          pb.environment().put("LD_LIBRARY_PATH", nodeDir.getAbsolutePath() + "/lib");
        }

        pb.redirectErrorStream(true);
        serverProcess = pb.start();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(serverProcess.getInputStream()));
        String line;
        long startTime = System.currentTimeMillis();
        boolean started = false;

        while ((line = reader.readLine()) != null) {
          Log.d(TAG, "[ourvon] " + line);
          if (line.contains("listening on") || line.contains("server started") ||
              line.contains("Listening") || line.contains("started")) {
            started = true;
            break;
          }
          if (System.currentTimeMillis() - startTime > 60000) break;
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

  public void stopServer() {
    if (serverProcess != null) {
      serverProcess.destroy();
      serverProcess = null;
    }
    status = BackendStatus.STOPPED;
  }

  // ────────── Download helpers ──────────

  private void downloadFile(String urlStr, File target, SetupCallback cb) throws Exception {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(30000);
    conn.setReadTimeout(60000);
    conn.connect();

    int total = conn.getContentLength();
    try (InputStream in = conn.getInputStream();
         OutputStream out = new FileOutputStream(target)) {
      byte[] buf = new byte[8192];
      int read, downloaded = 0;
      long lastProgress = 0;
      while ((read = in.read(buf)) != -1) {
        out.write(buf, 0, read);
        downloaded += read;
        long now = System.currentTimeMillis();
        if (cb != null && now - lastProgress > 500) {
          lastProgress = now;
          int pct = total > 0 ? (downloaded * 100 / total) : -1;
          final int fp = pct;
          runOnMain(() -> cb.onProgress("Downloading... " + (fp >= 0 ? fp + "%" : downloaded/1024 + "KB"), fp >= 0 ? fp / 2 : -1));
        }
      }
    }
  }

  private void extractTarGz(File archive, File destDir) throws Exception {
    try (InputStream fis = new java.io.FileInputStream(archive);
         GZIPInputStream gz = new GZIPInputStream(fis)) {
      // Simple tar extraction - reads tar entries
      byte[] buf = new byte[8192];
      while (true) {
        // Read tar header (512 bytes)
        byte[] header = new byte[512];
        int read = readFully(gz, header);
        if (read < 512) break;

        String name = new String(header, 0, 100, "UTF-8").trim();
        if (name.isEmpty() || name.equals("./")) continue;

        // Parse size from header (octal at offset 124, 12 bytes)
        long size = 0;
        try {
          String sizeStr = new String(header, 124, 12, "UTF-8").trim();
          if (!sizeStr.isEmpty()) size = Long.parseLong(sizeStr, 8);
        } catch (Exception ignored) {}

        File outFile = new File(destDir, name);
        if (name.endsWith("/")) {
          outFile.mkdirs();
        } else {
          outFile.getParentFile().mkdirs();
          try (FileOutputStream fos = new FileOutputStream(outFile)) {
            long remaining = size;
            while (remaining > 0) {
              int toRead = (int) Math.min(buf.length, remaining);
              int r = gz.read(buf, 0, toRead);
              if (r <= 0) break;
              fos.write(buf, 0, r);
              remaining -= r;
            }
          }
          // Preserve execute bit
          int mode = 0;
          try {
            String modeStr = new String(header, 100, 8, "UTF-8").trim();
            if (!modeStr.isEmpty()) mode = Integer.parseInt(modeStr, 8);
          } catch (Exception ignored) {}
          if ((mode & 0b001001001) != 0 || (mode & 0b100100100) != 0) {
            outFile.setExecutable(true);
          }
        }

        // Skip padding (blocks are 512 bytes, data is rounded up)
        long skip = (512 - (size % 512)) % 512;
        while (skip > 0) skip -= gz.skip(skip);
      }
    }
  }

  private int readFully(InputStream in, byte[] buf) throws Exception {
    int total = 0;
    while (total < buf.length) {
      int r = in.read(buf, total, buf.length - total);
      if (r <= 0) break;
      total += r;
    }
    return total;
  }

  private void runOnMain(Runnable r) {
    new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
  }

  // ────────── Callbacks ──────────

  public interface ServerCallback {
    void onResult(boolean success, String message);
  }

  public interface SetupCallback {
    void onProgress(String status, int pct);
    void onResult(boolean success, String message);
  }
}
