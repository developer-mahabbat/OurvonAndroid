package com.ourvon.app.server;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OurvonServer extends NanoHTTPD {

  private static final String TAG = "OurvonServer";
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private final Gson gson = new GsonBuilder().create();
  private final OkHttpClient http;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final String filesDir;

  private static final String ZEN_API_URL = "https://api.opencode.ai/zen/v1/chat/completions";

  static class Session {
    final String id;
    final List<Map<String, Object>> messages = Collections.synchronizedList(new ArrayList<>());
    final Queue<Map<String, Object>> eventQueue = new LinkedList<>();
    final Object eventLock = new Object();
    volatile boolean interrupted;
    Thread agentThread;
    long createdAt = System.currentTimeMillis();
    PipedOutputStream eventOutput;

    Session(String id) { this.id = id; }
  }

  public OurvonServer(int port, String filesDir) {
    super(port);
    this.filesDir = filesDir;
    this.http = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build();
  }

  @Override
  public Response serve(IHTTPSession session) {
    String uri = session.getUri();
    Method method = session.getMethod();
    String body = readBody(session);

    try {
      if (uri.equals("/api/health") && method == Method.GET)
        return json(200, map("healthy", true));

      if (uri.equals("/api/session")) {
        if (method == Method.GET) return handleListSessions();
        if (method == Method.POST) return handleCreateSession(body);
      }

      if (uri.matches("/api/session/[^/]+/prompt") && method == Method.POST)
        return handleSendPrompt(uri, body);

      if (uri.matches("/api/session/[^/]+/message") && method == Method.GET)
        return handleGetMessages(uri, session);

      if (uri.matches("/api/session/[^/]+/event") && method == Method.GET)
        return handleEvents(uri);

      if (uri.matches("/api/session/[^/]+/interrupt") && method == Method.POST)
        return handleInterrupt(uri);

      if (uri.equals("/api/fs/list") && method == Method.GET)
        return handleListFiles(session);

      if (uri.startsWith("/api/fs/read/") && method == Method.GET)
        return handleReadFile(uri);

      if (uri.equals("/api/provider") && method == Method.GET)
        return handleListProviders();

      if (uri.equals("/api/agent") && method == Method.GET)
        return handleListAgents();

      if (uri.equals("/api/model") && method == Method.GET)
        return handleListModels();

      return json(404, map("error", "Not Found"));
    } catch (Exception e) {
      Log.e(TAG, "Error serving " + uri, e);
      return json(500, map("error", e.getMessage()));
    }
  }

  // ────────── Session Management ──────────

  private Response handleListSessions() {
    List<Map<String, Object>> list = new ArrayList<>();
    for (Session s : sessions.values()) {
      list.add(map("id", s.id, "status", "active", "createdAt", s.createdAt));
    }
    return json(200, map("data", list, "cursor", map()));
  }

  private Response handleCreateSession(String body) {
    try {
      Map<String, Object> req = gson.fromJson(body, Map.class);
      String id = req != null && req.get("id") != null ? (String) req.get("id") : UUID.randomUUID().toString();
      sessions.put(id, new Session(id));
      return json(200, map("id", id, "status", "active"));
    } catch (Exception e) {
      String id = UUID.randomUUID().toString();
      sessions.put(id, new Session(id));
      return json(200, map("id", id, "status", "active"));
    }
  }

  private Response handleGetMessages(String uri, IHTTPSession session) {
    String sid = extractId(uri, "/api/session/", "/message");
    Session s = sessions.get(sid);
    if (s == null) return json(404, map("error", "Session not found"));

    int limit = 100;
    try {
      String l = session.getParameters().get("limit") != null
          ? session.getParameters().get("limit").get(0) : "100";
      limit = Integer.parseInt(l);
    } catch (Exception ignored) {}

    List<Object> data = new ArrayList<>();
    synchronized (s.messages) {
      int start = Math.max(0, s.messages.size() - limit);
      for (int i = start; i < s.messages.size(); i++) {
        data.add(s.messages.get(i));
      }
    }
    return json(200, map("data", data, "cursor", map()));
  }

  private Response handleSendPrompt(String uri, String body) {
    String sid = extractId(uri, "/api/session/", "/prompt");
    Session s = sessions.get(sid);
    if (s == null) return json(404, map("error", "Session not found"));

    try {
      Map<String, Object> req = gson.fromJson(body, Map.class);
      String text = null;
      if (req != null && req.get("prompt") instanceof Map) {
        text = (String) ((Map) req.get("prompt")).get("text");
      }
      if (text == null || text.isEmpty()) return json(400, map("error", "Empty prompt"));

      // Add user message
      Map<String, Object> userMsg = map("id", UUID.randomUUID().toString(), "role", "user",
          "content", listOf(map("type", "text", "text", text)),
          "createdAt", System.currentTimeMillis());
      s.messages.add(userMsg);

      // Start agent in background thread
      s.interrupted = false;
      startAgent(s, text);

      return json(200, map("id", UUID.randomUUID().toString()));
    } catch (Exception e) {
      Log.e(TAG, "Prompt error", e);
      return json(500, map("error", e.getMessage()));
    }
  }

  private Response handleInterrupt(String uri) {
    String sid = extractId(uri, "/api/session/", "/interrupt");
    Session s = sessions.get(sid);
    if (s == null) return json(404, map("error", "Session not found"));
    s.interrupted = true;
    return json(200, map("ok", true));
  }

  // ────────── Agent / LLM ──────────

  private void startAgent(Session s, String userText) {
    s.agentThread = new Thread(() -> {
      try {
        // Step admitted
        postSse(s, "session.next.prompt.admitted", map());

        // Build message array for Zen API
        List<Map<String, Object>> zenMessages = new ArrayList<>();
        zenMessages.add(map("role", "system", "content",
            "You are OURVON, an AI coding assistant. Help the user with code, files, and development tasks."));
        synchronized (s.messages) {
          for (Map<String, Object> msg : s.messages) {
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            if (content instanceof List) {
              List<Map<String, Object>> parts = (List<Map<String, Object>>) content;
              StringBuilder sb = new StringBuilder();
              for (Map<String, Object> p : parts) {
                if ("text".equals(p.get("type")) && p.get("text") != null)
                  sb.append(p.get("text"));
              }
              if (sb.length() > 0)
                zenMessages.add(map("role", role.equals("assistant") ? "assistant" : "user", "content", sb.toString()));
            }
          }
        }

        Map<String, Object> zenReq = map(
            "model", "auto",
            "messages", zenMessages,
            "stream", true
        );

        String reqJson = gson.toJson(zenReq);
        Log.d(TAG, "Zen API request: " + reqJson.substring(0, Math.min(300, reqJson.length())));

        Request req = new Request.Builder()
            .url(ZEN_API_URL)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(reqJson, JSON))
            .build();

        StringBuilder responseText = new StringBuilder();

        try (Response zenResp = http.newCall(req).execute()) {
          if (!zenResp.isSuccessful()) {
            String errBody = zenResp.body() != null ? zenResp.body().string() : "unknown";
            Log.e(TAG, "Zen API error: " + zenResp.code() + " " + errBody);
            postSse(s, "session.next.text.delta", map("text", "\nError: " + zenResp.code() + " - " + errBody));
            postSse(s, "session.next.text.ended", map());
            postSse(s, "session.idle", map());
            return;
          }

          // Parse SSE from Zen API response
          try (InputStream is = zenResp.body() != null ? zenResp.body().byteStream() : null) {
            if (is == null) throw new IOException("Empty response body");

            Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\n\\n");
            while (scanner.hasNext() && !s.interrupted) {
              String event = scanner.next().trim();
              if (event.isEmpty()) continue;

              for (String line : event.split("\\n")) {
                if (line.startsWith("data: ")) {
                  String data = line.substring(6).trim();
                  if (data.equals("[DONE]")) break;

                  try {
                    Map<String, Object> chunk = gson.fromJson(data, Map.class);
                    if (chunk != null && chunk.get("choices") instanceof List) {
                      List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                      if (!choices.isEmpty()) {
                        Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                        if (delta != null && delta.get("content") != null) {
                          String deltaText = (String) delta.get("content");
                          responseText.append(deltaText);
                          postSse(s, "session.next.text.delta", map("text", deltaText));
                        }
                      }
                    }
                  } catch (Exception ignored) {}
                }
              }
            }
          }
        }

        // Finalize
        if (!s.interrupted && responseText.length() > 0) {
          Map<String, Object> asstMsg = map(
              "id", UUID.randomUUID().toString(),
              "role", "assistant",
              "content", listOf(map("type", "text", "text", responseText.toString())),
              "createdAt", System.currentTimeMillis()
          );
          s.messages.add(asstMsg);
        }

        postSse(s, "session.next.text.ended", map());
        postSse(s, "session.idle", map());
      } catch (Exception e) {
        Log.e(TAG, "Agent error", e);
        postSse(s, "session.next.text.delta", map("text", "\nServer error: " + e.getMessage()));
        postSse(s, "session.next.text.ended", map());
        postSse(s, "session.idle", map());
      }
    });
    s.agentThread.setDaemon(true);
    s.agentThread.start();
  }

  private void postSse(Session s, String type, Map<String, Object> data) {
    String id = UUID.randomUUID().toString();
    String jsonData = gson.toJson(data);
    String sse = "id: " + id + "\nevent: " + type + "\ndata: " + jsonData + "\n\n";
    synchronized (s.eventLock) {
      if (s.eventOutput != null) {
        try {
          s.eventOutput.write(sse.getBytes("UTF-8"));
          s.eventOutput.flush();
        } catch (IOException e) {
          Log.w(TAG, "SSE write failed", e);
        }
      }
      s.eventQueue.add(map("id", id, "type", type, "data", data));
      s.eventLock.notifyAll();
    }
  }

  // ────────── SSE Events ──────────

  private Response handleEvents(String uri) {
    String sid = extractId(uri, "/api/session/", "/event");
    Session s = sessions.get(sid);
    if (s == null) return json(404, map("error", "Session not found"));

    try {
      PipedInputStream input = new PipedInputStream();
      PipedOutputStream output = new PipedOutputStream(input);
      s.eventOutput = output;

      // Background writer for queued events and heartbeats
      Thread writer = new Thread(() -> {
        try {
          while (true) {
            Map<String, Object> event;
            synchronized (s.eventLock) {
              event = s.eventQueue.poll();
              if (event == null) {
                try { s.eventLock.wait(30000); } catch (InterruptedException e) { break; }
                event = s.eventQueue.poll();
              }
            }
            if (event != null) {
              String eid = (String) event.get("id");
              String type = (String) event.get("type");
              String edata = gson.toJson(event.get("data"));
              String sse = "id: " + eid + "\nevent: " + type + "\ndata: " + edata + "\n\n";
              output.write(sse.getBytes("UTF-8"));
              output.flush();
            } else if (s.agentThread == null || !s.agentThread.isAlive()) {
              output.write(": heartbeat\n\n".getBytes("UTF-8"));
              output.flush();
            }
          }
        } catch (IOException ignored) {
        } finally {
          try { output.close(); } catch (IOException ignored) {}
        }
      });
      writer.setDaemon(true);
      writer.start();

      return newChunkedResponse(Response.Status.OK, "text/event-stream", input);
    } catch (IOException e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  // ────────── File System ──────────

  private Response handleListFiles(IHTTPSession session) {
    try {
      String path = session.getParameters().get("path") != null
          ? session.getParameters().get("path").get(0) : "";
      File dir = new File(filesDir, path);
      if (!dir.exists() || !dir.isDirectory()) dir = new File(filesDir);

      List<Map<String, Object>> entries = new ArrayList<>();
      File[] files = dir.listFiles();
      if (files != null) {
        for (File f : files) {
          Map<String, Object> entry = new HashMap<>();
          entry.put("name", f.getName());
          entry.put("path", f.getAbsolutePath().substring(filesDir.length()));
          entry.put("type", f.isDirectory() ? "directory" : "file");
          entry.put("size", f.length());
          entry.put("modifiedAt", f.lastModified());
          entries.add(entry);
        }
      }
      return json(200, map("entries", entries));
    } catch (Exception e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  private Response handleReadFile(String uri) {
    try {
      String path = uri.substring("/api/fs/read/".length());
      File f = new File(filesDir, path);
      if (!f.exists() || f.isDirectory()) return json(404, map("error", "File not found"));
      String content = new String(Files.readAllBytes(f.toPath()));
      return json(200, map("content", content));
    } catch (Exception e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  // ────────── Provider / Agent / Model ──────────

  private Response handleListProviders() {
    return json(200, listOf(
        map("id", "opencode", "name", "Zen (opencode.ai)", "status", "active",
            "models", listOf("auto", "gpt-4o", "claude-3"))
    ));
  }

  private Response handleListAgents() {
    return json(200, listOf(
        map("id", "default", "name", "Default Agent", "description", "General-purpose coding assistant")
    ));
  }

  private Response handleListModels() {
    return json(200, listOf(
        map("id", "auto", "name", "Auto (Zen)", "provider", "opencode"),
        map("id", "gpt-4o", "name", "GPT-4o", "provider", "opencode"),
        map("id", "claude-3", "name", "Claude 3", "provider", "opencode")
    ));
  }

  // ────────── Helpers ──────────

  private String readBody(IHTTPSession session) {
    try {
      Map<String, String> files = new HashMap<>();
      session.parseBody(files);
      String body = files.get("postData");
      return body != null ? body : "";
    } catch (Exception e) {
      return "";
    }
  }

  private String extractId(String uri, String prefix, String suffix) {
    int start = prefix.length();
    int end = uri.indexOf(suffix);
    return end > start ? uri.substring(start, end) : uri.substring(start);
  }

  private Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2)
      m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private List<Object> listOf(Object... items) {
    List<Object> l = new ArrayList<>();
    Collections.addAll(l, items);
    return l;
  }

  private Response json(int status, Object data) {
    String json = gson.toJson(data);
    return newFixedLengthResponse(Response.Status.lookup(status), "application/json", json);
  }

  @Override
  public void stop() {
    for (Session s : sessions.values()) {
      s.interrupted = true;
      if (s.agentThread != null) s.agentThread.interrupt();
      if (s.eventOutput != null) {
        try { s.eventOutput.close(); } catch (IOException ignored) {}
      }
    }
    super.stop();
    Log.d(TAG, "Server stopped");
  }
}
