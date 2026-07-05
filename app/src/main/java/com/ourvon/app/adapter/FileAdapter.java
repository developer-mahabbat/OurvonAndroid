package com.ourvon.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ourvon.app.R;
import com.ourvon.app.model.ApiModels;

import java.util.ArrayList;
import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.Holder> {

  private final List<ApiModels.FileEntry> items = new ArrayList<>();
  private OnFileClickListener listener;

  public interface OnFileClickListener {
    void onFileClick(ApiModels.FileEntry entry);
  }

  public void setOnFileClickListener(OnFileClickListener l) { this.listener = l; }

  public void submitList(List<ApiModels.FileEntry> list) {
    items.clear();
    if (list != null) items.addAll(list);
    notifyDataSetChanged();
  }

  public List<ApiModels.FileEntry> getItems() { return items; }

  @NonNull @Override
  public Holder onCreateViewHolder(@NonNull ViewGroup parent, int i) {
    return new Holder(
        LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_file, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull Holder h, int pos) {
    h.bind(items.get(pos));
  }

  @Override public int getItemCount() { return items.size(); }

  class Holder extends RecyclerView.ViewHolder {
    private final ImageView icon;
    private final TextView name, info;

    Holder(@NonNull View v) {
      super(v);
      icon = v.findViewById(R.id.fileIcon);
      name = v.findViewById(R.id.fileName);
      info = v.findViewById(R.id.fileInfo);
      v.setOnClickListener(vv -> {
        if (listener != null) listener.onFileClick(items.get(getAdapterPosition()));
      });
    }

    void bind(ApiModels.FileEntry f) {
      boolean isDir = "directory".equals(f.type);
      icon.setImageResource(isDir ? R.drawable.ic_folder
                                   : R.drawable.ic_edit);
      name.setText(f.name != null ? f.name : "?");
      String extra = "";
      if (f.size > 0) extra += formatSize(f.size) + "  ";
      if (f.modifiedAt > 0) extra += formatTime(f.modifiedAt);
      info.setText(extra.trim());
    }

    private String formatSize(long bytes) {
      if (bytes < 1024) return bytes + " B";
      if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
      return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private String formatTime(long ms) {
      return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
          java.util.Locale.getDefault()).format(new java.util.Date(ms));
    }
  }
}
