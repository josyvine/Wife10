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
import android.widget.ProgressBar;
import android.widget.TextView;
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
                    
                    boolean isChunk = intent.getBooleanExtra("IS_CHUNK", false);
                    if (isChunk) {
                        int chunkIndex = intent.getIntExtra("CHUNK_INDEX", 0);
                        long chunkTransferred = intent.getLongExtra("CHUNK_BYTES_TRANSFERRED", 0);
                        long chunkTotal = intent.getLongExtra("CHUNK_TOTAL_BYTES", 0);

                        int chunkPercent = (chunkTotal > 0) ? (int) ((chunkTransferred * 100) / chunkTotal) : 0;

                        // Symmetrical Cleanup logic: If chunk reaches completion, purge its row to prevent UI bloating
                        if (chunkPercent >= 100) {
                            View chunkRow = binding.containerActiveChunks.findViewWithTag("chunk_" + chunkIndex);
                            if (chunkRow != null) {
                                binding.containerActiveChunks.removeView(chunkRow);
                            }
                        } else {
                            // Dynamically inflate or retrieve progress row for the specific index
                            View chunkRow = binding.containerActiveChunks.findViewWithTag("chunk_" + chunkIndex);
                            if (chunkRow == null) {
                                chunkRow = getLayoutInflater().inflate(R.layout.item_chunk_progress, binding.containerActiveChunks, false);
                                chunkRow.setTag("chunk_" + chunkIndex);
                                binding.containerActiveChunks.addView(chunkRow);
                            }

                            TextView tvChunkLabel = chunkRow.findViewById(R.id.tvChunkLabel);
                            ProgressBar pbChunkPercentage = chunkRow.findViewById(R.id.pbChunkPercentage);
                            TextView tvChunkSpeedAndSize = chunkRow.findViewById(R.id.tvChunkSpeedAndSize);
                            TextView tvChunkPercentText = chunkRow.findViewById(R.id.tvChunkPercentText);

                            pbChunkPercentage.setProgress(chunkPercent);
                            tvChunkPercentText.setText(chunkPercent + "%");

                            String chunkTransferredStr = Utils.formatFileSize(chunkTransferred);
                            String chunkTotalStr = Utils.formatFileSize(chunkTotal);

                            // Visual formatting layout check for compression phase
                            if (filename != null && filename.startsWith("Compressing:")) {
                                tvChunkLabel.setText("Chunk #" + (chunkIndex + 1) + ": Compressing...");
                                tvChunkSpeedAndSize.setText(chunkTransferredStr + " / " + chunkTotalStr + " (Compressing...)");
                            } else {
                                tvChunkLabel.setText("Chunk #" + (chunkIndex + 1) + ": Processing...");
                                String speedStr = String.format(java.util.Locale.US, "%.1f MB/s", speed);
                                tvChunkSpeedAndSize.setText(chunkTransferredStr + " / " + chunkTotalStr + " (" + speedStr + ")");
                            }
                        }

                        // Maintain aggregate progression layout state on parent elements
                        binding.tvActiveFileName.setText("Processing Multiple Chunks...");
                        binding.pbTransferPercentage.setProgress(percent);
                        binding.tvTransferPercentText.setText(percent + "%");
                        String aggregateTransferredStr = Utils.formatFileSize(transferred);
                        String aggregateTotalStr = Utils.formatFileSize(total);
                        String aggregateSpeedStr = String.format(java.util.Locale.US, "%.1f MB/s", speed);
                        binding.tvTransferSpeedAndSize.setText(aggregateTransferredStr + " / " + aggregateTotalStr + " (" + aggregateSpeedStr + ")");

                    } else {
                        // Sequential non-chunk legacy layout configuration
                        binding.containerActiveChunks.removeAllViews(); // Clean up dynamic rows

                        if (FileTransferForegroundService.isPaused) {
                            binding.tvActiveFileName.setText("Paused: " + filename);
                        } else {
                            binding.tvActiveFileName.setText("Processing: " + filename);
                        }
                        
                        binding.pbTransferPercentage.setProgress(percent);
                        binding.tvTransferPercentText.setText(percent + "%");

                        String transferredStr = Utils.formatFileSize(transferred);
                        String totalStr = Utils.formatFileSize(total);
                        
                        if (filename != null && filename.startsWith("Compressing:")) {
                            binding.tvActiveFileName.setText(filename);
                            binding.tvTransferSpeedAndSize.setText(transferredStr + " / " + totalStr + " (Compressing...)");
                        } else {
                            String speedStr = String.format(java.util.Locale.US, "%.1f MB/s", speed);
                            binding.tvTransferSpeedAndSize.setText(transferredStr + " / " + totalStr + " (" + speedStr + ")");
                        }
                    }
                    break;

                case Constants.ACTION_TRANSFER_COMPLETE:
                    Toast.makeText(FileTransferActivity.this, "Transfer completed successfully!", Toast.LENGTH_SHORT).show();
                    binding.layoutTransferProgress.setVisibility(View.GONE);
                    binding.containerActiveChunks.removeAllViews(); // Purge chunk rows
                    loadHistory();
                    
                    stopService(new Intent(FileTransferActivity.this, FileTransferForegroundService.class));
                    break;

                case Constants.ACTION_TRANSFER_ERROR:
                    String error = intent.getStringExtra(Constants.EXTRA_ERROR_MESSAGE);
                    if ("Transfer cancelled by user.".equals(error)) {
                        Toast.makeText(FileTransferActivity.this, "Transfer cancelled.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(FileTransferActivity.this, "Transfer failed: " + error, Toast.LENGTH_SHORT).show();
                    }
                    binding.layoutTransferProgress.setVisibility(View.GONE);
                    binding.containerActiveChunks.removeAllViews(); // Purge chunk rows
                    loadHistory();
                    
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
            filePickerLauncher.launch("*/*"); 
        });

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

    /**
     * Database transaction executed asynchronously off the Main/UI thread (ANR Prevention check)
     */
    private void loadHistory() {
        new Thread(() -> {
            try {
                List<FileEntity> logs = db.fileDao().getAllFiles();
                runOnUiThread(() -> {
                    try {
                        historyList.clear();
                        historyList.addAll(logs);
                        adapter.notifyDataSetChanged();
                    } catch (Exception e) {
                        WifeLogger.log(TAG, "Failed executing adapter updates: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed reading Database contents asynchronously: " + e.getMessage());
            }
        }).start();
    }

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
        binding.containerActiveChunks.removeAllViews(); // Fresh visual state setup

        if (uris.size() == 1) {
            binding.tvActiveFileName.setText("Uploading: " + fileNames.get(0));
        } else {
            binding.tvActiveFileName.setText("Uploading " + uris.size() + " files...");
        }
        binding.pbTransferPercentage.setProgress(0);
        binding.tvTransferPercentText.setText("0%");
        binding.tvTransferSpeedAndSize.setText(""); 

        String peerIp = ConnectionManager.getInstance(this).getPeerIpAddress();
        if (peerIp == null || peerIp.isEmpty()) {
            Toast.makeText(this, "No connected peer available.", Toast.LENGTH_SHORT).show();
            binding.layoutTransferProgress.setVisibility(View.GONE);
            return;
        }

        Intent serviceIntent = new Intent(this, FileTransferForegroundService.class);
        serviceIntent.setAction(Constants.ACTION_START_TRANSFER);
        serviceIntent.putExtra("IS_SENDER", true);
        serviceIntent.putStringArrayListExtra("URI_LIST", uriStrings);
        serviceIntent.putStringArrayListExtra("FILE_NAMES", fileNames);
        serviceIntent.putExtra("FILE_SIZES", fileSizes);
        serviceIntent.putExtra("PEER_IP", peerIp);
        startService(serviceIntent);
    }

    private void onFileSelected(Uri uri) {
        // Compatibility Interface signature compatibility
    }

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

    @Override
    public void onProgress(int percent) {
        // Backward compatibility
    }

    @Override
    public void onComplete(String path) {
        // Backward compatibility
    }

    @Override
    public void onError(String error) {
        // Backward compatibility
    }

    @Override
    public void onProgress(String filename, int percent) {
        // Backward compatibility
    }

    @Override
    public void onComplete(String filename, String localPath) {
        // Backward compatibility
    }

    @Override
    public void onFileDelete(FileEntity file, int position) {
        WifeLogger.log("FileTransferActivity", "User requested deletion of file log entity: " + file.getFilename() + " at index: " + position);
        new Thread(() -> {
            try {
                db.fileDao().deleteById(file.getId());
                WifeLogger.log("FileTransferActivity", "Successfully deleted file transfer entry from Room Database.");

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

        LocalBroadcastManager.getInstance(this).unregisterReceiver(transferReceiver);
    }
}