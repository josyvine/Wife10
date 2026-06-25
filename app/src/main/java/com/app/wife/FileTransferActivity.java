package com.wife.app;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher; 
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.wife.app.databinding.ActivityFileTransferBinding;

import java.util.ArrayList;
import java.util.List;

public class FileTransferActivity extends AppCompatActivity implements 
        FileSender.FileTransferListener, 
        FileReceiver.FileReceiveListener,
        FileAdapter.OnFileDeleteListener {

    private static final String TAG = "FileTransferActivity";

    private ActivityFileTransferBinding binding;
    private FileAdapter adapter;
    private final List<FileEntity> historyList = new ArrayList<>();
    private RoomDatabaseManager db;

    // Upgraded contract to select multiple files at once (Glitch 3 Fix)
    private final ActivityResultLauncher<String> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetMultipleContents(),
            this::onFilesSelected
    );

    // --- High-Speed Real-time Broadcast Receiver ---
    private final BroadcastReceiver transferReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case Constants.ACTION_TRANSFER_PROGRESS:
                    String filename = intent.getStringExtra(Constants.EXTRA_FILE_NAME);
                    long transferred = intent.getLongExtra(Constants.EXTRA_BYTES_TRANSFERRED, 0);
                    long total = intent.getLongExtra(Constants.EXTRA_TOTAL_BYTES, 0);
                    int percent = (total > 0) ? (int) ((transferred * 100) / total) : 0;
                    double speed = intent.getDoubleExtra(Constants.EXTRA_TRANSFER_SPEED, 0.0);

                    binding.layoutTransferProgress.setVisibility(View.VISIBLE);
                    
                    if (FileTransferForegroundService.isPaused) {
                        binding.tvActiveFileName.setText("Paused: " + filename);
                    } else {
                        binding.tvActiveFileName.setText("Processing: " + filename);
                    }
                    
                    binding.pbTransferPercentage.setProgress(percent);
                    binding.tvTransferPercentText.setText(percent + "%");

                    // Format raw byte counts and speed to human-readable strings
                    String transferredStr = Utils.formatFileSize(transferred);
                    String totalStr = Utils.formatFileSize(total);
                    
                    // Symmetrical visual formatting during local preparation/compression phase (Glitch 1 Fix)
                    if (filename != null && filename.startsWith("Compressing:")) {
                        binding.tvActiveFileName.setText(filename);
                        binding.tvTransferSpeedAndSize.setText(transferredStr + " / " + totalStr + " (Compressing...)");
                    } else {
                        String speedStr = String.format(java.util.Locale.US, "%.1f MB/s", speed);
                        binding.tvTransferSpeedAndSize.setText(transferredStr + " / " + totalStr + " (" + speedStr + ")");
                    }
                    break;

                case Constants.ACTION_TRANSFER_COMPLETE:
                    Toast.makeText(FileTransferActivity.this, "Transfer completed successfully!", Toast.LENGTH_SHORT).show();
                    binding.layoutTransferProgress.setVisibility(View.GONE);
                    loadHistory();
                    
                    // Safety cleanup block to resolve Glitch 3
                    stopService(new Intent(FileTransferActivity.this, FileTransferForegroundService.class));
                    break;

                case Constants.ACTION_TRANSFER_ERROR:
                    String error = intent.getStringExtra(Constants.EXTRA_ERROR_MESSAGE);
                    // Symmetrical check to suppress error UI on manual cancellation (Glitch 2 Fix)
                    if ("Transfer cancelled by user.".equals(error)) {
                        Toast.makeText(FileTransferActivity.this, "Transfer cancelled.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(FileTransferActivity.this, "Transfer failed: " + error, Toast.LENGTH_SHORT).show();
                    }
                    binding.layoutTransferProgress.setVisibility(View.GONE);
                    loadHistory();
                    
                    // Safety cleanup block to resolve Glitch 3
                    stopService(new Intent(FileTransferActivity.this, FileTransferForegroundService.class));
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFileTransferBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = RoomDatabaseManager.getInstance(this);

        setupToolbar();
        setupRecyclerView();

        binding.btnPickFile.setOnClickListener(v -> {
            filePickerLauncher.launch("*/*"); // Let user pick any file type
        });

        // Interactive transfer dialog control hook
        binding.layoutTransferProgress.setOnClickListener(v -> showTransferOptionsDialog());

        loadHistory();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarFileTransfer);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbarFileTransfer.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void setupRecyclerView() {
        adapter = new FileAdapter(historyList, this);
        binding.rvFileHistory.setLayoutManager(new LinearLayoutManager(this));
        binding.rvFileHistory.setAdapter(adapter);
    }

    private void loadHistory() {
        List<FileEntity> logs = db.fileDao().getAllFiles();
        historyList.clear();
        historyList.addAll(logs);
        adapter.notifyDataSetChanged();
    }

    // Handles multiple selection and packages files into a background service queue (Glitch 3 Fix)
    private void onFilesSelected(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;

        ArrayList<String> uriStrings = new ArrayList<>();
        ArrayList<String> fileNames = new ArrayList<>();
        long[] fileSizes = new long[uris.size()];

        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);
            uriStrings.add(uri.toString());

            String filename = "Unknown_File_" + i;
            long size = 0;

            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                    
                    if (nameIdx != -1) filename = cursor.getString(nameIdx);
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            fileNames.add(filename);
            fileSizes[i] = size;
        }

        binding.layoutTransferProgress.setVisibility(View.VISIBLE);
        if (uris.size() == 1) {
            binding.tvActiveFileName.setText("Uploading: " + fileNames.get(0));
        } else {
            binding.tvActiveFileName.setText("Uploading " + uris.size() + " files...");
        }
        binding.pbTransferPercentage.setProgress(0);
        binding.tvTransferPercentText.setText("0%");
        binding.tvTransferSpeedAndSize.setText(""); // Clear previous stats on new transaction

        String peerIp = ConnectionManager.getInstance(this).getPeerIpAddress();
        if (peerIp == null || peerIp.isEmpty()) {
            Toast.makeText(this, "No connected peer available.", Toast.LENGTH_SHORT).show();
            binding.layoutTransferProgress.setVisibility(View.GONE);
            return;
        }

        // Directly kick off queue transfer via foreground service
        Intent serviceIntent = new Intent(this, FileTransferForegroundService.class);
        serviceIntent.setAction(Constants.ACTION_START_TRANSFER);
        serviceIntent.putExtra("IS_SENDER", true);
        serviceIntent.putStringArrayListExtra("URI_LIST", uriStrings);
        serviceIntent.putStringArrayListExtra("FILE_NAMES", fileNames);
        serviceIntent.putExtra("FILE_SIZES", fileSizes);
        serviceIntent.putExtra("PEER_IP", peerIp);
        startService(serviceIntent);
    }

    // Deprecated for the new high-performance batch selected queue handler
    private void onFileSelected(Uri uri) {
        // Maintained for interface signatures compatibility
    }

    /**
     * Shows a popup dialog allowing the user to Pause, Resume, or Cancel 
     * the background task without modifying your layout XML coordinates.
     */
    private void showTransferOptionsDialog() {
        String[] options = FileTransferForegroundService.isPaused ? 
                new String[]{"Resume Transfer", "Cancel Transfer"} : 
                new String[]{"Pause Transfer", "Cancel Transfer"};

        new AlertDialog.Builder(this)
                .setTitle("Transfer Controls")
                .setItems(options, (dialog, which) -> {
                    Intent intent = new Intent(this, FileTransferForegroundService.class);
                    if (which == 0) {
                        if (FileTransferForegroundService.isPaused) {
                            intent.setAction(Constants.ACTION_RESUME_TRANSFER);
                            Toast.makeText(this, "Resuming...", Toast.LENGTH_SHORT).show();
                        } else {
                            intent.setAction(Constants.ACTION_PAUSE_TRANSFER);
                            Toast.makeText(this, "Pausing...", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        intent.setAction(Constants.ACTION_CANCEL_TRANSFER);
                        Toast.makeText(this, "Cancelling...", Toast.LENGTH_SHORT).show();
                    }
                    startService(intent);
                })
                .show();
    }

    // --- Outgoing Callbacks (FileSender.FileTransferListener) ---

    @Override
    public void onProgress(int percent) {
        // Maintained for backward compatibility
    }

    @Override
    public void onComplete(String path) {
        // Maintained for backward compatibility
    }

    @Override
    public void onError(String error) {
        // Maintained for backward compatibility
    }

    // --- Incoming Callbacks (FileReceiver.FileReceiveListener) ---

    @Override
    public void onProgress(String filename, int percent) {
        // Maintained for backward compatibility
    }

    @Override
    public void onComplete(String filename, String localPath) {
        // Maintained for backward compatibility
    }

    // Callback invoked when delete button is tapped inside RecyclerView row item
    @Override
    public void onFileDelete(FileEntity file, int position) {
        WifeLogger.log("FileTransferActivity", "User requested deletion of file log entity: " + file.getFilename() + " at index: " + position);
        new Thread(() -> {
            try {
                // 1. Delete entity record from SQLite database using the DAO
                db.fileDao().deleteById(file.getId());
                WifeLogger.log("FileTransferActivity", "Successfully deleted file transfer entry from Room Database.");

                // 2. Refresh active list elements on Main UI Thread safely
                runOnUiThread(() -> {
                    try {
                        if (position < historyList.size()) {
                            historyList.remove(position);
                            adapter.notifyItemRemoved(position);
                            adapter.notifyItemRangeChanged(position, historyList.size());
                            Toast.makeText(this, "File transfer entry removed.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        WifeLogger.log("FileTransferActivity", "Failed updating active adapters during UI update: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                WifeLogger.log("FileTransferActivity", "Error executing file transfer log deletion background thread: " + e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FileReceiver.registerListener(this);

        // Register local broadcast receiver for high-speed updates
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION_TRANSFER_PROGRESS);
        filter.addAction(Constants.ACTION_TRANSFER_COMPLETE);
        filter.addAction(Constants.ACTION_TRANSFER_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(transferReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        FileReceiver.unregisterListener(this);

        // Unregister local broadcast receiver cleanly
        LocalBroadcastManager.getInstance(this).unregisterReceiver(transferReceiver);
    }
}