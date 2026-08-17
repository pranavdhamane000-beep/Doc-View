package com.docviewer;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST = 100;
    private RecyclerView recentList;
    private RecentFilesAdapter adapter;
    private SharedPreferences prefs;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("docviewer_prefs", MODE_PRIVATE);

        // Handle "Open With" intent from other apps
        handleViewIntent(getIntent());

        recentList = findViewById(R.id.recentList);
        recentList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecentFilesAdapter();
        recentList.setAdapter(adapter);

        Button btnOpen = findViewById(R.id.btnOpenFile);
        Button btnBrowse = findViewById(R.id.browse);

        // File picker launcher
        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) openFile(uri);
                }
            }
        );

        btnOpen.setOnClickListener(v -> {
            openFilePicker();
        });

        btnBrowse.setOnClickListener(v -> {
            openFilePicker();
        });

        checkPermission();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleViewIntent(intent);
    }

    private void handleViewIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri uri = intent.getData();
            openFile(uri);
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimeTypes = {
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/msword",
            "application/vnd.ms-powerpoint"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(Intent.createChooser(intent, "Select Document"));
    }

    private void openFile(Uri uri) {
        String fileName = getFileName(uri);
        String filePath = uri.toString();

        // Save to recent files
        Set<String> recent = prefs.getStringSet("recent_files", new HashSet<>());
        Set<String> updated = new HashSet<>(recent);
        updated.remove(filePath); // Remove if exists (to re-add at top)
        updated.add(filePath);
        // Keep only last 20 files
        List<String> list = new ArrayList<>(updated);
        if (list.size() > 20) {
            list = list.subList(list.size() - 20, list.size());
        }
        prefs.edit().putStringSet("recent_files", new HashSet<>(list)).apply();

        // Save filename mapping
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("filename_" + filePath, fileName);
        editor.apply();

        // Open viewer
        Intent intent = new Intent(this, ViewerActivity.class);
        intent.setData(uri);
        startActivity(intent);
    }

    private String getFileName(Uri uri) {
        String fileName = "Unknown";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex);
            }
        } catch (Exception e) {
            fileName = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "Unknown";
        }
        return fileName;
    }

    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    PERMISSION_REQUEST);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- Recent Files Adapter ---
    private class RecentFilesAdapter extends RecyclerView.Adapter<RecentFilesAdapter.ViewHolder> {
        private final List<String[]> files = new ArrayList<>();

        RecentFilesAdapter() {
            loadRecentFiles();
        }

        private void loadRecentFiles() {
            files.clear();
            Set<String> recent = prefs.getStringSet("recent_files", new HashSet<>());
            List<String> list = new ArrayList<>(recent);
            Collections.reverse(list); // Most recent first
            for (String path : list) {
                String name = prefs.getString("filename_" + path, path);
                files.add(new String[]{path, name});
            }
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_1, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            String[] file = files.get(position);
            holder.text.setText(file[1]);
            holder.itemView.setOnClickListener(v -> {
                Uri uri = Uri.parse(file[0]);
                openFile(uri);
            });
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView text;
            ViewHolder(View view) {
                super(view);
                text = view.findViewById(android.R.id.text1);
            }
        }
    }
}
