package com.ourvon.app.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;
import com.ourvon.app.R;
import com.ourvon.app.model.ApiModels;
import com.ourvon.app.network.OurvonClient;

import java.util.ArrayList;
import java.util.List;

public class ProvidersActivity extends AppCompatActivity {

  private OurvonClient client;
  private RecyclerView list;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_providers);

    MaterialToolbar toolbar = findViewById(R.id.providerToolbar);
    toolbar.setNavigationOnClickListener(v -> finish());
    list = findViewById(R.id.providerList);
    list.setLayoutManager(new LinearLayoutManager(this));

    SharedPreferences p = getSharedPreferences("ourvon_prefs", MODE_PRIVATE);
    client = new OurvonClient(
        p.getString("server_url", "192.168.1.100:4096"),
        p.getString("username", "ourvon"),
        p.getString("password", ""));

    loadData();
  }

  private void loadData() {
    new Thread(() -> {
      try {
        List<ApiModels.ProviderInfo> providers = client.listProviders();
        runOnUiThread(() -> {
          list.setAdapter(new RecyclerView.Adapter<Holder>() {
            @NonNull @Override
            public Holder onCreateViewHolder(@NonNull ViewGroup parent, int i) {
              return new Holder(
                  LayoutInflater.from(parent.getContext())
                      .inflate(R.layout.item_provider, parent, false));
            }
            @Override
            public void onBindViewHolder(@NonNull Holder h, int pos) {
              ApiModels.ProviderInfo p = providers.get(pos);
              h.name.setText(p.name != null ? p.name : p.id);
              h.status.setText("Status: " + (p.status != null ? p.status : "unknown"));
              h.models.setText(p.models != null ? String.join(", ", p.models) : "No models listed");
            }
            @Override
            public int getItemCount() { return providers.size(); }
          });
        });
      } catch (Exception e) {
        runOnUiThread(() ->
            Snackbar.make(list, "Error: " + e.getMessage(), Snackbar.LENGTH_LONG).show());
      }
    }).start();
  }

  static class Holder extends RecyclerView.ViewHolder {
    TextView name, status, models;
    Holder(@NonNull View v) {
      super(v);
      name = v.findViewById(R.id.providerName);
      status = v.findViewById(R.id.providerStatus);
      models = v.findViewById(R.id.providerModels);
    }
  }
}
