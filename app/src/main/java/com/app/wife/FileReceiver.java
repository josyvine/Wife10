package com.wife.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class FileReceiver implements Runnable {
    private static final String TAG = "FileReceiver";

    private final Context context;
    private final Socket socket;
    private final Handler mainHandler;

    public interface FileReceiveListener {
        void onProgress(String filename, int percent);
        void onComplete(String filename, String localPath);
        void onError(String error);
    }

    private static final List<FileReceiveListener> listeners = new ArrayList<>();
    
    // Thread-safe map tracking aggregate chunks received per active file transaction
    private static final ConcurrentHashMap<String, AtomicInteger> activeTransfers = new ConcurrentHashMap<>();

    public static synchronized void registerListener(FileReceiveListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static synchronized void unregisterListener(FileReceiveListener listener) {
        listeners.remove(listener);
    }

    /**
     * Backward-compatible legacy constructor.
     */
    public FileReceiver(Context context, Socket socket) {
        this.context = context;
        this.socket = socket;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Backward-compatible thread entry point.
     */
    @Override
    public void run() {
        WifeLogger.log(TAG, "Legacy FileReceiver runnable invoked. Redirecting to single socket processor.");
        try {
            socket.getChannel().configureBlocking(true);
            processPersistentStream(context, socket.getChannel());
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error executing legacy fallback: " + e.getMessage(), e);
            notifyError(e.getMessage());
        }
    }

    /**
     * Symmetrical server socket entry point called directly by the active foreground service.
     */
    public static void startServer(final Context context) {
        new Thread(() -> {
            ServerSocketChannel serverChannel = null;
            try {
                WifeLogger.log(TAG, "Opening ServerSocketChannel on port: " + Constants.OFF_PORT_FILE);
                serverChannel = ServerSocketChannel.open();
                serverChannel.socket().bind(new InetSocketAddress(Constants.OFF_PORT_FILE));
                WifeLogger.log(TAG, "ServerSocketChannel successfully bound. Entering persistent accept loop.");

                while (serverChannel.isOpen()) {
                    SocketChannel clientChannel = null;
                    try {
                        clientChannel = serverChannel.accept();
                        clientChannel.configureBlocking(true);

                        final SocketChannel finalChannel = clientChannel;
                        // Spawns background worker thread instantly to process parallel chunk streams concurrently (Glitch 1 & parallel Fix)
                        new Thread(() -> {
                            try {
                                String clientIp = finalChannel.socket().getInetAddress().getHostAddress();
                                WifeLogger.log(TAG, "Processing parallel transfer stream connection from: " + clientIp);
                                processPersistentStream(context, finalChannel);
                            } catch (Exception e) {
                                WifeLogger.log(TAG, "Parallel socket stream connection failed: " + e.getMessage(), e);
                                broadcastError(context, e.getMessage());
                            } finally {
                                try {
                                    finalChannel.close();
                                } catch (IOException ignored) {}
                            }
                        }).start();

                    } catch (Exception e) {
                        WifeLogger.log(TAG, "Active socket connection accept failed: " + e.getMessage(), e);
                    }
                }
            } catch (Exception e) {
                WifeLogger.log(TAG, "ServerSocketChannel threw exception or was closed: " + e.getMessage());
                if (serverChannel != null && serverChannel.isOpen()) {
                    broadcastError(context, e.getMessage());
                }
            } finally {
                try {
                    if (serverChannel != null) {
                        serverChannel.close();
                    }
                } catch (IOException ignored) {}
            }
        }).start();
    }

    /**
     * Persistent Multi-File Stream Processor.
     * Processes metadata headers and LZ4 decompression segments sequentially over the active SocketChannel.
     */
    private static void processPersistentStream(Context context, SocketChannel socketChannel) throws Exception {
        // Reset static cancellation state for this fresh inbound transaction
        FileTransferForegroundService.isCancelled = false;
        FileTransferForegroundService.isPaused = false;

        InputStream rawSocketIn = socketChannel.socket().getInputStream();
        NonClosingInputStream proxyIn = new NonClosingInputStream(rawSocketIn);
        int fileIndex = 0;

        while (!FileTransferForegroundService.isCancelled && socketChannel.isConnected()) {
            // 1. Read the 4-byte metadata length header directly from proxyIn
            byte[] lenBytes = new byte[4];
            int bytesRead = 0;
            while (bytesRead < 4 && !FileTransferForegroundService.isCancelled) {
                // Symmetrical Thread-Safe Pause/Resume wait monitor locks
                synchronized (FileTransferForegroundService.pauseLock) {
                    while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                        try {
                            WifeLogger.log(TAG, "Receiver thread entering wait state due to active pause command.");
                            FileTransferForegroundService.pauseLock.wait();
                        } catch (InterruptedException e) {
                            WifeLogger.log(TAG, "Receiver pause monitor thread interrupted.");
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                if (FileTransferForegroundService.isCancelled) {
                    break;
                }

                int read = proxyIn.read(lenBytes, bytesRead, 4 - bytesRead);
                if (read == -1) {
                    WifeLogger.log(TAG, "Stream ended abruptly while reading metadata length.");
                    return;
                }
                bytesRead += read;
            }

            if (FileTransferForegroundService.isCancelled) {
                break;
            }

            // Decode big-endian integer length
            int metadataLength = ((lenBytes[0] & 0xFF) << 24) |
                                 ((lenBytes[1] & 0xFF) << 16) |
                                 ((lenBytes[2] & 0xFF) << 8)  |
                                 (lenBytes[3] & 0xFF);

            // 0 metadata length indicates the persistent queue transfer session has completed naturally
            if (metadataLength == 0) {
                WifeLogger.log(TAG, "End of persistent queue stream marker received. Closing stream.");
                broadcastCompletion(context);
                break;
            }

            // 2. Read exactly the serialized JSON metadata payload directly from proxyIn
            byte[] metaBytes = new byte[metadataLength];
            bytesRead = 0;
            while (bytesRead < metadataLength && !FileTransferForegroundService.isCancelled) {
                // Symmetrical Thread-Safe Pause/Resume wait monitor locks
                synchronized (FileTransferForegroundService.pauseLock) {
                    while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                        try {
                            WifeLogger.log(TAG, "Receiver thread entering wait state due to active pause command.");
                            FileTransferForegroundService.pauseLock.wait();
                        } catch (InterruptedException e) {
                            WifeLogger.log(TAG, "Receiver pause monitor thread interrupted.");
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                if (FileTransferForegroundService.isCancelled) {
                    break;
                }

                int read = proxyIn.read(metaBytes, bytesRead, metadataLength - bytesRead);
                if (read == -1) {
                    throw new IOException("Stream ended abruptly while reading metadata payload.");
                }
                bytesRead += read;
            }

            if (FileTransferForegroundService.isCancelled) {
                break;
            }

            String metaJson = new String(metaBytes, StandardCharsets.UTF_8);
            JsonObject meta = JsonParser.parseString(metaJson).getAsJsonObject();

            final String filename = meta.get("name").getAsString();
            final long originalSize = meta.get("size").getAsLong();
            final long compressedSize = meta.get("compressedSize").getAsLong();
            long resumePosition = meta.has("lastPosition") ? meta.get("lastPosition").getAsLong() : 0;
            
            // Check for chunk-type parallel transmissions
            String transferType = meta.has("type") ? meta.get("type").getAsString() : "file";

            WifeLogger.log(TAG, "Processing incoming payload: " + filename + " | Type: " + transferType + " | Size: " + originalSize + " bytes");

            File targetDir = getTargetDirectory(context, filename);

            if ("chunk".equals(transferType)) {
                final String fileId = meta.get("fileId").getAsString();
                final int chunkIndex = meta.get("chunkIndex").getAsInt();
                final int totalChunks = meta.get("totalChunks").getAsInt();

                WifeLogger.log(TAG, "Processing parallel chunk [" + chunkIndex + "/" + totalChunks + "] for File ID: " + fileId);

                File tempCompressedChunk = new File(context.getCacheDir(), "chunk_" + fileId + "_" + chunkIndex + ".lz4");
                File tempRawChunk = new File(context.getCacheDir(), "chunk_" + fileId + "_" + chunkIndex + ".raw");

                // 3. Receive and decompress chunk bytes
                try {
                    try (FileOutputStream fos = new FileOutputStream(tempCompressedChunk)) {
                        byte[] buffer = new byte[16384];
                        long totalBytesRead = 0;
                        while (totalBytesRead < compressedSize && !FileTransferForegroundService.isCancelled) {
                            int bytesToRead = (int) Math.min(buffer.length, compressedSize - totalBytesRead);
                            int read = proxyIn.read(buffer, 0, bytesToRead);
                            if (read == -1) {
                                throw new IOException("Connection severed abruptly during raw chunk payload transfer.");
                            }
                            fos.write(buffer, 0, read);
                            totalBytesRead += read;
                        }
                        fos.flush();
                    }

                    if (!FileTransferForegroundService.isCancelled) {
                        // Decompress chunk locally
                        try (FileInputStream fis = new FileInputStream(tempCompressedChunk);
                             FileOutputStream fos = new FileOutputStream(tempRawChunk)) {
                            CompressionUtils.decompress(fis, fos);
                        }

                        // Track active completed chunks for this specific transaction ID
                        activeTransfers.putIfAbsent(fileId, new AtomicInteger(0));
                        int completed = activeTransfers.get(fileId).incrementAndGet();

                        // Symmetrical Progress notification
                        int percent = (completed * 100) / totalChunks;
                        notifyProgress(context, filename, percent, completed * 20L * 1024L * 1024L, originalSize, fileIndex, 0.0);

                        if (completed == totalChunks) {
                            // All parts arrived. Spin up the merger thread to assemble the final target file
                            mergeChunksAndFinalize(context, fileId, totalChunks, targetDir, filename, originalSize, fileIndex);
                        }
                    }
                } finally {
                    if (tempCompressedChunk.exists()) {
                        tempCompressedChunk.delete();
                    }
                }

                // Chunk socket connection terminates immediately after writing the individual part
                break;

            } else {
                // STANDARD SEQUENTIAL SINGLE-FILE TRANSFER
                File fileDest = new File(targetDir, filename);
                File tempCompressedFile = new File(context.getCacheDir(), "temp_recv_" + System.currentTimeMillis() + "_" + filename + ".lz4");

                try {
                    try (FileOutputStream fos = new FileOutputStream(tempCompressedFile, resumePosition > 0)) {
                        byte[] buffer = new byte[16384];
                        long totalBytesRead = resumePosition;
                        long lastNotificationUpdateTime = System.currentTimeMillis();
                        long speedPeriodBytesRead = 0;
                        long speedPeriodStartTime = System.currentTimeMillis();
                        double currentSpeed = 0.0;

                        while (totalBytesRead < compressedSize && !FileTransferForegroundService.isCancelled) {
                            synchronized (FileTransferForegroundService.pauseLock) {
                                while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                                    try {
                                        FileTransferForegroundService.pauseLock.wait();
                                    } catch (InterruptedException ignored) {}
                                }
                            }

                            if (FileTransferForegroundService.isCancelled) {
                                break;
                            }

                            int bytesToRead = (int) Math.min(buffer.length, compressedSize - totalBytesRead);
                            int read = proxyIn.read(buffer, 0, bytesToRead);
                            if (read == -1) {
                                throw new IOException("Connection severed abruptly during raw payload transfer.");
                            }

                            fos.write(buffer, 0, read);
                            totalBytesRead += read;
                            speedPeriodBytesRead += read;

                            long currentTime = System.currentTimeMillis();
                            long timeDiff = currentTime - speedPeriodStartTime;
                            if (timeDiff >= 1000) {
                                currentSpeed = ((double) speedPeriodBytesRead / (1024.0 * 1024.0)) / ((double) timeDiff / 1000.0);
                                speedPeriodBytesRead = 0;
                                speedPeriodStartTime = currentTime;
                            }

                            if (currentTime - lastNotificationUpdateTime >= 500) {
                                int percent = (int) ((totalBytesRead * 100) / compressedSize);
                                notifyProgress(context, filename, percent, totalBytesRead, compressedSize, fileIndex, currentSpeed);
                                lastNotificationUpdateTime = currentTime;
                            }
                        }
                        fos.flush();
                    }

                    if (!FileTransferForegroundService.isCancelled) {
                        WifeLogger.log(TAG, "Compressed payload received. Decompressing file locally: " + fileDest.getAbsolutePath());

                        try (FileInputStream fis = new FileInputStream(tempCompressedFile);
                             FileOutputStream fos = new FileOutputStream(fileDest)) {
                            CompressionUtils.decompress(fis, fos);
                        }

                        WifeLogger.log(TAG, "File successfully decrypted and saved: " + fileDest.getAbsolutePath());

                        FileEntity entity = new FileEntity(filename, originalSize, fileDest.getAbsolutePath(), System.currentTimeMillis());
                        RoomDatabaseManager.getInstance(context).fileDao().insert(entity);

                        notifyComplete(context, filename, fileDest.getAbsolutePath(), fileIndex);
                        fileIndex++;
                    }
                } finally {
                    if (tempCompressedFile.exists()) {
                        tempCompressedFile.delete();
                    }
                }
            }
        }
    }

    /**
     * Glues raw temporary chunk files back into a single valid uncompressed file (Glitch 1 Merge Fix).
     */
    private static void mergeChunksAndFinalize(Context context, String fileId, int totalChunks, File targetDir, String filename, long originalSize, int fileIndex) {
        new Thread(() -> {
            File fileDest = new File(targetDir, filename);
            WifeLogger.log(TAG, "All chunks received. Merging chunk parts into final file: " + fileDest.getAbsolutePath());

            try (FileOutputStream fos = new FileOutputStream(fileDest)) {
                byte[] buffer = new byte[131072]; // High-performance 128KB buffer
                
                for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                    File tempRawChunk = new File(context.getCacheDir(), "chunk_" + fileId + "_" + chunkIndex + ".raw");
                    if (!tempRawChunk.exists()) {
                        throw new IOException("Missing parallel chunk part index: " + chunkIndex);
                    }

                    try (FileInputStream fis = new FileInputStream(tempRawChunk)) {
                        int read;
                        while ((read = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                    }
                    // Delete temporary segment instantly to preserve disk storage (ENOSPC fix)
                    tempRawChunk.delete();
                }
                fos.flush();

                WifeLogger.log(TAG, "File successfully decrypted, merged and saved: " + fileDest.getAbsolutePath());

                // Save history record in database
                FileEntity entity = new FileEntity(filename, originalSize, fileDest.getAbsolutePath(), System.currentTimeMillis());
                RoomDatabaseManager.getInstance(context).fileDao().insert(entity);

                notifyComplete(context, filename, fileDest.getAbsolutePath(), fileIndex);
                activeTransfers.remove(fileId);

            } catch (Exception e) {
                WifeLogger.log(TAG, "Merge and finalize failed for file ID " + fileId + ": " + e.getMessage(), e);
                broadcastError(context, e.getMessage());
            }
        }).start();
    }

    private static File getTargetDirectory(Context context, String filename) {
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
            case "webp":
                subFolder = "images";
                break;
            case "mp4":
            case "mkv":
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

    // --- UI/Notification Broadcast dispatchers ---

    private static void notifyProgress(Context context, final String filename, final int percent, long transferred, long total, int fileIndex, double speed) {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (FileReceiver.class) {
                for (FileReceiveListener l : listeners) {
                    l.onProgress(filename, percent);
                }
            }
        });

        // Intent broadcast to FileTransferActivity with transmission rate metrics
        Intent intent = new Intent(Constants.ACTION_TRANSFER_PROGRESS);
        intent.putExtra(Constants.EXTRA_FILE_NAME, filename);
        intent.putExtra(Constants.EXTRA_BYTES_TRANSFERRED, transferred);
        intent.putExtra(Constants.EXTRA_TOTAL_BYTES, total);
        intent.putExtra(Constants.EXTRA_FILE_INDEX, fileIndex);
        intent.putExtra(Constants.EXTRA_TRANSFER_SPEED, speed);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

        // Foreground Service Notification update
        String speedText = String.format(Locale.US, "%.1f MB/s", speed);
        Intent serviceIntent = new Intent(context, FileTransferForegroundService.class);
        serviceIntent.setAction("UPDATE_NOTIF");
        serviceIntent.putExtra("NOTIF_TEXT", "Receiving " + filename + " (" + percent + "%) - " + speedText);
        serviceIntent.putExtra("PROGRESS", percent);
        context.startService(serviceIntent);
    }

    private static void notifyComplete(Context context, final String filename, final String path, int fileIndex) {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (FileReceiver.class) {
                for (FileReceiveListener l : listeners) {
                    l.onComplete(filename, path);
                }
            }
        });

        Intent intent = new Intent(Constants.ACTION_TRANSFER_PROGRESS);
        intent.putExtra(Constants.EXTRA_FILE_NAME, filename);
        intent.putExtra(Constants.EXTRA_BYTES_TRANSFERRED, 1L); // Force complete UI redraw
        intent.putExtra(Constants.EXTRA_TOTAL_BYTES, 1L);
        intent.putExtra(Constants.EXTRA_FILE_INDEX, fileIndex);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private static void broadcastCompletion(Context context) {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_COMPLETE);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

        // Symmetrical service cleanup to resolve Glitch 3 (Clears Receiver sticky notification)
        Intent stopIntent = new Intent(context, FileTransferForegroundService.class);
        context.stopService(stopIntent);
    }

    private static void broadcastError(Context context, String message) {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_ERROR);
        intent.putExtra(Constants.EXTRA_ERROR_MESSAGE, message);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

        // Symmetrical service cleanup on error to resolve Glitch 3 (Clears Receiver sticky notification)
        Intent stopIntent = new Intent(context, FileTransferForegroundService.class);
        context.stopService(stopIntent);
    }

    private static void notifyError(final String error) {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (FileReceiver.class) {
                for (FileReceiveListener l : listeners) {
                    l.onError(error);
                }
            }
        });
    }

    // --- Symmetrical Non-Closing Socket Stream Wrapper ---
    private static class NonClosingInputStream extends InputStream {
        private final InputStream delegate;

        public NonClosingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            // Intercept close() commands to prevent closing the underlying persistent socket
            Log.d(TAG, "Intercepted close() request. Stream remains open.");
        }
    }
}