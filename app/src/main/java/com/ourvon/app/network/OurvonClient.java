package com.ourvon.app.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.ourvon.app.model.ApiModels;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

public class OurvonClient {

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private final Gson gson = new GsonBuilder().create();
  private final OkHttpClient http;
  private final OkHttpClient sseHttp;
  private String baseUrl;
  private final String username;
  private final String password;
  private volatile boolean connected;

  public OurvonClient(String baseUrl, String username, String password) {
    setBaseUrl(baseUrl);
    this.username = username != null ? username : "ourvon";
    this.password = password != null ? password : "";

    OkHttpClient.Builder common = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(chain -> {
          Request orig = chain.request();
          Request.Builder b = orig.newBuilder();
          if (!this.password.isEmpty()) {
            b.header("Authorization", okhttp3.Credentials.basic(this.username, this.password));
          }
          return chain.proceed(b.build());
        });

    this.http = common
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build();

    this.sseHttp = common
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build();
  }

  public void setBaseUrl(String url) {
    url = url.trim();
    if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
    if (!url.startsWith("http")) url = "http://" + url;
    this.baseUrl = url;
  }

  public String getBaseUrl() { return baseUrl; }
  public boolean isConnected() { return connected; }

  // ──────────────── HTTP helpers ────────────────

  private String url(String path) { return baseUrl + path; }

  private String get(String path) throws IOException {
    Request req = new Request.Builder().url(url(path)).get().build();
    try (Response r = http.newCall(req).execute()) {
      return r.body() != null ? r.body().string() : "";
    }
  }

  private String post(String path, Object body) throws IOException {
    String json = body != null ? gson.toJson(body) : "";
    Request req = new Request.Builder()
        .url(url(path))
        .post(RequestBody.create(json, JSON))
        .build();
    try (Response r = http.newCall(req).execute()) {
      return r.body() != null ? r.body().string() : "";
    }
  }

  private String postEmpty(String path) throws IOException {
    Request req = new Request.Builder()
        .url(url(path))
        .post(RequestBody.create("", null))
        .build();
    try (Response r = http.newCall(req).execute()) {
      return r.body() != null ? r.body().string() : "";
    }
  }

  // ──────────────── Health ────────────────

  public boolean checkHealth() throws IOException {
    String body = get("/api/health");
    ApiModels.HealthResponse h = gson.fromJson(body, ApiModels.HealthResponse.class);
    connected = h != null && h.healthy;
    return connected;
  }

  // ──────────────── Sessions ────────────────

  public ApiModels.SessionInfo createSession(String agent, String model) throws IOException {
    ApiModels.SessionCreateRequest req = new ApiModels.SessionCreateRequest();
    req.agent = agent;
    req.model = model;
    String body = post("/api/session", req);
    return gson.fromJson(body, ApiModels.SessionInfo.class);
  }

  public List<ApiModels.SessionInfo> listSessions(int limit) throws IOException {
    String body = get("/api/session?limit=" + limit + "&order=desc");
    ApiModels.SessionListResponse r = gson.fromJson(body, ApiModels.SessionListResponse.class);
    return r != null && r.data != null ? r.data : Collections.emptyList();
  }

  // ──────────────── Prompt ────────────────

  public ApiModels.SessionInputAdmitted sendPrompt(String sessionId, String text) throws IOException {
    String body = post("/api/session/" + sessionId + "/prompt",
        ApiModels.PromptRequest.create(text));
    return gson.fromJson(body, ApiModels.SessionInputAdmitted.class);
  }

  public void interruptSession(String sessionId) throws IOException {
    postEmpty("/api/session/" + sessionId + "/interrupt");
  }

  // ──────────────── Messages ────────────────

  public List<ApiModels.Message> getMessages(String sessionId, int limit) throws IOException {
    String body = get("/api/session/" + sessionId + "/message?limit=" + limit + "&order=asc");
    ApiModels.MessageListResponse r = gson.fromJson(body, ApiModels.MessageListResponse.class);
    return r != null && r.data != null ? r.data : Collections.emptyList();
  }

  // ──────────────── SSE events ────────────────

  public EventSource subscribeEvents(String sessionId, EventSourceListener listener) {
    Request req = new Request.Builder()
        .url(url("/api/session/" + sessionId + "/event"))
        .header("Accept", "text/event-stream")
        .build();
    return EventSources.createFactory(sseHttp).newEventSource(req, listener);
  }

  // ──────────────── Providers ────────────────

  public List<ApiModels.ProviderInfo> listProviders() throws IOException {
    String body = get("/api/provider");
    Type t = new TypeToken<List<ApiModels.ProviderInfo>>() {}.getType();
    List<ApiModels.ProviderInfo> r = gson.fromJson(body, t);
    return r != null ? r : Collections.emptyList();
  }

  // ──────────────── Agents ────────────────

  public List<ApiModels.AgentInfo> listAgents() throws IOException {
    String body = get("/api/agent");
    Type t = new TypeToken<List<ApiModels.AgentInfo>>() {}.getType();
    List<ApiModels.AgentInfo> r = gson.fromJson(body, t);
    return r != null ? r : Collections.emptyList();
  }

  // ──────────────── Models ────────────────

  public List<ApiModels.ModelInfo> listModels() throws IOException {
    String body = get("/api/model");
    Type t = new TypeToken<List<ApiModels.ModelInfo>>() {}.getType();
    List<ApiModels.ModelInfo> r = gson.fromJson(body, t);
    return r != null ? r : Collections.emptyList();
  }

  // ──────────────── Filesystem ────────────────

  public List<ApiModels.FileEntry> listFiles(String path) throws IOException {
    String p = path != null && !path.isEmpty() ? "?path=" + java.net.URLEncoder.encode(path, "UTF-8") : "";
    String body = get("/api/fs/list" + p);
    ApiModels.FileListResponse r = gson.fromJson(body, ApiModels.FileListResponse.class);
    return r != null && r.entries != null ? r.entries : Collections.emptyList();
  }

  public String readFile(String path) throws IOException {
    String p = java.net.URLEncoder.encode(path, "UTF-8");
    return get("/api/fs/read/" + p);
  }

  // ──────────────── Commands ────────────────

  public List<ApiModels.CommandInfo> listCommands() throws IOException {
    String body = get("/api/command");
    Type t = new TypeToken<List<ApiModels.CommandInfo>>() {}.getType();
    List<ApiModels.CommandInfo> r = gson.fromJson(body, t);
    return r != null ? r : Collections.emptyList();
  }

  // ──────────────── Skills ────────────────

  public List<ApiModels.SkillInfo> listSkills() throws IOException {
    String body = get("/api/skill");
    Type t = new TypeToken<List<ApiModels.SkillInfo>>() {}.getType();
    List<ApiModels.SkillInfo> r = gson.fromJson(body, t);
    return r != null ? r : Collections.emptyList();
  }
}
