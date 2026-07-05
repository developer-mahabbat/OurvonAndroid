package com.ourvon.app.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;
import com.ourvon.app.R;
import com.ourvon.app.adapter.FileAdapter;
import com.ourvon.app.model.ApiModels;
import com.ourvon.app.network.OurvonClient;

import java.util.ArrayList;

public class FileBrowserActivity extends AppCompatActivity {

  private OurvonClient client;
  private FileAdapter adapter;
  private RecyclerView fileList;
  private TextView currentPath, filePreview;

  private String cwd = "";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_file_browser);

    MaterialToolbar toolbar = findViewById(R.id.fileToolbar);
    toolbar.setNavigationOnClickListener(v -> finish());

    currentPath = findViewById(R.id.currentPath);
    filePreview = findViewById(R.id.filePreview);
    fileList = findViewById(R.id.fileList);

    adapter = new FileAdapter();
    fileList.setLayoutManager(new LinearLayoutManager(this));
    fileList.setAdapter(adapter);

    SharedPreferences p = getSharedPreferences("ourvon_prefs", MODE_PRIVATE);
    client = new OurvonClient(
        p.getString("server_url", "192.168.1.100:4096"),
        p.getString("username", "ourvon"),
        p.getString("password", ""));

    adapter.setOnFileClickListener(entry -> {
      if ("directory".equals(entry.type)) {
        cwd = entry.path != null ? entry.path : cwd + "/" + entry.name;
        loadDir();
      } else {
        readFile(entry);
      }
    });

    loadDir();
  }

  private void loadDir() {
    currentPath.setText("Dir: " + (cwd.isEmpty() ? "/" : cwd));
    filePreview.setVisibility(android.view.View.GONE);

    new Thread(() -> {
      try {
        java.util.List<ApiModels.FileEntry> entries = client.listFiles(cwd);
        // Add ".." for navigation
        java.util.List<ApiModels.FileEntry> all = new ArrayList<>();
        if (!cwd.isEmpty()) {
          ApiModels.FileEntry up = new ApiModels.FileEntry();
          up.name = "..";
          up.type = "directory";
          up.path = cwd.contains("/") ? cwd.substring(0, cwd.lastIndexOf('/')) : "";
          if (up.path.isEmpty()) up.path = "";
          all.add(up);
        }
        if (entries != null) all.addAll(entries);

        runOnUiThread(() -> adapter.submitList(all));
      } catch (Exception e) {
        runOnUiThread(() ->
            Snackbar.make(fileList, "Error: " + e.getMessage(), Snackbar.LENGTH_LONG).show());
      }
    }).start();
  }

  private void readFile(ApiModels.FileEntry entry) {
    String path = entry.path != null ? entry.path : cwd + "/" + entry.name;
    filePreview.setVisibility(android.view.View.VISIBLE);
    filePreview.setText("Loading...");

    new Thread(() -> {
      try {
        String content = client.readFile(path);
        String preview = content.length() > 2000 ? content.substring(0, 2000) + "\n... [truncated]" : content;
        runOnUiThread(() -> {
          filePreview.setText(preview);
          currentPath.setText("File: " + path + " (" + content.length() + " chars)");
        });
      } catch (Exception e) {
        runOnUiThread(() -> filePreview.setText("Error: " + e.getMessage()));
      }
    }).start();
  }
}
