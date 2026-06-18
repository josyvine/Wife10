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
import net.jpountz.lz4.LZ4FrameOutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileSender {
    private static final String TAG = "FileSender";
    private static volatile FileSender instance;

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
            SocketChannel socketChannel = null;
            OutputStream socketOs = null;

            try {
                // 1. Establish persistent SocketChannel to the receiver
                WifeLogger.log(TAG, "Opening SocketChannel connection to " + peerIp + " on file port " + Constants.OFF_PORT_FILE);
                socketChannel = SocketChannel.open();
                socketChannel.connect(new InetSocketAddress(peerIp, Constants.OFF_PORT_FILE));
                socketChannel.configureBlocking(true);
                
                socketOs = socketChannel.socket().getOutputStream();
                WifeLogger.log(TAG, "SocketChannel persistent connection established successfully.");

                // 2. Loop sequentially through the file queue
                for (int i = 0; i < uris.size(); i++) {
                    if (FileTransferForegroundService.isCancelled) {
                        WifeLogger.log(TAG, "Queue loop cancelled by user. Terminating sender.");
                        break;
                    }

                    Uri fileUri = uris.get(i);
                    String fileName = fileNames.get(i);
                    long fileSize = fileSizes[i];

                    WifeLogger.log(TAG, "Processing file [" + (i + 1) + "/" + uris.size() + "]: " + fileName + " (" + fileSize + " bytes)");

                    // Read local file through file channels
                    try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(fileUri, "r");
                         FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                         FileChannel fileChannel = fis.getChannel()) {

                        // 3. Serialize and transmit metadata descriptor
                        JsonObject fileMeta = new JsonObject();
                        fileMeta.addProperty("type", "file");
                        fileMeta.addProperty("name", fileName);
                        fileMeta.addProperty("size", fileSize);
                        fileMeta.addProperty("lastPosition", FileTransferForegroundService.lastPosition);

                        byte[] metaBytes = fileMeta.toString().getBytes(StandardCharsets.UTF_8);
                        
                        // Write 4-byte length header prefix
                        ByteBuffer lenBuf = ByteBuffer.allocate(4);
                        lenBuf.putInt(metaBytes.length);
                        lenBuf.flip();
                        while (lenBuf.hasRemaining()) {
                            socketChannel.write(lenBuf);
                        }

                        // Write raw JSON block
                        ByteBuffer metaBuf = ByteBuffer.wrap(metaBytes);
                        while (metaBuf.hasRemaining()) {
                            socketChannel.write(metadataBuffer(metaBytes));
                        }

                        Log.d(TAG, "JSON Handshake payload sent: " + fileMeta.toString());

                        // 4. Wrap Socket Channel output stream into the fast LZ4 compressor stream
                        // Note: We use .finish() instead of .close() inside the file transitions 
                        // to keep the underlying SocketChannel open and persistent!
                        LZ4FrameOutputStream lz4Out = CompressionUtils.wrapOutputStream(socketOs);

                        // Position channel if resuming from a crash/partial state
                        if (FileTransferForegroundService.lastPosition > 0) {
                            WifeLogger.log(TAG, "Resuming file transfer from offset position: " + FileTransferForegroundService.lastPosition);
                            fileChannel.position(FileTransferForegroundService.lastPosition);
                        }

                        // 5. High-Speed 16KB ByteBuffer chunk streaming loop
                        ByteBuffer byteBuffer = ByteBuffer.allocate(16384);
                        long totalBytesSent = FileTransferForegroundService.lastPosition;
                        long lastNotificationUpdateTime = System.currentTimeMillis();

                        while (!FileTransferForegroundService.isCancelled) {
                            // Symmetrical Thread-Safe Pause/Resume wait monitor locks
                            synchronized (FileTransferForegroundService.pauseLock) {
                                while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                                    try {
                                        WifeLogger.log(TAG, "Sender thread entering wait state due to active pause command.");
                                        FileTransferForegroundService.pauseLock.wait();
                                    } catch (InterruptedException e) {
                                        WifeLogger.log(TAG, "Sender pause monitor thread interrupted.");
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            }

                            if (FileTransferForegroundService.isCancelled) {
                                break;
                            }

                            byteBuffer.clear();
                            int readBytes = fileChannel.read(byteBuffer);
                            if (readBytes == -1) {
                                WifeLogger.log(TAG, "Reached EOF on local FileChannel.");
                                break;
                            }
                            byteBuffer.flip();

                            // Compress and write chunk on the fly
                            lz4Out.write(byteBuffer.array(), 0, readBytes);
                            totalBytesSent += readBytes;
                            FileTransferForegroundService.lastPosition = totalBytesSent;

                            // Regularly throttle status broadcasts to prevent UI thread choke
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastNotificationUpdateTime >= 500) {
                                int percent = (int) ((totalBytesSent * 100) / fileSize);
                                broadcastProgress(fileName, totalBytesSent, fileSize, percent, i);
                                lastNotificationUpdateTime = currentTime;
                            }
                        }

                        // Finalize this LZ4 compressed file block segment without closing the socket!
                        lz4Out.flush();
                        lz4Out.finish();

                        if (!FileTransferForegroundService.isCancelled) {
                            WifeLogger.log(TAG, "File successfully streamed: " + fileName);
                            
                            // Insert database transfer record
                            FileEntity entity = new FileEntity(fileName, fileSize, fileUri.toString(), System.currentTimeMillis());
                            RoomDatabaseManager.getInstance(context).fileDao().insert(entity);
                            
                            // Reset file resume position tracking for next queue file
                            FileTransferForegroundService.lastPosition = 0;
                            broadcastProgress(fileName, fileSize, fileSize, 100, i);
                        }
                    }
                }

                // 3. Write transmission finished goodbye signal (Metadata length = 0)
                if (!FileTransferForegroundService.isCancelled) {
                    ByteBuffer goodbyeBuf = ByteBuffer.allocate(4).putInt(0);
                    goodbyeBuf.flip();
                    while (goodbyeBuf.hasRemaining()) {
                        socketChannel.write(goodbyeBuf);
                    }
                    socketOs.flush();
                    WifeLogger.log(TAG, "All queue files sent successfully. Sent persistent stream finished marker.");
                    broadcastCompletion();
                }

            } catch (Exception e) {
                WifeLogger.log(TAG, "Persistent file sending pipeline threw fatal exception: " + e.getMessage(), e);
                broadcastError(e.getMessage());
            } finally {
                // Symmetrical clean up of channel streams
                try {
                    if (socketChannel != null && socketChannel.isOpen()) {
                        socketChannel.close();
                    }
                } catch (IOException ignored) {}
                
                // Stop foreground service context cleanly
                Intent stopIntent = new Intent(context, FileTransferForegroundService.class);
                context.stopService(stopIntent);
            }
        });
    }

    private ByteBuffer metadataBuffer(byte[] metaBytes) {
        return ByteBuffer.wrap(metaBytes);
    }

    private void broadcastProgress(String fileName, long transferred, long total, int percent, int fileIndex) {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_PROGRESS);
        intent.putExtra(Constants.EXTRA_FILE_NAME, fileName);
        intent.putExtra(Constants.EXTRA_BYTES_TRANSFERRED, transferred);
        intent.putExtra(Constants.EXTRA_TOTAL_BYTES, total);
        intent.putExtra(Constants.EXTRA_FILE_INDEX, fileIndex);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

        // Update foreground service notification in parallel
        Intent serviceIntent = new Intent(context, FileTransferForegroundService.class);
        serviceIntent.setAction("UPDATE_NOTIF");
        serviceIntent.putExtra("NOTIF_TEXT", "Sending " + fileName + " (" + percent + "%)");
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
}