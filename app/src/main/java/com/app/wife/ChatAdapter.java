package com.wife.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SENT = 1;
    private static final int TYPE_RECEIVED = 2;

    private final List<MessageEntity> messages;
    private final String selfDeviceId;

    // Inline Audio Playback variables
    private MediaPlayer mediaPlayer;
    private String currentlyPlayingPath;
    private TextView currentlyPlayingTextView;

    public ChatAdapter(Context context, List<MessageEntity> messages) {
        this.messages = messages;
        this.selfDeviceId = Utils.getDeviceId(context);
    }

    @Override
    public int getItemViewType(int position) {
        MessageEntity msg = messages.get(position);
        if (msg.getSender().equals(selfDeviceId)) {
            return TYPE_SENT;
        } else {
            return TYPE_RECEIVED;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SENT) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_sent, parent, false);
            return new SentViewHolder(v);
        } else {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_received, parent, false);
            return new ReceivedViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MessageEntity msg = messages.get(position);
        String formattedTime = formatTime(msg.getTimestamp());
        String rawText = msg.getText();

        boolean isAttachment = rawText.startsWith("[FILE]:") || rawText.startsWith("[IMAGE]:") || 
                               rawText.startsWith("[VIDEO]:") || rawText.startsWith("[AUDIO]:");

        String filename = "Attachment";
        String fileSize = "";
        if (isAttachment) {
            try {
                int firstColon = rawText.indexOf(':');
                String payload = rawText.substring(firstColon + 1);
                String[] parts = payload.split("\\|");
                if (parts.length > 0) filename = parts[0];
                if (parts.length > 1) {
                    long bytes = Long.parseLong(parts[1]);
                    fileSize = " (" + Utils.formatFileSize(bytes) + ")";
                }
            } catch (Exception e) {
                WifeLogger.log("ChatAdapter", "Error parsing attachment: " + e.getMessage());
            }
        }

        final String cleanFilename = getCleanFileName(filename);
        final File localFile = getLocalFile(filename);
        
        // Define a combined, effectively final string to resolve lambda compiler scoping restrictions
        final String labelWithFileSize = cleanFilename + fileSize;

        // Dynamic visual override if an image was mistakenly sent with a [FILE] tag
        String ext = "";
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx > 0) {
            ext = filename.substring(dotIdx + 1).toLowerCase(Locale.US);
        }
        boolean isVisualImage = rawText.startsWith("[IMAGE]:") || 
                               (rawText.startsWith("[FILE]:") && (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp") || ext.equals("gif")));
        boolean isVisualVideo = rawText.startsWith("[VIDEO]:");

        if (holder instanceof SentViewHolder) {
            SentViewHolder h = (SentViewHolder) holder;
            if (isAttachment) {
                h.itemView.setOnClickListener(null); // Clear previous item click
                
                // Show standard preview block and clear text
                if (rawText.startsWith("[FILE]:") && !isVisualImage) {
                    h.tvText.setText("📁 Document: " + labelWithFileSize);
                } else if (isVisualImage) {
                    h.tvText.setText("📷 Image: " + labelWithFileSize);
                } else if (isVisualVideo) {
                    h.tvText.setText("🎥 Video: " + labelWithFileSize);
                } else if (rawText.startsWith("[AUDIO]:")) {
                    h.tvText.setText("🎤 Voice Note: " + labelWithFileSize);
                    h.tvText.setTag("🎤 Voice Note: " + labelWithFileSize);
                    h.itemView.setOnClickListener(v -> playAudio(v.getContext(), localFile, h.tvText, labelWithFileSize));
                }

                // Render dynamic memory-safe image/video thumbnail card using native generator
                if (h.ivImage != null) {
                    if ((isVisualImageOrVideo(rawText) || isVisualImageFile(filename)) && localFile.exists()) {
                        h.ivImage.setVisibility(View.VISIBLE);
                        Bitmap thumb = null;
                        if (rawText.startsWith("[VIDEO]:")) {
                            thumb = ThumbnailGenerator.getVideoThumbnail(localFile);
                        } else {
                            thumb = ThumbnailGenerator.getImageThumbnail(localFile, 200, 200);
                        }

                        if (thumb != null) {
                            h.ivImage.setImageBitmap(thumb);
                        } else {
                            h.ivImage.setImageResource(android.R.drawable.ic_menu_gallery);
                        }
                        h.ivImage.setOnClickListener(v -> viewImage(v.getContext(), localFile, finalFilenameOnImageClick(rawText, cleanFilename)));
                    } else {
                        h.ivImage.setVisibility(View.GONE);
                    }
                }
            } else {
                h.tvText.setText(rawText);
                if (h.ivImage != null) {
                    h.ivImage.setVisibility(View.GONE);
                }
            }
            h.tvTime.setText(formattedTime);
        } else if (holder instanceof ReceivedViewHolder) {
            ReceivedViewHolder h = (ReceivedViewHolder) holder;
            if (isAttachment) {
                final String finalFilename = filename;
                h.itemView.setOnClickListener(null); // Clear previous item click
                h.ivSave.setVisibility(View.VISIBLE);
                h.ivSave.setOnClickListener(v -> saveReceivedFileToPublic(v.getContext(), finalFilename));

                if (rawText.startsWith("[FILE]:") && !isVisualImage) {
                    h.tvText.setText("📁 Document: " + labelWithFileSize);
                } else if (isVisualImage) {
                    h.tvText.setText("📷 Image: " + labelWithFileSize);
                } else if (isVisualVideo) {
                    h.tvText.setText("🎥 Video: " + labelWithFileSize);
                } else if (rawText.startsWith("[AUDIO]:")) {
                    h.tvText.setText("🎤 Voice Note: " + labelWithFileSize);
                    h.tvText.setTag("🎤 Voice Note: " + labelWithFileSize);
                    h.itemView.setOnClickListener(v -> playAudio(v.getContext(), localFile, h.tvText, labelWithFileSize));
                }

                // Render dynamic memory-safe image/video thumbnail card using native generator
                if (h.ivImage != null) {
                    if ((isVisualImageOrVideo(rawText) || isVisualImageFile(filename)) && localFile.exists()) {
                        h.ivImage.setVisibility(View.VISIBLE);
                        Bitmap thumb = null;
                        if (rawText.startsWith("[VIDEO]:")) {
                            thumb = ThumbnailGenerator.getVideoThumbnail(localFile);
                        } else {
                            thumb = ThumbnailGenerator.getImageThumbnail(localFile, 200, 200);
                        }

                        if (thumb != null) {
                            h.ivImage.setImageBitmap(thumb);
                        } else {
                            h.ivImage.setImageResource(android.R.drawable.ic_menu_gallery);
                        }
                        h.ivImage.setOnClickListener(v -> viewImage(v.getContext(), localFile, finalFilenameOnImageClick(rawText, cleanFilename)));
                    } else {
                        h.ivImage.setVisibility(View.GONE);
                    }
                }
            } else {
                h.ivSave.setVisibility(View.GONE);
                h.tvText.setText(rawText);
                if (h.ivImage != null) {
                    h.ivImage.setVisibility(View.GONE);
                }
            }
            h.tvTime.setText(formattedTime);
        }

        // Attach long-press gesture listener to the message bubble cells
        holder.itemView.setOnLongClickListener(v -> {
            WifeLogger.log("ChatAdapter", "Long-press captured on message cell at position index: " + position);
            showContextMenu(v, msg, position);
            return true;
        });
    }

    private boolean isVisualImageOrVideo(String rawText) {
        return rawText.startsWith("[IMAGE]:") || rawText.startsWith("[VIDEO]:");
    }

    private boolean isVisualImageFile(String filename) {
        String ext = "";
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx > 0) {
            ext = filename.substring(dotIdx + 1).toLowerCase(Locale.US);
        }
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp") || ext.equals("gif");
    }

    private String finalFilenameOnImageClick(String rawText, String cleanFilename) {
        return cleanFilename;
    }

    private String getCleanFileName(String rawName) {
        if (rawName == null) return "Attachment";
        try {
            // Truncate voice note UUID trail (e.g. voice_note_1130005974066978416.wav -> voice_note_11.wav)
            if (rawName.startsWith("voice_note_")) {
                return rawName.replaceAll("(?<=voice_note_\\d{2})\\d+", "");
            }
            // Truncate camera capture UUID trail (e.g. JPEG_20260618_033645_788281448479624591.jpg -> JPEG_20260618_033645.jpg)
            if (rawName.startsWith("JPEG_") && rawName.length() > 30) {
                int secondUnder = rawName.indexOf('_', 5); // index past "JPEG_"
                int thirdUnder = rawName.indexOf('_', secondUnder + 1);
                if (thirdUnder != -1) {
                    int dotIdx = rawName.lastIndexOf('.');
                    String ext = dotIdx != -1 ? rawName.substring(dotIdx) : "";
                    return rawName.substring(0, thirdUnder) + ext;
                }
            }
            // Truncate WA duplicate markers (e.g. filename(1).jpg -> filename.jpg)
            if (rawName.contains("-WA") || rawName.contains("(")) {
                return rawName.replaceAll("\\(\\d+\\)", "");
            }
        } catch (Exception e) {
            WifeLogger.log("ChatAdapter", "Failed to clean filename string: " + e.getMessage());
        }
        return rawName;
    }

    private File getLocalFile(String filename) {
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
            case "webp":
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

        File rootDir;
        // Synchronized: Must match the public Download/ directory fallback of FileReceiver on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "wife shared");
        } else {
            rootDir = new File(Environment.getExternalStorageDirectory(), "wife shared");
        }

        return new File(new File(rootDir, subFolder), filename);
    }

    private Bitmap getThumbnail(File file, int targetWidth, int targetHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            
            int srcWidth = options.outWidth;
            int srcHeight = options.outHeight;
            int inSampleSize = 1;
            
            if (srcHeight > targetHeight || srcWidth > targetWidth) {
                final int halfHeight = srcHeight / 2;
                final int halfWidth = srcWidth / 2;
                while ((halfHeight / inSampleSize) >= targetHeight && (halfWidth / inSampleSize) >= targetWidth) {
                    inSampleSize *= 2;
                }
            }
            
            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception e) {
            WifeLogger.log("ChatAdapter", "Failed generating layout bitmap preview: " + e.getMessage());
            return null;
        }
    }

    private void playAudio(Context context, File audioFile, TextView statusView, String originalLabel) {
        if (mediaPlayer != null) {
            stopAudio();
            if (currentlyPlayingPath != null && currentlyPlayingPath.equals(audioFile.getAbsolutePath())) {
                return; // Re-tapping active playing file cancels playback cleanly
            }
        }

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(audioFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> stopAudio());
            mediaPlayer.start();
            
            currentlyPlayingPath = audioFile.getAbsolutePath();
            currentlyPlayingTextView = statusView;
            statusView.setText("🔊 Playing... " + originalLabel);
            
            Toast.makeText(context, "Playing audio note...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            WifeLogger.log("ChatAdapter", "Failed playing audio voice note: " + e.getMessage());
            Toast.makeText(context, "Playback failed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopAudio() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (currentlyPlayingTextView != null) {
            currentlyPlayingTextView.setText(currentlyPlayingTextView.getTag() != null ? currentlyPlayingTextView.getTag().toString() : "🎤 Voice Note");
            currentlyPlayingTextView = null;
        }
        currentlyPlayingPath = null;
    }

    private void viewImage(Context context, File imageFile, String filename) {
        try {
            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    imageFile
            );
            Intent intent = new Intent(Intent.ACTION_VIEW);

            // Sniff file extension to dynamically resolve matching MIME type (Fixes Glitch 5)
            String mimeType = "image/*";
            String ext = "";
            int idx = filename.lastIndexOf('.');
            if (idx > 0) {
                ext = filename.substring(idx + 1).toLowerCase(Locale.US);
            }
            if (ext.equals("mp4") || ext.equals("mkv") || ext.equals("avi") || ext.equals("mov") || ext.equals("3gp") || ext.equals("webm")) {
                mimeType = "video/*";
            }

            intent.setDataAndType(fileUri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        } catch (Exception e) {
            WifeLogger.log("ChatAdapter", "Failed launching media system viewer: " + e.getMessage());
            Toast.makeText(context, "No compatible media viewer found.", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveReceivedFileToPublic(Context context, String filename) {
        WifeLogger.log("ChatAdapter", "User triggered save action for file: " + filename);
        
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
            case "webp":
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

        File rootDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "wife shared");
        } else {
            rootDir = new File(Environment.getExternalStorageDirectory(), "wife shared");
        }

        File targetFile = new File(new File(rootDir, subFolder), filename);
        
        if (targetFile.exists()) {
            WifeLogger.log("ChatAdapter", "Verified file existence inside public folder: " + targetFile.getAbsolutePath());
            Toast.makeText(context, "Saved to: wife shared/" + subFolder + "/" + filename, Toast.LENGTH_LONG).show();
        } else {
            WifeLogger.log("ChatAdapter", "File not found at targeted public path. It may still be transferring.");
            Toast.makeText(context, "File is still downloading or transfer failed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showContextMenu(View anchorView, final MessageEntity msg, final int position) {
        final Context context = anchorView.getContext();
        PopupMenu popup = new PopupMenu(context, anchorView);
        
        popup.getMenu().add("Copy");
        popup.getMenu().add("Delete locally");

        // Allow unsending globally only if the message originated from the local device
        boolean isSentByMe = msg.getSender().equals(selfDeviceId);
        if (isSentByMe) {
            popup.getMenu().add("Unsend globally");
        }

        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Copy".equals(title)) {
                WifeLogger.log("ChatAdapter", "User clicked: 'Copy'");
                String copyText = msg.getText();
                if (copyText.startsWith("[FILE]:") || copyText.startsWith("[IMAGE]:") || 
                    copyText.startsWith("[VIDEO]:") || copyText.startsWith("[AUDIO]:")) {
                    try {
                        int firstColon = copyText.indexOf(':');
                        String payload = copyText.substring(firstColon + 1);
                        String[] parts = payload.split("\\|");
                        if (parts.length > 0) {
                            copyText = parts[0]; // Copy only the filename
                        }
                    } catch (Exception e) {
                        WifeLogger.log("ChatAdapter", "Error cleaning clipboard text: " + e.getMessage());
                    }
                }
                copyToClipboard(context, copyText);
            } else if ("Delete locally".equals(title)) {
                WifeLogger.log("ChatAdapter", "User clicked: 'Delete locally'");
                deleteLocally(context, msg, position);
            } else if ("Unsend globally".equals(title)) {
                WifeLogger.log("ChatAdapter", "User clicked: 'Unsend globally'");
                unsendGlobally(context, msg, position);
            }
            return true;
        });
        popup.show();
    }

    private void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("WifeChat", text);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Text copied to clipboard.", Toast.LENGTH_SHORT).show();
                WifeLogger.log("ChatAdapter", "Successfully copied message text payload to system ClipboardManager.");
            }
        } catch (Exception e) {
            WifeLogger.log("ChatAdapter", "Failed copying text payload to clipboard: " + e.getMessage(), e);
        }
    }

    private void deleteLocally(Context context, MessageEntity msg, int position) {
        WifeLogger.log("ChatAdapter", "Initiating local message deletion task. Local ID: " + msg.getId());
        new Thread(() -> {
            try {
                // 1. Purge from local SQLite DB using the long primary key ID
                RoomDatabaseManager.getInstance(context).messageDao().deleteById(msg.getId());
                WifeLogger.log("ChatAdapter", "Message row successfully purged from local Room database.");

                // 2. Refresh current list elements on Main UI Thread
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        if (position < messages.size()) {
                            messages.remove(position);
                            notifyDataSetChanged();
                            WifeLogger.log("ChatAdapter", "Dataset updated. Removed index: " + position + " | Remaining cells: " + messages.size());
                        }
                    } catch (Exception e) {
                        WifeLogger.log("ChatAdapter", "Failed updating active adapter lists: " + e.getMessage(), e);
                    }
                });
            } catch (Exception e) {
                WifeLogger.log("ChatAdapter", "Error executing local message deletion thread: " + e.getMessage(), e);
            }
        }).start();
    }

    private void unsendGlobally(Context context, MessageEntity msg, int position) {
        WifeLogger.log("ChatAdapter", "Initiating global unsend request. Shared timestamp key: " + msg.getTimestamp());
        
        // 1. Clear locally first
        deleteLocally(context, msg, position);

        // 2. Dispatch "unsend" control signal to the connected peer over Port 8888
        String peerIp = ConnectionManager.getInstance(context).getPeerIpAddress();
        if (peerIp != null && !peerIp.isEmpty()) {
            WifeLogger.log("ChatAdapter", "Broadcasting unsend signal to Peer: " + peerIp + " with timestamp: " + msg.getTimestamp());
            CallSignalingManager.getInstance(context).sendSignal(peerIp, "unsend", msg.getTimestamp());
        } else {
            WifeLogger.log("ChatAdapter", "Aborted: Peer is disconnected. Cannot transmit global unsend packet.");
            Toast.makeText(context, "Failed to unsend globally: Peer disconnected.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    private String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    public static class SentViewHolder extends RecyclerView.ViewHolder {
        TextView tvText;
        TextView tvTime;
        ImageView ivImage; // Dynamic square photo/video preview block

        public SentViewHolder(@NonNull View itemView) {
            super(itemView);
            tvText = itemView.findViewById(R.id.tvMessageText);
            tvTime = itemView.findViewById(R.id.tvMessageTime);
            ivImage = itemView.findViewById(R.id.ivMessageImage);
        }
    }

    public static class ReceivedViewHolder extends RecyclerView.ViewHolder {
        TextView tvText;
        TextView tvTime;
        ImageView ivSave; // Tiny save icon for received files
        ImageView ivImage; // Dynamic square photo/video preview block

        public ReceivedViewHolder(@NonNull View itemView) {
            super(itemView);
            tvText = itemView.findViewById(R.id.tvMessageText);
            tvTime = itemView.findViewById(R.id.tvMessageTime);
            ivSave = itemView.findViewById(R.id.ivSaveAttachment);
            ivImage = itemView.findViewById(R.id.ivMessageImage);
        }
    }
}