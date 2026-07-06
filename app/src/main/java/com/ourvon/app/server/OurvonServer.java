package com.ourvon.app.server;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

public class OurvonServer extends NanoHTTPD {

  private static final String TAG = "OurvonServer";
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private final Gson gson = new GsonBuilder().create();
  private final OkHttpClient http;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final String filesDir;

  private static final String ZEN_API = "https://api.opencode.ai/zen/v1/chat/completions";

  static class Session {
    final String id;
    final List<Map<String, Object>> messages = Collections.synchronizedList(new ArrayList<>());
    final Queue<Map<String, Object>> eventQueue = new LinkedList<>();
    final Object eventLock = new Object();
    volatile boolean interrupted;
    Thread agentThread;
    long createdAt = System.currentTimeMillis();

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
        if (method == Method.GET) return json(200, map("data", listSessions(), "cursor", map()));
        if (method == Method.POST) {
          String id = UUID.randomUUID().toString();
          sessions.put(id, new Session(id));
          return json(200, map("id", id, "status", "active"));
        }
      }

      if (method == Method.GET && uri.matches("/api/session/[^/]+/message"))
        return handleMessages(uri, session);

      if (method == Method.POST && uri.matches("/api/session/[^/]+/prompt"))
        return handlePrompt(uri, body);

      if (method == Method.GET && uri.matches("/api/session/[^/]+/event"))
        return handleEvents(uri);

      if (method == Method.POST && uri.matches("/api/session/[^/]+/interrupt")) {
        String sid = uri.replaceAll("/api/session/([^/]+)/interrupt", "$1");
        Session s = sessions.get(sid);
        if (s != null) s.interrupted = true;
        return json(200, map("ok", true));
      }

      if (uri.equals("/api/provider"))
        return json(200, listOf(map("id", "opencode", "name", "Zen API", "models", listOf("auto"))));

      if (uri.equals("/api/agent"))
        return json(200, listOf(map("id", "default", "name", "Default")));

      if (uri.equals("/api/model"))
        return json(200, listOf(map("id", "auto", "name", "Auto", "provider", "opencode")));

      return json(404, map("error", "Not Found"));
    } catch (Exception e) {
      Log.e(TAG, "Error: " + uri, e);
      return json(500, map("error", e.getMessage()));
    }
  }

  private List<Map<String, Object>> listSessions() {
    List<Map<String, Object>> list = new ArrayList<>();
    for (Session s : sessions.values())
      list.add(map("id", s.id, "status", "active"));
    return list;
  }

  private Response handleMessages(String uri, IHTTPSession session) {
    String sid = uri.replaceAll("/api/session/([^/]+)/message", "$1");
    Session s = sessions.get(sid);
    if (s == null) return json(404, map("error", "No session"));

    int limit = 50;
    try { limit = Integer.parseInt(session.getParameters().get("limit").get(0)); } catch (Exception ignored) {}

    List<Object> data = new ArrayList<>();
    synchronized (s.messages) {
      int start = Math.max(0, s.messages.size() - limit);
      for (int i = start; i < s.messages.size(); i++)
        data.add(s.messages.get(i));
    }
    return json(200, map("data", data, "cursor", map()));
  }

  private Response handlePrompt(String uri, String body) {
    String sid = uri.replaceAll("/api/session/([^/]+)/prompt", "$1");
    Session s = sessions.get(sid);
    if (s == null) return json(404, map("error", "No session"));

    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> req = gson.fromJson(body, Map.class);
      String text = "";
      if (req != null && req.get("prompt") instanceof Map)
        text = (String) ((Map<?, ?>) req.get("prompt")).get("text");
      if (text.isEmpty()) return json(400, map("error", "Empty prompt"));

      String msgId = UUID.randomUUID().toString();
      Map<String, Object> userMsg = map("id", msgId, "role", "user",
          "content", listOf(map("type", "text", "text", text)),
          "createdAt", System.currentTimeMillis());
      s.messages.add(userMsg);

      s.interrupted = false;
      startAgent(s, text);
      return json(200, map("id", msgId));
    } catch (Exception e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  private void startAgent(Session s, String text) {
    s.agentThread = new Thread(() -> {
      try {
        postSse(s, "session.next.prompt.admitted", map());

        // Build Zen messages
        List<Map<String, Object>> zenMsgs = new ArrayList<>();
        zenMsgs.add(map("role", "system", "content",
            "You are OURVON, an AI coding assistant. Be concise and helpful."));
        synchronized (s.messages) {
          for (Map<String, Object> msg : s.messages) {
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            if (content instanceof List) {
              StringBuilder sb = new StringBuilder();
              for (Object part : (List<?>) content) {
                if (part instanceof Map && "text".equals(((Map<?, ?>) part).get("type"))
                    && ((Map<?, ?>) part).get("text") != null)
                  sb.append(((Map<?, ?>) part).get("text"));
              }
              if (sb.length() > 0)
                zenMsgs.add(map("role", "user".equals(role) ? "user" : "assistant", "content", sb.toString()));
            }
          }
        }

        Map<String, Object> zenReq = map("model", "auto", "messages", zenMsgs, "stream", true);
        String reqJson = gson.toJson(zenReq);

        okhttp3.Response zenResp = http.newCall(new Request.Builder()
            .url(ZEN_API)
            .post(RequestBody.create(reqJson, JSON))
            .build()).execute();

        StringBuilder fullText = new StringBuilder();

        if (!zenResp.isSuccessful()) {
          String err = zenResp.body() != null ? zenResp.body().string() : "unknown";
          postSse(s, "session.next.text.delta", map("text", "Error: " + err));
        } else {
          InputStream is = zenResp.body() != null ? zenResp.body().byteStream() : null;
          if (is != null) {
            Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\n\\n");
            while (sc.hasNext() && !s.interrupted) {
              String chunk = sc.next().trim();
              for (String line : chunk.split("\\n")) {
                if (line.startsWith("data: ")) {
                  String d = line.substring(6).trim();
                  if ("[DONE]".equals(d)) break;
                  try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> json = gson.fromJson(d, Map.class);
                    if (json != null && json.get("choices") instanceof List) {
                      List<?> choices = (List<?>) json.get("choices");
                      if (!choices.isEmpty() && choices.get(0) instanceof Map) {
                        Object delta = ((Map<?, ?>) choices.get(0)).get("delta");
                        if (delta instanceof Map && ((Map<?, ?>) delta).get("content") != null) {
                          String t = (String) ((Map<?, ?>) delta).get("content");
                          fullText.append(t);
                          postSse(s, "session.next.text.delta", map("text", t));
                        }
                      }
                    }
                  } catch (Exception ignored) {}
                }
              }
            }
            sc.close();
          }
        }
        zenResp.close();

        if (!s.interrupted && fullText.length() > 0) {
          s.messages.add(map("id", UUID.randomUUID().toString(), "role", "assistant",
              "content", listOf(map("type", "text", "text", fullText.toString())),
              "createdAt", System.currentTimeMillis()));
        }

        postSse(s, "session.next.text.ended", map());
        postSse(s, "session.idle", map());
      } catch (Exception e) {
        Log.e(TAG, "Agent error", e);
        postSse(s, "session.next.text.delta", map("text", "Server error: " + e.getMessage()));
        postSse(s, "session.next.text.ended", map());
        postSse(s, "session.idle", map());
      }
    });
    s.agentThread.setDaemon(true);
    s.agentThread.start();
  }

  private void postSse(Session s, String type, Map<String, Object> data) {
    synchronized (s.eventLock) {
      s.eventQueue.add(map("id", UUID.randomUUID().toString(), "type", type, "data", data));
      s.eventLock.notifyAll();
    }
  }

  private Response handleEvents(String uri) {
    String sid = uri.replaceAll("/api/session/([^/]+)/event", "$1");
    Session s = sessions.get(sid);
    if (s == null) return json(404, map("error", "No session"));

    try {
      PipedInputStream in = new PipedInputStream();
      PipedOutputStream out = new PipedOutputStream(in);

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
              String json = gson.toJson(event.get("data"));
              String sse = "id: " + eid + "\nevent: " + type + "\ndata: " + json + "\n\n";
              out.write(sse.getBytes("UTF-8"));
              out.flush();
            } else {
              out.write(": heartbeat\n\n".getBytes("UTF-8"));
              out.flush();
            }
          }
        } catch (IOException ignored) {
        } finally {
          try { out.close(); } catch (IOException ignored) {}
        }
      });
      writer.setDaemon(true);
      writer.start();

      return newChunkedResponse(Response.Status.OK, "text/event-stream", in);
    } catch (IOException e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  // ── Helpers ──

  private String readBody(IHTTPSession session) {
    try {
      Map<String, String> files = new HashMap<>();
      session.parseBody(files);
      return files.getOrDefault("postData", "");
    } catch (Exception e) {
      return "";
    }
  }

  private Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private List<Object> listOf(Object... items) {
    List<Object> l = new ArrayList<>();
    Collections.addAll(l, items);
    return l;
  }

  private Response json(int status, Object data) {
    return newFixedLengthResponse(Response.Status.lookup(status), "application/json", gson.toJson(data));
  }

  @Override
  public void stop() {
    for (Session s : sessions.values()) {
      s.interrupted = true;
      if (s.agentThread != null) s.agentThread.interrupt();
    }
    super.stop();
  }
}
