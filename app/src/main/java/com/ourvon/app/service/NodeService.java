package com.ourvon.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class NodeService extends Service {
    private static final String TAG = "OurvonNodeService";
    private static final String CHANNEL_ID = "ourvon_node";
    private static final int NOTIF_ID = 1001;
    private static Thread nodeThread;
    private static volatile boolean running = false;

    // Called from native-lib.cpp -> Java_com_ourvon_app_service_NodeService_sendMessageToNode
    public static native void sendMessageToNode(String channel, String message);
    public static native String getCurrentABIName();
    public static native void registerNodeDataDirPath(String dataDir);
    public static native int startNodeWithArguments(Object[] arguments, String modulesPath, boolean redirectOutput);

    static {
        System.loadLibrary("rn_bridge");
    }

    public static void sendMessageToApplication(String channel, String message) {
        Log.d(TAG, "Node msg [" + channel + "]: " + message);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            startNode();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        if (nodeThread != null) {
            nodeThread.interrupt();
        }
        super.onDestroy();
    }

    private void startNode() {
        nodeThread = new Thread(() -> {
            try {
                Log.d(TAG, "Starting Node.js...");
                String dataDir = getFilesDir().getAbsolutePath();
                String nodeDir = dataDir + "/nodejs-project";
                registerNodeDataDirPath(dataDir);

                // Copy assets to filesDir
                copyAssets("nodejs-project", nodeDir, false);

                // Get built-in modules path
                String modulesPath = dataDir;

                // Arguments: node, main.js
                Object[] args = new Object[]{"node", nodeDir + "/main.js"};
                int exitCode = startNodeWithArguments(args, modulesPath, true);
                Log.d(TAG, "Node.js exited with code: " + exitCode);
            } catch (Exception e) {
                Log.e(TAG, "Node.js start failed", e);
                running = false;
            }
        }, "nodejs-main");
        nodeThread.setDaemon(true);
        nodeThread.start();
    }

    private void copyAssets(String assetPath, String destPath, boolean overwrite) {
        try {
            File dest = new File(destPath);
            if (dest.exists() && !overwrite) return;

            AssetManager am = getAssets();
            String[] files = am.list(assetPath);
            if (files != null && files.length > 0) {
                // It's a directory
                dest.mkdirs();
                for (String f : files) {
                    copyAssets(assetPath + "/" + f, destPath + "/" + f, overwrite);
                }
            } else {
                // It's a file
                InputStream in = am.open(assetPath);
                OutputStream out = new FileOutputStream(dest);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                in.close();
                out.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Copy assets failed: " + assetPath, e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Ourvon AI Engine",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
            .setContentTitle("Ourvon AI")
            .setContentText("Engine running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build();
    }

    public static boolean isRunning() { return running; }
}
