package com.wife.app;

import android.content.Context;
import android.util.Log;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketClient {
    private static final String TAG = "SocketClient";

    private final Context context;
    private final InetAddress hostAddress;
    private final ConnectionManager connectionManager;
    private final ExecutorService executorService;

    public SocketClient(Context context, InetAddress hostAddress, ConnectionManager connectionManager) {
        this.context = context;
        this.hostAddress = hostAddress;
        this.connectionManager = connectionManager;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public void start() {
        Log.d(TAG, "SocketClient initialized with Host: " + hostAddress.getHostAddress());
        WifeLogger.log(TAG, "start() invoked. SocketClient starting up. Targeted Host IP Address: " + hostAddress.getHostAddress());
        
        // Probe connection with a dummy heartbeat/handshake to let the Host learn our local IP
        String handshake = "{\"type\":\"handshake\",\"sender\":\"" + Utils.getDeviceId(context) + "\"}";
        WifeLogger.log(TAG, "Transmitting baseline startup handshake probe payload: " + handshake);
        sendControlMessage(handshake);
    }

    public void close() {
        WifeLogger.log(TAG, "close() invoked. Terminating SocketClient background executor threads.");
        executorService.shutdownNow();
        Log.d(TAG, "SocketClient shut down.");
        WifeLogger.log(TAG, "SocketClient execution loops finalized.");
    }

    public void sendControlMessage(final String jsonPayload) {
        WifeLogger.log(TAG, "sendControlMessage() queued for background execution. Payload: " + jsonPayload);
        executorService.execute(() -> {
            WifeLogger.log(TAG, "Initiating Control Socket connection to Host: " + hostAddress.getHostAddress() + " on Port: " + Constants.OFF_PORT_CONTROL);
            try (Socket socket = new Socket(hostAddress, Constants.OFF_PORT_CONTROL);
                 OutputStream os = socket.getOutputStream();
                 PrintWriter pw = new PrintWriter(os, true)) {
                
                WifeLogger.log(TAG, "Control Socket connected successfully. Writing payload...");
                pw.println(jsonPayload);
                pw.flush();
                Log.d(TAG, "Successfully sent control packet: " + jsonPayload);
                WifeLogger.log(TAG, "Control packet written and output stream flushed successfully.");
            } catch (Exception e) {
                Log.e(TAG, "Error sending control packet: " + e.getMessage());
                WifeLogger.log(TAG, "Failed sending Control Socket packet to " + hostAddress.getHostAddress() + " on Port " + Constants.OFF_PORT_CONTROL + " | Exception: " + e.getMessage(), e);
            }
        });
    }

    public void sendTextMessage(final String jsonPayload) {
        WifeLogger.log(TAG, "sendTextMessage() queued for background execution. Payload: " + jsonPayload);
        executorService.execute(() -> {
            WifeLogger.log(TAG, "Initiating Text Socket connection to Host: " + hostAddress.getHostAddress() + " on Port: " + Constants.OFF_PORT_TEXT);
            try (Socket socket = new Socket(hostAddress, Constants.OFF_PORT_TEXT);
                 OutputStream os = socket.getOutputStream();
                 PrintWriter pw = new PrintWriter(os, true)) {
                
                WifeLogger.log(TAG, "Text Socket connected successfully. Writing payload...");
                pw.println(jsonPayload);
                pw.flush();
                Log.d(TAG, "Successfully sent message packet: " + jsonPayload);
                WifeLogger.log(TAG, "Text packet written and output stream flushed successfully.");
            } catch (Exception e) {
                Log.e(TAG, "Error sending message packet: " + e.getMessage());
                WifeLogger.log(TAG, "Failed sending Text Socket packet to " + hostAddress.getHostAddress() + " on Port " + Constants.OFF_PORT_TEXT + " | Exception: " + e.getMessage(), e);
            }
        });
    }
}