package com.wife.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.wife.app.databinding.ActivityChatBinding;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ChatActivity extends AppCompatActivity implements ChatManager.MessageListener, FileReceiver.FileReceiveListener {

    private static final String TAG = "ChatActivity";

    private ActivityChatBinding binding;
    private ChatAdapter adapter;
    private final List<MessageEntity> messagesList = new ArrayList<>();
    private RoomDatabaseManager db;
    private String selfId;

    // Symmetrical cache variable to resolve Glitch 2: Holds the active peer hardware signature ID
    private String peerDeviceId;

    // Multimedia Pickers & Capture launch handlers
    private Uri tempCameraUri;

    private final ActivityResultLauncher<String> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    // Dynamically resolve MIME type to prevent classification conflicts (e.g. image sent as doc)
                    String mimeType = getContentResolver().getType(uri);
                    String typePrefix = "[FILE]";
                    if (mimeType != null) {
                        if (mimeType.startsWith("image/")) {
                            typePrefix = "[IMAGE]";
                        } else if (mimeType.startsWith("video/")) {
                            typePrefix = "[VIDEO]";
                        } else if (mimeType.startsWith("audio/")) {
                            typePrefix = "[AUDIO]";
                        }
                    }
                    sendAttachment(uri, typePrefix);
                }
            }
    );

    private final ActivityResultLauncher<Uri> takePictureLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            success -> {
                if (success && tempCameraUri != null) {
                    sendAttachment(tempCameraUri, "[IMAGE]");
                }
            }
    );

    private final ActivityResultLauncher<Uri> captureVideoLauncher = registerForActivityResult(
            new ActivityResultContracts.CaptureVideo(),
            success -> {
                if (success && tempCameraUri != null) {
                    sendAttachment(tempCameraUri, "[VIDEO]");
                }
            }
    );

    // Audio Voice Note Recording Variables
    private File voiceNoteFile;
    private boolean isRecordingVoice = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WifeLogger.log(TAG, "onCreate() invoked. Constructing Chat Session UI components.");

        db = RoomDatabaseManager.getInstance(this);
        selfId = Utils.getDeviceId(this);
        WifeLogger.log(TAG, "Local Hardware Signature ID resolved: " + selfId);

        // Retrieve the passed peer hardware device ID extra to resolve Glitch 2
        peerDeviceId = getIntent().getStringExtra(Constants.EXTRA_PEER_MAC);
        if (peerDeviceId == null || peerDeviceId.isEmpty()) {
            peerDeviceId = "peer_device";
        }
        WifeLogger.log(TAG, "Chat Session peer Device ID resolved: " + peerDeviceId);

        setupToolbar();
        setupRecyclerView();
        setupInputBarListeners();
        setupEmojiPanel();

        // Automatically restore previous chat backups for this peer if present in public folder
        BackupManager.restoreChat(this, peerDeviceId, selfId);

        loadHistory();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarChat);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbarChat.setNavigationOnClickListener(v -> {
            WifeLogger.log(TAG, "Navigation back button clicked. Exiting Chat Session.");
            onBackPressed();
        });
    }

    private void setupRecyclerView() {
        WifeLogger.log(TAG, "Initializing ChatAdapter and binding LayoutManager to RecyclerView.");
        adapter = new ChatAdapter(this, messagesList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Always align chats from the bottom up like modern chats
        binding.rvChatHistory.setLayoutManager(layoutManager);
        binding.rvChatHistory.setAdapter(adapter);
    }

    private void setupInputBarListeners() {
        WifeLogger.log(TAG, "Registering TextWatcher and click listeners for the WhatsApp-style input bar.");

        // Monitor typing states dynamically
        binding.etChatMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String text = s.toString().trim();
                if (text.isEmpty()) {
                    // Default state: Camera icon visible, Microphone icon active
                    binding.btnCameraIcon.setVisibility(View.VISIBLE);
                    binding.ivSendVoiceIcon.setImageResource(R.drawable.mic_24px);
                } else {
                    // Typing state: Camera icon hidden (attachment slides right), Send icon active
                    binding.btnCameraIcon.setVisibility(View.GONE);
                    binding.ivSendVoiceIcon.setImageResource(R.drawable.send_24px);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Hide emoji panel if the user taps the text input area to type
        binding.etChatMessage.setOnClickListener(v -> {
            if (binding.layoutEmojiPanel.getVisibility() == View.VISIBLE) {
                WifeLogger.log(TAG, "Hiding emoji panel to make room for system soft keyboard.");
                binding.layoutEmojiPanel.setVisibility(View.GONE);
            }
        });

        // Click handler for the morphing mic / send button
        binding.cardSendVoiceContainer.setOnClickListener(v -> {
            String text = binding.etChatMessage.getText().toString().trim();
            if (!TextUtils.isEmpty(text)) {
                WifeLogger.log(TAG, "User triggered SEND message action. Outgoing text length: " + text.length());
                MessageSender.getInstance(this).sendMessage(text);
                binding.etChatMessage.setText("");
            } else {
                WifeLogger.log(TAG, "User triggered offline voice recording action.");
                toggleVoiceRecording();
            }
        });

        // File attachment button picker integration
        binding.btnAttachFile.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User tapped attachment paperclip button. Launching file manager picker.");
            filePickerLauncher.launch("*/*");
        });

        // Camera button option menu integration
        binding.btnCameraIcon.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User tapped camera button. Launching photo/video options dialog.");
            showCameraOptions();
        });
    }

    private void showCameraOptions() {
        String[] options = {"Take Photo", "Record Video"};
        new AlertDialog.Builder(this)
                .setTitle("Camera Options")
                .setItems(options, (dialog, which) -> {
                    try {
                        File tempFile = createTempMediaFile(which == 0 ? ".jpg" : ".mp4");
                        tempCameraUri = FileProvider.getUriForFile(
                                this,
                                getApplicationContext().getPackageName() + ".fileprovider",
                                tempFile
                        );
                        if (which == 0) {
                            takePictureLauncher.launch(tempCameraUri);
                        } else {
                            captureVideoLauncher.launch(tempCameraUri);
                        }
                    } catch (Exception e) {
                        WifeLogger.log(TAG, "Error launching camera: " + e.getMessage(), e);
                        Toast.makeText(this, "Failed to launch camera.", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
        }

        private File createTempMediaFile(String suffix) throws IOException {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "JPEG_" + timeStamp + "_";
            File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (storageDir == null) {
                storageDir = getCacheDir();
            }
            return File.createTempFile(fileName, suffix, storageDir);
        }

        private void toggleVoiceRecording() {
            if (!isRecordingVoice) {
                startVoiceRecording();
            } else {
                stopVoiceRecording();
            }
        }

        private void startVoiceRecording() {
            try {
                voiceNoteFile = File.createTempFile("voice_note_", ".wav", getCacheDir());
                
                // Standardize on high-fidelity WAV capture and wait for completion in background loop callback
                AudioCaptureManager.getInstance(this).startFileRecording(voiceNoteFile, new AudioCaptureManager.OnRecordingCompleteListener() {
                    @Override
                    public void onRecordingComplete(File outputFile) {
                        long size = outputFile.length();
                        WifeLogger.log(TAG, "Recording complete callback fired from capture manager thread. Verified file size: " + size + " bytes");
                        if (outputFile.exists() && size > 0) {
                            sendAttachment(Uri.fromFile(outputFile), "[AUDIO]");
                        } else {
                            WifeLogger.log(TAG, "WAV capture completed but file is empty or was deleted.");
                            Toast.makeText(ChatActivity.this, "Audio recording failed. Captured file is empty.", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onRecordingFailed(String error) {
                        WifeLogger.log(TAG, "WAV recording failed with error context: " + error);
                        Toast.makeText(ChatActivity.this, "Audio recording failed: " + error, Toast.LENGTH_SHORT).show();
                    }
                });

                isRecordingVoice = true;
                binding.ivSendVoiceIcon.setImageResource(android.R.drawable.ic_media_pause);
                binding.etChatMessage.setHint("Recording voice note...");
                binding.etChatMessage.setEnabled(false);
                Toast.makeText(this, "Recording voice note...", Toast.LENGTH_SHORT).show();
                WifeLogger.log(TAG, "Voice note recording started: " + voiceNoteFile.getAbsolutePath());
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed starting voice note recording: " + e.getMessage(), e);
                Toast.makeText(this, "Microphone recording failed.", Toast.LENGTH_SHORT).show();
            }
        }

        private void stopVoiceRecording() {
            // Stop capture cleanly, which safely halts and blocks the background thread to write headers
            AudioCaptureManager.getInstance(this).stopCapture();
            isRecordingVoice = false;
            binding.ivSendVoiceIcon.setImageResource(R.drawable.mic_24px);
            binding.etChatMessage.setHint("Message");
            binding.etChatMessage.setEnabled(true);
            // Dispatching sendAttachment is now completely delegated to onRecordingComplete callback
        }

        private void sendAttachment(Uri uri, String typePrefix) {
            String filename = "Attachment";
            long size = 0;

            if (uri != null) {
                String scheme = uri.getScheme();
                if ("file".equalsIgnoreCase(scheme) || scheme == null) {
                    // Safely query absolute files directly avoiding content database restrictions
                    try {
                        File file = new File(uri.getPath());
                        if (file.exists()) {
                            filename = file.getName();
                            size = file.length();
                            WifeLogger.log(TAG, "Resolved local file URI directly. Filename: " + filename + " | Size: " + size + " bytes");
                        } else {
                            WifeLogger.log(TAG, "Target local file was not discovered at path: " + uri.getPath());
                        }
                    } catch (Exception e) {
                        WifeLogger.log(TAG, "Error resolving direct local file path length: " + e.getMessage(), e);
                    }
                } else {
                    // Fall back to cursor query for standard content:// provider URIs
                    try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                            if (nameIdx != -1) filename = cursor.getString(nameIdx);
                            if (sizeIdx != -1) size = cursor.getLong(sizeIdx);
                            WifeLogger.log(TAG, "Resolved provider content URI via cursor query. Filename: " + filename + " | Size: " + size + " bytes");
                        }
                    } catch (Exception e) {
                        WifeLogger.log(TAG, "Error resolving attachment uri database metadata: " + e.getMessage());
                    }
                }
            }

            if (filename == null || filename.equals("Attachment")) {
                filename = uri.getLastPathSegment();
            }
            if (filename == null) {
                filename = "file_" + System.currentTimeMillis();
            }

            final String finalFilename = filename;
            final long finalSize = size;

            // Copy file to local public directory so that ChatAdapter can resolve it immediately for rendering on sender side
            try {
                File targetDir = getTargetDirectoryForAttachment(finalFilename);
                File destFile = new File(targetDir, finalFilename);
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
                WifeLogger.log(TAG, "Copied sent attachment locally to allow immediate rendering: " + destFile.getAbsolutePath());
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed copying sent attachment locally: " + e.getMessage());
            }

            // 1. Asynchronously transfer raw binary data across custom file socket channel on port 8900
            FileSender.getInstance(this).sendFile(uri, finalFilename, finalSize, new FileSender.FileTransferListener() {
                @Override
                public void onProgress(int percent) {
                    // Background streaming progress tracked silently
                }

                @Override
                public void onComplete(String path) {
                    WifeLogger.log(TAG, "Attachment payload bytes streamed successfully to receiver: " + path);
                    // Force a UI refresh on sender side when sending completes
                    runOnUiThread(() -> {
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    WifeLogger.log(TAG, "Attachment payload bytes stream failed: " + error);
                }
            });

            // 2. Submit formatted signaling layout payload message to update local + remote adapters
            String attachmentPayload = typePrefix + ":" + finalFilename + "|" + finalSize + "|" + uri.toString();
            MessageSender.getInstance(this).sendMessage(attachmentPayload);
        }

        private File getTargetDirectoryForAttachment(String filename) {
            File rootDir;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                rootDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "wife shared");
            } else {
                rootDir = new File(Environment.getExternalStorageDirectory(), "wife shared");
            }

            String ext = "";
            int idx = filename.lastIndexOf('.');
            if (idx > 0) {
                ext = filename.substring(idx + 1).toLowerCase(Locale.US);
            }

            String subFolder;
            switch (ext) {
                case "mp3":
                case "emv":
                case "wav":
                case "ogg":
                case "m4a":
                case "aac":
                    subFolder = "music";
                    break;
                case "jpg":
                case "jpeg":
                case "png":
                case "gif":
                case "bmp":
                    subFolder = "images";
                    break;
                case "mp4":
                case "mkv":
                case "avi":
                case "mov":
                case "3gp":
                case "webm":
                    subFolder = "videos";
                    break;
                case "pdf":
                case "txt":
                case "doc":
                case "docx":
                case "xls":
                case "xlsx":
                case "ppt":
                case "pptx":
                    subFolder = "document";
                    break;
                default:
                    subFolder = "misc";
                    break;
            }

            File targetDir = new File(rootDir, subFolder);
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            return targetDir;
        }

        private void setupEmojiPanel() {
            WifeLogger.log(TAG, "Initializing local offline emoji selection panel.");

            // Toggle emoji panel visibility when clicking the sticker button
            binding.btnEmojiSticker.setOnClickListener(v -> {
                if (binding.layoutEmojiPanel.getVisibility() == View.VISIBLE) {
                    binding.layoutEmojiPanel.setVisibility(View.GONE);
                    WifeLogger.log(TAG, "Dismissing bottom emoji panel.");
                } else {
                    hideKeyboard();
                    binding.layoutEmojiPanel.setVisibility(View.VISIBLE);
                    WifeLogger.log(TAG, "Displaying bottom emoji panel.");
                }
            });

            // Asynchronously load the local raw JSON emoji database
            new Thread(() -> {
                try {
                    Map<String, List<EmojiLoader.EmojiDTO>> emojiMap = EmojiLoader.loadEmojisFromAssets(this);
                    if (emojiMap != null && !emojiMap.isEmpty()) {
                        runOnUiThread(() -> {
                            WifeLogger.log(TAG, "Local emoji database parsed successfully. Binding categories.");
                            setupEmojiViews(emojiMap);
                        });
                    }
                } catch (Exception e) {
                    WifeLogger.log(TAG, "Failed to load local offline emoji database: " + e.getMessage(), e);
                }
            }).start();
        }

        private void setupEmojiViews(Map<String, List<EmojiLoader.EmojiDTO>> emojiMap) {
            List<String> categories = new ArrayList<>(emojiMap.keySet());
            
            // Fixed: Instantiated the child subclass EmojiCategoryAdapter directly to prevent compile mismatch
            EmojiCategoryAdapter categoryAdapter = new EmojiCategoryAdapter(categories, category -> {
                List<EmojiLoader.EmojiDTO> emojis = emojiMap.get(category);
                if (emojis != null) {
                    setupEmojiGrid(emojis);
                }
            });
            binding.rvEmojiCategories.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            binding.rvEmojiCategories.setAdapter(categoryAdapter);

            // Load first category by default on boot
            if (!categories.isEmpty()) {
                List<EmojiLoader.EmojiDTO> defaultEmojis = emojiMap.get(categories.get(0));
                if (defaultEmojis != null) {
                    setupEmojiGrid(defaultEmojis);
                }
            }
        }

        private void setupEmojiGrid(List<EmojiLoader.EmojiDTO> emojis) {
            // Fixed: Instantiated the child subclass EmojiGridAdapter directly to prevent compile mismatch
            EmojiGridAdapter gridAdapter = new EmojiGridAdapter(emojis, emojiChar -> {
                // Insert clicked emoji character directly inside our text field cursor position
                int start = Math.max(binding.etChatMessage.getSelectionStart(), 0);
                int end = Math.max(binding.etChatMessage.getSelectionEnd(), 0);
                binding.etChatMessage.getText().replace(Math.min(start, end), Math.max(start, end),
                        emojiChar, 0, emojiChar.length());
            });
            binding.rvEmojiGrid.setLayoutManager(new GridLayoutManager(this, 7)); // Clean 7 column grid
            binding.rvEmojiGrid.setAdapter(gridAdapter);
        }

        private void hideKeyboard() {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        }

        private void loadHistory() {
            WifeLogger.log(TAG, "Accessing local SQLite database to load chat logs for peer: " + peerDeviceId);
            try {
                // Symmetrical segmented lookup: Query database specifically for the active peer relationship
                List<MessageEntity> history = db.messageDao().getChatHistory(peerDeviceId, selfId);
                messagesList.clear();
                
                WifeLogger.log("ChatActivity", "Successfully retrieved " + history.size() + " messages from Room database.");
                
                // Populating in chronological order (Room DAO returns chronological getChatHistory by default)
                for (MessageEntity msg : history) {
                    messagesList.add(msg);
                }
                
                adapter.notifyDataSetChanged();
                scrollToBottom();
                WifeLogger.log("ChatActivity", "Dataset loaded and adapter notifications dispatched.");
            } catch (Exception e) {
                WifeLogger.log("ChatActivity", "Failed to query local database chat history: " + e.getMessage(), e);
            }
        }

        private void scrollToBottom() {
            if (!messagesList.isEmpty()) {
                WifeLogger.log("ChatActivity", "Scrolling list view focus to position index: " + (messagesList.size() - 1));
                binding.rvChatHistory.smoothScrollToPosition(messagesList.size() - 1);
            }
        }

        @Override
        protected void onResume() {
            super.onResume();
            WifeLogger.log("ChatActivity", "onResume() invoked. Registering ChatActivity observer to ChatManager listener list.");
            ChatManager.getInstance(this).registerMessageListener(this);
            FileReceiver.registerListener(this);

            // Clear unread notification counts and save state to clear the unread badge
            try {
                SharedPreferences prefs = getSharedPreferences("WifeSettings", MODE_PRIVATE);
                prefs.edit().putInt("unread_count", 0).apply();
                WifeLogger.log("ChatActivity", "Unread chat message count reset to 0 inside local preferences.");
            } catch (Exception e) {
                WifeLogger.log("ChatActivity", "Error resetting unread message counts: " + e.getMessage(), e);
            }
        }

        @Override
        protected void onPause() {
            super.onPause();
            WifeLogger.log("ChatActivity", "onPause() invoked. Unregistering ChatActivity observer from ChatManager listener list.");
            ChatManager.getInstance(this).unregisterMessageListener(this);
            FileReceiver.unregisterListener(this);

            // Automatically backup active session logs to persistent shared public storage
            BackupManager.backupChat(this, peerDeviceId, selfId);
        }

        @Override
        public void onMessageReceived(MessageEntity message) {
            WifeLogger.log("ChatActivity", "onMessageReceived callback triggered on ChatActivity. From: " + message.getSender() + " | Text: " + message.getText());
            
            // Verify if the incoming message corresponds strictly to the active peer session (resolve Glitch 2)
            boolean isFromActivePeer = message.getSender().equals(peerDeviceId);
            boolean isFromSelf = message.getSender().equals(selfId) && message.getReceiver().equals(peerDeviceId);

            if (isFromActivePeer || isFromSelf) {
                runOnUiThread(() -> {
                    try {
                        messagesList.add(message);
                        adapter.notifyDataSetChanged();
                        scrollToBottom();
                        WifeLogger.log("ChatActivity", "Real-time list update redrawn. Current list size: " + messagesList.size());
                    } catch (Exception e) {
                        WifeLogger.log("ChatActivity", "Error rendering real-time message bubble update: " + e.getMessage(), e);
                    }
                });
            } else {
                WifeLogger.log("ChatActivity", "Suppressed real-time message bubble insertion. Message belongs to a different background session.");
            }
        }

        @Override
        public void onMessageUnsent(long targetTimestamp) {
            WifeLogger.log(TAG, "onMessageUnsent callback triggered. Target timestamp: " + targetTimestamp);
            runOnUiThread(() -> {
                try {
                    boolean removed = false;
                    for (int i = 0; i < messagesList.size(); i++) {
                        if (messagesList.get(i).getTimestamp() == targetTimestamp) {
                            messagesList.remove(i);
                            removed = true;
                            WifeLogger.log(TAG, "Successfully removed unsent message from active list dataset in real-time.");
                            break;
                        }
                    }
                    if (removed) {
                        adapter.notifyDataSetChanged();
                        scrollToBottom();

                        // Rewrite the local backup file immediately to remove this unsent message (and resolve the unsend recovery glitch)
                        BackupManager.backupChat(this, peerDeviceId, selfId);
                        WifeLogger.log(TAG, "Local JSON backup overwritten successfully after active unsend event.");
                    }
                } catch (Exception e) {
                    WifeLogger.log(TAG, "Error executing real-time unsend UI refresh: " + e.getMessage(), e);
                }
            });
        }

        // --- FileReceiveListener Callbacks to resolve Glitch 1 ---
        @Override
        public void onProgress(String filename, int percent) {
            // Background receiver progress ignored for silent chat flow
        }

        @Override
        public void onComplete(String filename, String localPath) {
            WifeLogger.log(TAG, "onComplete received for file: " + filename + " at path: " + localPath + ". Refreshing chat UI for instant thumbnail.");
            runOnUiThread(() -> {
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        }

        @Override
        public void onError(String error) {
            // Optional receiver error handling
        }
}