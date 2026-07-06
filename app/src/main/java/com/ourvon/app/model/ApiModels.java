package com.ourvon.app.model;

import java.util.List;
import java.util.Map;

public class ApiModels {

  // ── Health ──
  public static class HealthResponse {
    public boolean healthy;
  }

  // ── Session ──
  public static class SessionInfo {
    public String id;
    public String agent;
    public String model;
    public String status;
    public long createdAt;
    public Map<String, Object> config;
  }

  public static class SessionListResponse {
    public List<SessionInfo> data;
    public Cursor cursor;
  }

  public static class SessionCreateRequest {
    public String id;
    public String agent;
    public String model;
  }

  // ── Prompt ──
  public static class PromptRequest {
    public PromptInput prompt;
    public String delivery;
    public static PromptRequest create(String text) {
      PromptRequest r = new PromptRequest();
      r.prompt = new PromptInput();
      r.prompt.text = text;
      return r;
    }
  }

  public static class PromptInput {
    public String text;
  }

  public static class SessionInputAdmitted {
    public String id;
  }

  // ── Messages ──
  public static class MessageListResponse {
    public List<Message> data;
    public Cursor cursor;
  }

  public static class Message {
    public String id;
    public String role;
    public String text;
    public List<ContentPart> content;
    public long createdAt;
    public String status;

    public Message() {}
    public Message(String role, String text) {
      this.role = role;
      this.text = text;
    }
  }

  public static class ContentPart {
    public String type;
    public String text;
    public ToolCall toolCall;
    public ToolResult toolResult;
  }

  public static class ToolCall {
    public String id;
    public String name;
    public String input;
    public String state;
    public String progress;
  }

  public static class ToolResult {
    public String id;
    public String name;
    public boolean success;
    public String output;
  }

  // ── SSE Events ──
  public static class SseEvent {
    public String id;
    public String type;
    public Map<String, Object> data;

    public String getTextDelta() {
      if (data != null && data.containsKey("text")) {
        Object t = data.get("text");
        return t != null ? t.toString() : "";
      }
      return "";
    }
  }

  // ── Providers ──
  public static class ProviderInfo {
    public String id;
    public String name;
    public String status;
    public List<String> models;
  }

  // ── Agents ──
  public static class AgentInfo {
    public String id;
    public String name;
    public String description;
  }

  // ── Models ──
  public static class ModelInfo {
    public String id;
    public String name;
    public String provider;
  }

  // ── Files ──
  public static class FileEntry {
    public String name;
    public String path;
    public String type;
    public long size;
    public long modifiedAt;
  }

  public static class FileListResponse {
    public List<FileEntry> entries;
  }

  // ── Cursor ──
  public static class Cursor {
    public String previous;
    public String next;
  }

  // ── Commands ──
  public static class CommandInfo {
    public String id;
    public String description;
  }

  // ── Skills ──
  public static class SkillInfo {
    public String id;
    public String name;
    public String description;
  }

  // ── Error ──
  public static class ApiError {
    public String _tag;
    public String message;
  }
}
