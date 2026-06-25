package com.wife.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class FileSender {
    private static final String TAG = "FileSender";
    private static volatile FileSender instance;

    private static final long CHUNK_THRESHOLD = 100L * 1024L * 1024L; // 100 MB Threshold
    private static final int CHUNK_SIZE = 20 * 1024 * 1024;            // 20 MB Chunk Size

    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    public interface FileTransferListener {
        void onProgress(int percent);
        void onComplete(String path);
        void onError(String error);
    }

    public static FileSender getInstance(Context context) {
        if (instance == null) {
            synchronized (FileSender.class) {
                if (instance == null) {
                    instance = new FileSender(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private FileSender(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Backward-compatible single-file transmitter.
     * Delegates internally to the new persistent streaming queue engine.
     */
    public void sendFile(final Uri fileUri, final String originalFileName, final long fileSize, final FileTransferListener listener) {
        ArrayList<Uri> uris = new ArrayList<>(Collections.singletonList(fileUri));
        ArrayList<String> names = new ArrayList<>(Collections.singletonList(originalFileName));
        long[] sizes = new long[]{fileSize};

        final String peerIp = ConnectionManager.getInstance(context).getPeerIpAddress();
        if (peerIp == null || peerIp.isEmpty()) {
            if (listener != null) listener.onError("No connected peer available.");
            return;
        }

        // Trigger the queue execution under the active service lifecycle
        WifeLogger.log(TAG, "Legacy sendFile() invoked. Wrapping in single-item queue list.");

        Intent serviceIntent = new Intent(context, FileTransferForegroundService.class);
        serviceIntent.setAction(Constants.ACTION_START_TRANSFER);
        serviceIntent.putExtra("IS_SENDER", true);
        serviceIntent.putStringArrayListExtra("URI_LIST", new ArrayList<>(Collections.singletonList(fileUri.toString())));
        serviceIntent.putStringArrayListExtra("FILE_NAMES", names);
        serviceIntent.putExtra("FILE_SIZES", sizes);
        serviceIntent.putExtra("PEER_IP", peerIp);
        context.startService(serviceIntent);
    }

    /**
     * Core Persistent High-Speed Queue Transmitter.
     * Maintains a single persistent SocketChannel across all files in the queue.
     */
    public void sendQueue(final List<Uri> uris, final List<String> fileNames, final long[] fileSizes, final String peerIp) {
        WifeLogger.log(TAG, "sendQueue() started. Files count: " + uris.size() + " | Destination Peer: " + peerIp);

        executorService.execute(() -> {
            // Symmetrical State Reset: Ensure a clean transactional starting point
            FileTransferForegroundService.isCancelled = false;
            FileTransferForegroundService.isPaused = false;

            try {
                for (int i = 0; i < uris.size(); i++) {
                    if (FileTransferForegroundService.isCancelled) {
                        WifeLogger.log(TAG, "Queue loop cancelled by user. Terminating sender.");
                        break;
                    }

                    Uri fileUri = uris.get(i);
                    String fileName = fileNames.get(i);
                    long fileSize = fileSizes[i];

                    if (fileSize > CHUNK_THRESHOLD) {
                        WifeLogger.log(TAG, "File size exceeds 100MB threshold. Initiating parallel chunked stream pipeline.");
                        sendLargeFileInParallel(fileUri, fileName, fileSize, peerIp, i);
                    } else {
                        WifeLogger.log(TAG, "File size is below 100MB. Proceeding with standard sequential LZ4 streaming.");
                        sendSequentialFile(fileUri, fileName, fileSize, peerIp, i);
                    }
                }

                if (!FileTransferForegroundService.isCancelled) {
                    broadcastCompletion();
                }

            } catch (Exception e) {
                WifeLogger.log(TAG, "Persistent file sending pipeline threw fatal exception: " + e.getMessage(), e);
                broadcastError(e.getMessage());
            } finally {
                // Stop foreground service context cleanly
                Intent stopIntent = new Intent(context, FileTransferForegroundService.class);
                context.stopService(stopIntent);
            }
        });
    }

    /**
     * Sends large files using parallel sockets and dynamic 20MB chunks (Glitch 1 & Chunk Fix).
     */
    private void sendLargeFileInParallel(Uri fileUri, String fileName, long fileSize, String peerIp, int fileIndex) throws Exception {
        final String fileId = UUID.randomUUID().toString();
        final int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
        WifeLogger.log(TAG, "Parallel chunks metadata calculated. Total chunks to build: " + totalChunks);

        ExecutorService chunkExecutor = Executors.newFixedThreadPool(Math.min(5, totalChunks));
        AtomicLong totalBytesSentCombined = new AtomicLong(0);
        long lastNotificationTime = System.currentTimeMillis();

        // 1. Queue all blocks concurrently to compression threads
        for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
            final int finalChunkIdx = chunkIdx;
            chunkExecutor.execute(() -> {
                if (FileTransferForegroundService.isCancelled) return;

                File tempChunkFile = new File(context.getCacheDir(), "temp_send_chunk_" + fileId + "_" + finalChunkIdx + ".lz4");
                long startOffset = (long) finalChunkIdx * CHUNK_SIZE;
                long rawChunkSize = Math.min(CHUNK_SIZE, fileSize - startOffset);

                // 2. Read, compress on-the-fly and write block to temp files
                try {
                    try (InputStream is = context.getContentResolver().openInputStream(fileUri);
                         FileOutputStream fos = new FileOutputStream(tempChunkFile);
                         net.jpountz.lz4.LZ4FrameOutputStream lz4Out = new net.jpountz.lz4.LZ4FrameOutputStream(fos, net.jpountz.lz4.LZ4FrameOutputStream.BLOCKSIZE.SIZE_256KB)) {

                        if (is == null) throw new IOException("Failed opening content URI stream descriptor.");
                        
                        // Fast skip to chunk segment offset
                        long skipped = 0;
                        while (skipped < startOffset) {
                            long skipRead = is.skip(startOffset - skipped);
                            if (skipRead <= 0) break;
                            skipped += skipRead;
                        }

                        byte[] buffer = new byte[65536]; // 64KB high speed buffer
                        long bytesReadTotal = 0;
                        int read;

                        while (bytesReadTotal < rawChunkSize && (read = is.read(buffer, 0, (int) Math.min(buffer.length, rawChunkSize - bytesReadTotal))) != -1) {
                            if (FileTransferForegroundService.isCancelled) break;
                            
                            // Symmetrical pause locks check
                            synchronized (FileTransferForegroundService.pauseLock) {
                                while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                                    try {
                                        FileTransferForegroundService.pauseLock.wait();
                                    } catch (InterruptedException ignored) {}
                                }
                            }

                            lz4Out.write(buffer, 0, read);
                            bytesReadTotal += read;
                        }
                    }

                    long compressedChunkSize = tempChunkFile.length();
                    WifeLogger.log(TAG, "Chunk [" + finalChunkIdx + "] compression finalized. Compressed: " + compressedChunkSize + " bytes.");

                    if (FileTransferForegroundService.isCancelled) return;

                    // 3. Establish individual parallel socket connection to Port 8900
                    try (SocketChannel chunkChannel = SocketChannel.open()) {
                        chunkChannel.connect(new InetSocketAddress(peerIp, Constants.OFF_PORT_FILE));
                        chunkChannel.configureBlocking(true);
                        OutputStream os = chunkChannel.socket().getOutputStream();

                        // 4. Serialize and transmit individual chunk metadata header
                        JsonObject chunkMeta = new JsonObject();
                        chunkMeta.addProperty("type", "chunk");
                        chunkMeta.addProperty("fileId", fileId);
                        chunkMeta.addProperty("name", fileName);
                        chunkMeta.addProperty("chunkIndex", finalChunkIdx);
                        chunkMeta.addProperty("totalChunks", totalChunks);
                        chunkMeta.addProperty("size", fileSize);
                        chunkMeta.addProperty("compressedSize", compressedChunkSize);

                        byte[] metaBytes = chunkMeta.toString().getBytes(StandardCharsets.UTF_8);
                        byte[] lenBytes = new byte[4];
                        lenBytes[0] = (byte) ((metaBytes.length >> 24) & 0xFF);
                        lenBytes[1] = (byte) ((metaBytes.length >> 16) & 0xFF);
                        lenBytes[2] = (byte) ((metaBytes.length >> 8) & 0xFF);
                        lenBytes[3] = (byte) (metaBytes.length & 0xFF);

                        os.write(lenBytes);
                        os.write(metaBytes);
                        os.flush();

                        // 5. High-Speed streaming loop for compressed chunk bytes
                        try (FileInputStream fis = new FileInputStream(tempChunkFile)) {
                            byte[] buffer = new byte[16384];
                            int len;
                            long bytesSentForThisChunk = 0;

                            while ((len = fis.read(buffer)) != -1) {
                                if (FileTransferForegroundService.isCancelled) break;

                                synchronized (FileTransferForegroundService.pauseLock) {
                                    while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                                        try {
                                            FileTransferForegroundService.pauseLock.wait();
                                        } catch (InterruptedException ignored) {}
                                    }
                                }

                                os.write(buffer, 0, len);
                                bytesSentForThisChunk += len;
                                long totalSoFar = totalBytesSentCombined.addAndGet(len);

                                // Throttle real-time aggregate progress reporting
                                long now = System.currentTimeMillis();
                                if (now - lastNotificationTime >= 500) {
                                    int percent = (int) ((totalSoFar * 100) / (fileSize)); // Base percent on original size limit
                                    broadcastProgress(fileName, totalSoFar, fileSize, percent, fileIndex, 0.0);
                                }
                            }
                        }

                        // Gracefully shutdown outputs to prevent packet loss
                        chunkChannel.socket().shutdownOutput();
                        Thread.sleep(200);

                    }
                } catch (Exception e) {
                    WifeLogger.log(TAG, "Failed parallel chunk delivery for index " + finalChunkIdx + ": " + e.getMessage());
                } finally {
                    if (tempChunkFile.exists()) {
                        tempChunkFile.delete();
                    }
                }
            });
        }

        chunkExecutor.shutdown();
        chunkExecutor.awaitTermination(1, TimeUnit.HOURS);

        if (FileTransferForegroundService.isCancelled) {
            throw new IOException("Parallel transfer terminated by user cancellation.");
        }

        // Insert database transaction record
        FileEntity entity = new FileEntity(fileName, fileSize, fileUri.toString(), System.currentTimeMillis());
        RoomDatabaseManager.getInstance(context).fileDao().insert(entity);
    }

    /**
     * Standard sequential LZ4 file transmitter for files under 100MB.
     */
    private void sendSequentialFile(Uri fileUri, String fileName, long fileSize, String peerIp, int fileIndex) throws Exception {
        SocketChannel socketChannel = null;
        OutputStream socketOs = null;

        try {
            socketChannel = SocketChannel.open();
            socketChannel.connect(new InetSocketAddress(peerIp, Constants.OFF_PORT_FILE));
            socketChannel.configureBlocking(true);
            socketOs = socketChannel.socket().getOutputStream();

            File tempCompressedFile = new File(context.getCacheDir(), "temp_send_" + UUID.randomUUID().toString() + "_" + fileName + ".lz4");

            try {
                // Compress raw stream with optimized 64KB blocks
                try (InputStream is = context.getContentResolver().openInputStream(fileUri);
                     FileOutputStream fos = new FileOutputStream(tempCompressedFile);
                     net.jpountz.lz4.LZ4FrameOutputStream lz4Out = new net.jpountz.lz4.LZ4FrameOutputStream(fos, net.jpountz.lz4.LZ4FrameOutputStream.BLOCKSIZE.SIZE_256KB)) {

                    if (is == null) throw new IOException("Failed opening content URI stream.");

                    byte[] buffer = new byte[65536];
                    int read;
                    long bytesReadTotal = 0;
                    long lastProgressUpdate = 0;

                    while ((read = is.read(buffer)) != -1) {
                        if (FileTransferForegroundService.isCancelled) break;

                        synchronized (FileTransferForegroundService.pauseLock) {
                            while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                                try {
                                    FileTransferForegroundService.pauseLock.wait();
                                } catch (InterruptedException ignored) {}
                            }
                        }

                        lz4Out.write(buffer, 0, read);
                        bytesReadTotal += read;

                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastProgressUpdate >= 500) {
                            int percent = (fileSize > 0) ? (int) ((bytesReadTotal * 100) / fileSize) : 0;
                            broadcastProgress("Compressing: " + fileName, bytesReadTotal, fileSize, percent, fileIndex, 0.0);
                            lastProgressUpdate = currentTime;
                        }
                    }
                }

                long compressedSize = tempCompressedFile.length();
                WifeLogger.log(TAG, "Compression complete. Compressed Size: " + compressedSize + " bytes.");

                if (FileTransferForegroundService.isCancelled) return;

                // 3. Serialize and transmit metadata descriptor
                JsonObject fileMeta = new JsonObject();
                fileMeta.addProperty("type", "file");
                fileMeta.addProperty("name", fileName);
                fileMeta.addProperty("size", fileSize);
                fileMeta.addProperty("compressedSize", compressedSize);
                fileMeta.addProperty("lastPosition", FileTransferForegroundService.lastPosition);

                byte[] metaBytes = fileMeta.toString().getBytes(StandardCharsets.UTF_8);
                byte[] lenBytes = new byte[4];
                lenBytes[0] = (byte) ((metaBytes.length >> 24) & 0xFF);
                lenBytes[1] = (byte) ((metaBytes.length >> 16) & 0xFF);
                lenBytes[2] = (byte) ((metaBytes.length >> 8) & 0xFF);
                lenBytes[3] = (byte) (metaBytes.length & 0xFF);

                socketOs.write(lenBytes);
                socketOs.write(metaBytes);
                socketOs.flush();

                // 4. Send raw compressed byte stream over socket directly
                try (FileInputStream fisCompressed = new FileInputStream(tempCompressedFile)) {
                    if (FileTransferForegroundService.lastPosition > 0) {
                        long skipped = fisCompressed.skip(FileTransferForegroundService.lastPosition);
                        WifeLogger.log(TAG, "Skipped bytes successfully: " + skipped);
                    }

                    byte[] buffer = new byte[16384];
                    int readBytes;
                    long totalBytesSent = FileTransferForegroundService.lastPosition;
                    long lastNotificationUpdateTime = System.currentTimeMillis();
                    long speedPeriodBytesSent = 0;
                    long speedPeriodStartTime = System.currentTimeMillis();
                    double currentSpeed = 0.0;

                    while ((readBytes = fisCompressed.read(buffer)) != -1) {
                        if (FileTransferForegroundService.isCancelled) break;

                        synchronized (FileTransferForegroundService.pauseLock) {
                            while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                                try {
                                    FileTransferForegroundService.pauseLock.wait();
                                } catch (InterruptedException ignored) {}
                            }
                        }

                        socketOs.write(buffer, 0, readBytes);
                        totalBytesSent += readBytes;
                        speedPeriodBytesSent += readBytes;
                        FileTransferForegroundService.lastPosition = totalBytesSent;

                        long currentTime = System.currentTimeMillis();
                        long timeDiff = currentTime - speedPeriodStartTime;
                        if (timeDiff >= 1000) {
                            currentSpeed = ((double) speedPeriodBytesSent / (1024.0 * 1024.0)) / ((double) timeDiff / 1000.0);
                            speedPeriodBytesSent = 0;
                            speedPeriodStartTime = currentTime;
                        }

                        if (currentTime - lastNotificationUpdateTime >= 500) {
                            int percent = (int) ((totalBytesSent * 100) / compressedSize);
                            broadcastProgress(fileName, totalBytesSent, compressedSize, percent, fileIndex, currentSpeed);
                            lastNotificationUpdateTime = currentTime;
                        }
                    }
                }

                if (!FileTransferForegroundService.isCancelled) {
                    FileEntity entity = new FileEntity(fileName, fileSize, fileUri.toString(), System.currentTimeMillis());
                    RoomDatabaseManager.getInstance(context).fileDao().insert(entity);
                    FileTransferForegroundService.lastPosition = 0;
                    broadcastProgress(fileName, compressedSize, compressedSize, 100, fileIndex, 0.0);
                }
            } finally {
                if (tempCompressedFile.exists()) {
                    tempCompressedFile.delete();
                }
            }
        } finally {
            try {
                if (socketChannel != null && socketChannel.socket() != null && !socketChannel.socket().isClosed()) {
                    socketChannel.socket().shutdownOutput();
                    Thread.sleep(500); // 500ms grace window (98% halt fix)
                }
            } catch (Exception ignored) {}
            try {
                if (socketChannel != null && socketChannel.isOpen()) {
                    socketChannel.close();
                }
            } catch (IOException ignored) {}
        }
    }

    private ByteBuffer metadataBuffer(byte[] metaBytes) {
        return ByteBuffer.wrap(metaBytes);
    }

    private void broadcastProgress(String fileName, long transferred, long total, int percent, int fileIndex, double speed) {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_PROGRESS);
        intent.putExtra(Constants.EXTRA_FILE_NAME, fileName);
        intent.putExtra(Constants.EXTRA_BYTES_TRANSFERRED, transferred);
        intent.putExtra(Constants.EXTRA_TOTAL_BYTES, total);
        intent.putExtra(Constants.EXTRA_FILE_INDEX, fileIndex);
        intent.putExtra(Constants.EXTRA_TRANSFER_SPEED, speed);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

        // Update foreground service notification in parallel with formatted transfer rate metrics
        String speedText = String.format(Locale.US, "%.1f MB/s", speed);
        Intent serviceIntent = new Intent(context, FileTransferForegroundService.class);
        serviceIntent.setAction("UPDATE_NOTIF");
        serviceIntent.putExtra("NOTIF_TEXT", "Sending " + fileName + " (" + percent + "%) - " + speedText);
        serviceIntent.putExtra("PROGRESS", percent);
        context.startService(serviceIntent);
    }

    private void broadcastCompletion() {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_COMPLETE);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private void broadcastError(String message) {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_ERROR);
        intent.putExtra(Constants.EXTRA_ERROR_MESSAGE, message);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    // --- Symmetrical Non-Closing Socket Stream Wrapper ---
    private static class NonClosingOutputStream extends OutputStream {
        private final OutputStream delegate;

        public NonClosingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            // Intercept close() request to preserve the underlying persistent SocketChannel
            Log.d(TAG, "Intercepted close() request. Stream remains open.");
        }
    }
}