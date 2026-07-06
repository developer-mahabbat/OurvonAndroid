package com.ourvon.app.adapter;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import com.google.android.material.card.MaterialCardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.ourvon.app.R;
import com.ourvon.app.model.ApiModels;

public class ChatAdapter extends ListAdapter<ApiModels.Message, ChatAdapter.Holder> {

  public ChatAdapter() {
    super(new DiffUtil.ItemCallback<ApiModels.Message>() {
      @Override
      public boolean areItemsTheSame(@NonNull ApiModels.Message a, @NonNull ApiModels.Message b) {
        return a.id != null && a.id.equals(b.id);
      }
      @Override
      public boolean areContentsTheSame(@NonNull ApiModels.Message a, @NonNull ApiModels.Message b) {
        if (a.content == null && b.content == null) return true;
        if (a.content == null || b.content == null) return false;
        if (a.content.size() != b.content.size()) return false;
        for (int i = 0; i < a.content.size(); i++) {
          String ta = a.content.get(i) != null ? a.content.get(i).text : null;
          String tb = b.content.get(i) != null ? b.content.get(i).text : null;
          if (ta == null && tb == null) continue;
          if (ta == null || !ta.equals(tb)) return false;
        }
        return true;
      }
    });
  }

  @NonNull @Override
  public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new Holder(
        LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_chat_message, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull Holder h, int pos) {
    h.bind(getItem(pos));
  }

  static class Holder extends RecyclerView.ViewHolder {
    private final TextView roleLabel, messageText;
    private final MaterialCardView messageCard;
    private final LinearLayout container;

    Holder(@NonNull View v) {
      super(v);
      roleLabel = v.findViewById(R.id.roleLabel);
      messageText = v.findViewById(R.id.messageText);
      messageCard = v.findViewById(R.id.messageCard);
      container = v.findViewById(R.id.messageContainer);
    }

    void bind(ApiModels.Message msg) {
      boolean isUser = "user".equals(msg.role);
      roleLabel.setText(isUser ? "You" : "OURVON");

      StringBuilder text = new StringBuilder();
      if (msg.content != null) {
        for (ApiModels.ContentPart p : msg.content) {
          if ("text".equals(p.type) && p.text != null) {
            text.append(p.text);
          }
        }
      }

      messageText.setText(text.length() > 0 ? text.toString() : (isUser ? "" : "..."));

      if (isUser) {
        // User: orange bubble, right-aligned
        messageCard.setCardBackgroundColor(
            ContextCompat.getColor(itemView.getContext(), R.color.user_bubble));
        messageText.setTextColor(0xFFFFFFFF);
        container.setHorizontalGravity(Gravity.END);
        roleLabel.setGravity(Gravity.END);
      } else {
        // Assistant: light gray bubble, left-aligned
        messageCard.setCardBackgroundColor(
            ContextCompat.getColor(itemView.getContext(), R.color.assistant_bubble));
        messageText.setTextColor(
            ContextCompat.getColor(itemView.getContext(), R.color.assistant_bubble_text));
        container.setHorizontalGravity(Gravity.START);
        roleLabel.setGravity(Gravity.START);
      }
    }
  }
}
