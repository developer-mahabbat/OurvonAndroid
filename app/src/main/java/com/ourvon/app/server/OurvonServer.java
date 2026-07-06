package com.ourvon.app.server;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

  private static final String ZEN_API = "https://opencode.ai/zen/v1/chat/completions";
  private static final String MODEL = "deepseek-v4-flash-free";

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
        Session s = sessions.get(uri.replaceAll("/api/session/([^/]+)/interrupt", "$1"));
        if (s != null) s.interrupted = true;
        return json(200, map("ok", true));
      }

      if (uri.equals("/api/fs/list") && method == Method.GET)
        return handleFsList(session);
      if (uri.startsWith("/api/fs/read/") && method == Method.GET)
        return handleFsRead(uri);
      if (uri.equals("/api/fs/write") && method == Method.POST)
        return handleFsWrite(body);

      if (uri.equals("/api/command") && method == Method.POST)
        return handleCommand(body);

      if (uri.equals("/api/provider"))
        return json(200, listOf(map("id", "opencode", "name", "Zen Free", "models",
            listOf("deepseek-v4-flash-free", "big-pickle", "mimo-v2.5-free", "north-mini-code-free", "nemotron-3-ultra-free"))));
      if (uri.equals("/api/agent"))
        return json(200, listOf(map("id", "default", "name", "Default Agent"),
            map("id", "research", "name", "Research"), map("id", "code", "name", "Code")));
      if (uri.equals("/api/model"))
        return json(200, listOf(map("id", "deepseek-v4-flash-free", "name", "DeepSeek V4 Flash Free", "provider", "opencode"),
            map("id", "big-pickle", "name", "Big Pickle", "provider", "opencode")));
      if (uri.equals("/api/skill"))
        return json(200, listOf(map("id", "file_ops", "name", "File Operations"),
            map("id", "bash", "name", "Command Execution"), map("id", "web_search", "name", "Web Search")));

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
      for (int i = start; i < s.messages.size(); i++) data.add(s.messages.get(i));
    }
    return json(200, map("data", data, "cursor", map()));
  }

  private Response handlePrompt(String uri, String body) {
    String sid = uri.replaceAll("/api/session/([^/]+)/prompt", "$1");
    Session s = sessions.get(sid);
    if (s == null) return json(404, map("error", "No session"));
    try {
      Map<String, Object> req = gson.fromJson(body, Map.class);
      String text = "";
      if (req != null && req.get("prompt") instanceof Map)
        text = (String) ((Map<?, ?>) req.get("prompt")).get("text");
      if (text == null || text.isEmpty()) return json(400, map("error", "Empty prompt"));

      String msgId = UUID.randomUUID().toString();
      s.messages.add(map("id", msgId, "role", "user",
          "content", listOf(map("type", "text", "text", text)),
          "createdAt", System.currentTimeMillis()));
      s.interrupted = false;
      startAgent(s);
      return json(200, map("id", msgId));
    } catch (Exception e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  // ─── Agent Loop with Tool Calls ───

  private void startAgent(Session s) {
    s.agentThread = new Thread(() -> {
      try {
        postSse(s, "session.next.prompt.admitted", map());

        int turn = 0;
        while (!s.interrupted && turn < 10) {
          turn++;
          List<Map<String, Object>> msgs = buildZenMessages(s);
          Map<String, Object> zenReq = map("model", MODEL, "messages", msgs, "stream", true);
          String reqJson = gson.toJson(zenReq);

          okhttp3.Response zenResp = http.newCall(new Request.Builder()
              .url(ZEN_API)
              .header("Content-Type", "application/json")
              .post(RequestBody.create(reqJson, JSON))
              .build()).execute();

          if (!zenResp.isSuccessful()) {
            String err = zenResp.body() != null ? zenResp.body().string() : "unknown";
            postSse(s, "session.next.text.delta", map("text", "\n[API Error: " + zenResp.code() + " " + err + "]"));
            zenResp.close();
            break;
          }

          StringBuilder content = new StringBuilder();
          String toolCallId = null, toolName = null, toolArgs = null;
          boolean hasToolCall = false;

          InputStream is = zenResp.body() != null ? zenResp.body().byteStream() : null;
          if (is != null) {
            Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\n\\n");
            while (sc.hasNext() && !s.interrupted) {
              String chunk = sc.next().trim();
              for (String line : chunk.split("\\n")) {
                if (!line.startsWith("data: ")) continue;
                String d = line.substring(6).trim();
                if ("[DONE]".equals(d)) break;
                try {
                  Map<String, Object> json = gson.fromJson(d, Map.class);
                  if (json == null || !(json.get("choices") instanceof List)) continue;
                  List<?> choices = (List<?>) json.get("choices");
                  if (choices.isEmpty() || !(choices.get(0) instanceof Map)) continue;
                  Object delta = ((Map<?, ?>) choices.get(0)).get("delta");
                  if (!(delta instanceof Map)) continue;
                  Map<?, ?> dmap = (Map<?, ?>) delta;

                  if (dmap.get("content") != null) {
                    String t = dmap.get("content").toString();
                    content.append(t);
                    postSse(s, "session.next.text.delta", map("text", t));
                  }

                  // Check for tool_calls
                  Object tc = dmap.get("tool_calls");
                  if (tc instanceof List) {
                    List<?> tcList = (List<?>) tc;
                    for (Object tco : tcList) {
                      if (tco instanceof Map) {
                        Map<?, ?> tcm = (Map<?, ?>) tco;
                        if (tcm.get("function") instanceof Map) {
                          Map<?, ?> fn = (Map<?, ?>) tcm.get("function");
                          toolCallId = tcm.get("id") != null ? tcm.get("id").toString() : UUID.randomUUID().toString();
                          if (fn.get("name") != null) {
                            toolName = fn.get("name").toString();
                            hasToolCall = true;
                          }
                          if (fn.get("arguments") != null) {
                            String argPart = fn.get("arguments").toString();
                            toolArgs = (toolArgs == null ? "" : toolArgs) + argPart;
                          }
                        }
                      }
                    }
                  }
                } catch (Exception ignored) {}
              }
            }
            sc.close();
          }
          zenResp.close();

          if (hasToolCall && toolName != null) {
            postSse(s, "session.next.tool.called", map("name", toolName, "id", toolCallId));
            String result = executeTool(toolName, toolArgs);
            s.messages.add(map("role", "assistant", "content", content.toString()));
            s.messages.add(map("role", "tool", "tool_call_id", toolCallId, "name", toolName, "content", result));
            postSse(s, "session.next.tool.result", map("name", toolName, "result", truncate(result, 500)));
            postSse(s, "session.next.step.ended", map());
          } else {
            if (content.length() > 0) {
              s.messages.add(map("id", UUID.randomUUID().toString(), "role", "assistant",
                  "content", listOf(map("type", "text", "text", content.toString())),
                  "createdAt", System.currentTimeMillis()));
            }
            break;
          }
        }
        postSse(s, "session.next.text.ended", map());
        postSse(s, "session.idle", map());
      } catch (Exception e) {
        Log.e(TAG, "Agent error", e);
        postSse(s, "session.next.text.delta", map("text", "\nError: " + e.getMessage()));
        postSse(s, "session.next.text.ended", map());
        postSse(s, "session.idle", map());
      }
    });
    s.agentThread.setDaemon(true);
    s.agentThread.start();
  }

  private List<Map<String, Object>> buildZenMessages(Session s) {
    List<Map<String, Object>> msgs = new ArrayList<>();
    msgs.add(map("role", "system", "content",
        "You are OURVON, an AI coding assistant running on Android. " +
        "You have access to tools:\n" +
        "- `read_file(path)` - Read file content\n" +
        "- `write_file(path, content)` - Write/create a file\n" +
        "- `list_files(path)` - List directory contents\n" +
        "- `bash(command)` - Execute a bash command in the app's sandbox\n" +
        "- `web_search(query)` - Search the web for info\n" +
        "Use tools when helpful. Be concise. You are on-device, no cloud needed."));
    synchronized (s.messages) {
      for (Map<String, Object> msg : s.messages) {
        String role = (String) msg.get("role");
        Object content = msg.get("content");
        if ("tool".equals(role)) {
          msgs.add(map("role", "tool", "tool_call_id", msg.get("tool_call_id"),
              "content", msg.get("content")));
        } else if (content instanceof List) {
          StringBuilder sb = new StringBuilder();
          for (Object p : (List<?>) content) {
            if (p instanceof Map && "text".equals(((Map<?, ?>) p).get("type"))
                && ((Map<?, ?>) p).get("text") != null)
              sb.append(((Map<?, ?>) p).get("text"));
          }
          if (sb.length() > 0)
            msgs.add(map("role", "user".equals(role) ? "user" : "assistant", "content", sb.toString()));
        }
      }
    }
    return msgs;
  }

  private String executeTool(String name, String argsJson) {
    try {
      Map<String, Object> args = argsJson != null ? gson.fromJson(argsJson, Map.class) : new HashMap<>();
      switch (name) {
        case "read_file": {
          String path = (String) args.get("path");
          if (path == null) return "Error: path required";
          File f = new File(filesDir, path);
          if (!f.exists()) return "Error: file not found: " + path;
          return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        case "write_file": {
          String path = (String) args.get("path");
          String content = (String) args.get("content");
          if (path == null) return "Error: path required";
          File f = new File(filesDir, path);
          f.getParentFile().mkdirs();
          Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
          return "Written " + content.length() + " bytes to " + path;
        }
        case "list_files": {
          String path = args.get("path") != null ? (String) args.get("path") : "";
          File dir = new File(filesDir, path);
          if (!dir.isDirectory()) return "Error: not a directory";
          StringBuilder sb = new StringBuilder();
          File[] files = dir.listFiles();
          if (files != null) {
            for (File f : files) {
              sb.append(f.isDirectory() ? "[DIR] " : "[FILE] ").append(f.getName()).append("\n");
            }
          }
          return sb.length() > 0 ? sb.toString() : "(empty)";
        }
        case "bash": {
          String cmd = (String) args.get("command");
          if (cmd == null) return "Error: command required";
          Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
          p.waitFor(15, TimeUnit.SECONDS);
          StringBuilder out = new StringBuilder();
          try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) out.append(l).append("\n");
          }
          try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String l; while ((l = r.readLine()) != null) out.append(l).append("\n");
          }
          String result = out.toString().trim();
          return result.isEmpty() ? "(no output)" : truncate(result, 2000);
        }
        case "web_search": {
          String q = (String) args.get("query");
          if (q == null) return "Error: query required";
          return webSearch(q);
        }
        default:
          return "Unknown tool: " + name;
      }
    } catch (Exception e) {
      return "Error executing " + name + ": " + e.getMessage();
    }
  }

  private String webSearch(String query) {
    try {
      String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8");
      okhttp3.Response resp = http.newCall(new Request.Builder().url(url).get().build()).execute();
      String html = resp.body() != null ? resp.body().string() : "";
      resp.close();
      // Simple extraction of result snippets
      StringBuilder sb = new StringBuilder();
      String[] parts = html.split("<a rel=\"nofollow\" class=\"result__a\" href=\"");
      for (int i = 1; i < Math.min(parts.length, 6); i++) {
        String p = parts[i];
        int hrefEnd = p.indexOf("\"");
        if (hrefEnd > 0) {
          String link = p.substring(0, hrefEnd);
          sb.append(link).append("\n");
        }
        // Extract snippet
        int snipStart = p.indexOf("class=\"result__snippet\"");
        if (snipStart > 0) {
          int tagEnd = p.indexOf(">", snipStart);
          int snipEnd = p.indexOf("</", tagEnd);
          if (tagEnd > 0 && snipEnd > tagEnd) {
            String snippet = p.substring(tagEnd + 1, snipEnd)
                .replaceAll("<[^>]+>", "").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#x27;", "'");
            sb.append(snippet).append("\n\n");
          }
        }
      }
      String result = sb.toString().trim();
      return result.isEmpty() ? "(no results)" : truncate(result, 1500);
    } catch (Exception e) {
      return "Search error: " + e.getMessage();
    }
  }

  // ─── API Handlers ───

  private Response handleFsList(IHTTPSession session) {
    try {
      String path = session.getParameters().get("path") != null
          ? session.getParameters().get("path").get(0) : "";
      File dir = new File(filesDir, path);
      if (!dir.isDirectory()) dir = new File(filesDir);
      List<Map<String, Object>> entries = new ArrayList<>();
      File[] files = dir.listFiles();
      if (files != null) {
        for (File f : files) {
          entries.add(map("name", f.getName(), "path", f.getAbsolutePath().substring(filesDir.length()),
              "type", f.isDirectory() ? "directory" : "file", "size", f.length(), "modifiedAt", f.lastModified()));
        }
      }
      return json(200, map("entries", entries));
    } catch (Exception e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  private Response handleFsRead(String uri) {
    try {
      String path = uri.substring("/api/fs/read/".length());
      File f = new File(filesDir, path);
      if (!f.exists()) return json(404, map("error", "Not found"));
      return json(200, map("content", new String(Files.readAllBytes(f.toPath()))));
    } catch (Exception e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  private Response handleFsWrite(String body) {
    try {
      Map<String, Object> req = gson.fromJson(body, Map.class);
      String path = (String) req.get("path");
      String content = (String) req.get("content");
      if (path == null) return json(400, map("error", "path required"));
      File f = new File(filesDir, path);
      f.getParentFile().mkdirs();
      Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
      return json(200, map("ok", true, "size", content != null ? content.length() : 0));
    } catch (Exception e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  private Response handleCommand(String body) {
    try {
      Map<String, Object> req = gson.fromJson(body, Map.class);
      String cmd = req != null ? (String) req.get("command") : null;
      if (cmd == null) return json(400, map("error", "command required"));
      Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
      p.waitFor(30, TimeUnit.SECONDS);
      StringBuilder stdout = new StringBuilder();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
        String l; while ((l = r.readLine()) != null) stdout.append(l).append("\n");
      }
      StringBuilder stderr = new StringBuilder();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
        String l; while ((l = r.readLine()) != null) stderr.append(l).append("\n");
      }
      return json(200, map("exitCode", p.exitValue(), "stdout", stdout.toString(), "stderr", stderr.toString()));
    } catch (Exception e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  // ─── SSE ───

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
              out.write(("id: " + eid + "\nevent: " + type + "\ndata: " + json + "\n\n").getBytes("UTF-8"));
              out.flush();
            } else {
              out.write(": heartbeat\n\n".getBytes("UTF-8"));
              out.flush();
            }
          }
        } catch (IOException ignored) {
        } finally { try { out.close(); } catch (IOException ignored) {} }
      });
      writer.setDaemon(true);
      writer.start();
      return newChunkedResponse(Response.Status.OK, "text/event-stream", in);
    } catch (IOException e) {
      return json(500, map("error", e.getMessage()));
    }
  }

  // ─── Helpers ───

  private String readBody(IHTTPSession session) {
    try {
      Map<String, String> files = new HashMap<>();
      session.parseBody(files);
      return files.getOrDefault("postData", "");
    } catch (Exception e) { return ""; }
  }

  private String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "\n... (truncated)";
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
    for (Session s : sessions.values()) { s.interrupted = true; if (s.agentThread != null) s.agentThread.interrupt(); }
    super.stop();
  }
}
