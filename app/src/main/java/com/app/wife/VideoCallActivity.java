package com.wife.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.wife.app.databinding.ActivityVideoCallBinding;
import com.wife.app.VideoDecoderManager.VideoFrameListener;
import com.google.gson.JsonObject;

public class VideoCallActivity extends AppCompatActivity implements 
        CallSignalingManager.SignalingEventListener, 
        VideoFrameListener,
        VideoCaptureManager.LocalFrameListener {

    private static final String TAG = "VideoCallActivity";

    private ActivityVideoCallBinding binding;
    private VideoCallManager videoCallManager;
    private String peerIp;
    private String peerName;
    private boolean isInbound;
    private long callStartTime;

    // Track which stream is currently enlarged (false: peer full screen, true: local full screen)
    private boolean isLocalVideoFull = false;

    private MediaPlayer ringtonePlayer;
    private Vibrator vibratorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Configure window flags to wake the screen backlight, keep it on, and bypass locks
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED 
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        binding = ActivityVideoCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WifeLogger.log(TAG, "onCreate() invoked. Initializing VideoCallActivity components.");

        videoCallManager = VideoCallManager.getInstance(this);
        vibratorService = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        peerIp = getIntent().getStringExtra(Constants.EXTRA_PEER_IP);
        peerName = getIntent().getStringExtra(Constants.EXTRA_PEER_NAME);
        isInbound = getIntent().getBooleanExtra("IS_INBOUND", false);

        if (peerIp == null || peerIp.isEmpty()) {
            peerIp = ConnectionManager.getInstance(this).getPeerIpAddress();
        }
        if (peerName == null || peerName.isEmpty()) {
            peerName = "Video Peer";
        }

        WifeLogger.log(TAG, "Target Peer IP: " + peerIp + " | Peer Name: " + peerName + " | Inbound Call Direction: " + isInbound);

        binding.tvVideoPeerName.setText(peerName);

        // Bind our local camera listener to stream real-time local previews into our PIP view
        VideoCaptureManager.setLocalFrameListener(this);

        setupClickListeners();
        configureCallingState();

        // Start playing ringtone if it's an incoming call
        if (isInbound) {
            startRingtone();
        }
    }

    private void startRingtone() {
        WifeLogger.log(TAG, "Checking device system audio configuration profile.");
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int ringerMode = audioManager.getRingerMode();
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                WifeLogger.log(TAG, "Device profile is SILENT. Suppressing incoming video call ringtone playback (Glitch 6 Fix).");
                return;
            } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                WifeLogger.log(TAG, "Device profile is VIBRATE. Suppressing ringtone, starting vibration (Glitch 6 Fix).");
                if (vibratorService != null && vibratorService.hasVibrator()) {
                    long[] pattern = {0, 600, 800}; // Vibrate 600ms, pause 800ms
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibratorService.vibrate(VibrationEffect.createWaveform(pattern, 0)); // Loop from index 0
                    } else {
                        vibratorService.vibrate(pattern, 0);
                    }
                }
                return;
            }
        }

        WifeLogger.log(TAG, "Initializing incoming video call ringtone playback.");
        try {
            ringtonePlayer = MediaPlayer.create(this, R.raw.wife_ringtone);
            if (ringtonePlayer != null) {
                ringtonePlayer.setLooping(true);
                ringtonePlayer.start();
                WifeLogger.log(TAG, "Branded ringtone is now playing.");
            } else {
                WifeLogger.log(TAG, "Failed creating MediaPlayer instance for wife_ringtone.");
            }
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error playing custom branded ringtone: " + e.getMessage(), e);
        }
    }

    private void stopRingtone() {
        if (vibratorService != null) {
            WifeLogger.log(TAG, "Halting active vibrator channels.");
            vibratorService.cancel();
        }
        if (ringtonePlayer != null) {
            try {
                WifeLogger.log(TAG, "Stopping and releasing local ringtone MediaPlayer.");
                if (ringtonePlayer.isPlaying()) {
                    ringtonePlayer.stop();
                }
                ringtonePlayer.release();
            } catch (Exception e) {
                WifeLogger.log(TAG, "Error cleanly releasing ringtone player: " + e.getMessage(), e);
            }
            ringtonePlayer = null;
        }
    }

    private void setupClickListeners() {
        binding.fabSwitchCamera.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User triggered Switch Camera button. Rotating active lens.");
            videoCallManager.switchCamera(this);
            Toast.makeText(this, "Flipped focus", Toast.LENGTH_SHORT).show();
        });

        binding.fabAcceptVideo.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User accepted inbound video invitation.");
            acceptCall();
        });

        binding.fabDeclineVideo.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User declined/ended active video call session.");
            declineOrEndCall();
        });

        // Set click listener on the PIP Card to swap views (local vs remote)
        binding.cardSelfMirror.setOnClickListener(v -> {
            isLocalVideoFull = !isLocalVideoFull;
            WifeLogger.log(TAG, "Self-mirror PIP container tapped. Swapped display state: Local full screen = " + isLocalVideoFull);
            Toast.makeText(this, isLocalVideoFull ? "Local preview enlarged" : "Remote preview enlarged", Toast.LENGTH_SHORT).show();
        });
    }

    private void configureCallingState() {
        WifeLogger.log(TAG, "Configuring Call Signaling Manager. Registering Activity listener.");
        CallSignalingManager.getInstance(this).registerListener(this);

        if (isInbound) {
            WifeLogger.log(TAG, "Call State configured as: INBOUND_REQUEST.");
            binding.tvVideoCallState.setText("Inbound Video Request...");
            binding.fabAcceptVideo.setVisibility(View.VISIBLE);
        } else {
            WifeLogger.log(TAG, "Call State configured as: OUTBOUND_CALL. Dispatching signaling request to: " + peerIp);
            binding.tvVideoCallState.setText("Negotiating Link...");
            binding.fabAcceptVideo.setVisibility(View.GONE);
            // Send request over control signal
            CallSignalingManager.getInstance(this).sendSignal(peerIp, Constants.SIGNAL_VIDEO_REQUEST);
        }
    }

    private void acceptCall() {
        stopRingtone();
        binding.fabAcceptVideo.setVisibility(View.GONE);
        binding.tvVideoCallState.setText("Opening Video channel...");

        // Alert initiator
        WifeLogger.log(TAG, "Accepting Call. Relaying accept signaling packet to: " + peerIp);
        CallSignalingManager.getInstance(this).sendSignal(peerIp, Constants.SIGNAL_VIDEO_ACCEPT);

        // Parallel audio: Listen for incoming audio stream
        WifeLogger.log(TAG, "Concurrently starting incoming audio listener on Port: " + Constants.OFF_PORT_VOICE);
        VoiceCallManager.getInstance(this).listenForIncomingCall();

        // Bind incoming stream
        WifeLogger.log(TAG, "Concurrently starting incoming video listener on Port: " + Constants.OFF_PORT_VIDEO);
        videoCallManager.listenForIncomingCall(this, this);
        startCallService();
    }

    private void declineOrEndCall() {
        stopRingtone();
        WifeLogger.log(TAG, "declineOrEndCall() invoked. Processing signal termination...");
        if (isInbound && binding.fabAcceptVideo.setVisibility() == View.VISIBLE) {
            WifeLogger.log(TAG, "Call declined before connection. Dispatching REJECT signal to: " + peerIp);
            CallSignalingManager.getInstance(this).sendSignal(peerIp, Constants.SIGNAL_VIDEO_REJECT);
        } else {
            WifeLogger.log(TAG, "Call ended during active stream. Dispatching END signal to: " + peerIp);
            CallSignalingManager.getInstance(this).sendSignal(peerIp, Constants.SIGNAL_VIDEO_END);
        }
        hangUp();
    }

    private void startCallService() {
        WifeLogger.log(TAG, "Starting VideoCallForegroundService foreground worker.");
        Intent serviceIntent = new Intent(this, VideoCallForegroundService.class);
        startService(serviceIntent);

        binding.tvVideoCallState.setText("Mesh Streaming Live");
        callStartTime = System.currentTimeMillis();
    }

    private void hangUp() {
        WifeLogger.log(TAG, "hangUp() invoked. Stopping services and releasing active calling managers.");
        stopRingtone();
        stopCallService();

        // Parallel audio: Terminate the parallel voice pipeline
        WifeLogger.log(TAG, "Concurrently halting concurrent voice call pipelines.");
        VoiceCallManager.getInstance(this).endCall();

        WifeLogger.log(TAG, "Halting video call pipelines.");
        videoCallManager.endCall();

        // Calculate call duration
        long duration = 0;
        if (callStartTime > 0) {
            duration = (System.currentTimeMillis() - callStartTime) / 1000;
        }
        WifeLogger.log(TAG, "Calculated call duration: " + duration + " seconds. Inserting log record to Call Dao.");

        // Save Call Record in DB
        CallEntity entity = new CallEntity(peerName, "Video", duration, System.currentTimeMillis());
        try {
            RoomDatabaseManager.getInstance(this).callDao().insert(entity);
            WifeLogger.log(TAG, "Call duration log record written successfully to SQLite DB.");
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed writing call log record to database: " + e.getMessage(), e);
        }

        WifeLogger.log(TAG, "Finalizing VideoCallActivity.");
        finish();
    }

    private void stopCallService() {
        WifeLogger.log(TAG, "stopCallService() invoked. Unregistering SignalingEventListener and terminating foreground service.");
        CallSignalingManager.getInstance(this).unregisterListener(this);
        stopService(new Intent(this, VideoCallForegroundService.class));
    }

    @Override
    public void onSignalReceived(String action, String senderIp, JsonObject payload) {
        WifeLogger.log(TAG, "onSignalReceived callback triggered. Action: " + action + " | Sender IP: " + senderIp);
        runOnUiThread(() -> {
            switch (action) {
                case Constants.SIGNAL_VIDEO_ACCEPT:
                    stopRingtone();
                    WifeLogger.log(TAG, "Signal matched: ACCEPT. Starting outbound audio/video stream parameters.");
                    binding.tvVideoCallState.setText("Streaming Active");

                    // Symmetrical lookup to resolve Glitch 1: Extract the receiver's customized profile name
                    if (payload != null && payload.has("senderName")) {
                        peerName = payload.get("senderName").getAsString();
                        binding.tvVideoPeerName.setText(peerName);
                        WifeLogger.log(TAG, "Updated peer display name on caller side to: " + peerName);
                    }
                    
                    // Parallel audio: Start outbound audio stream
                    WifeLogger.log(TAG, "Concurrently starting outbound audio client connection to: " + peerIp);
                    VoiceCallManager.getInstance(VideoCallActivity.this).startCall(peerIp);

                    videoCallManager.startCall(this, peerIp, this);
                    startCallService();
                    break;
                case Constants.SIGNAL_VIDEO_REJECT:
                    stopRingtone();
                    WifeLogger.log(TAG, "Signal matched: REJECT. Teardown active activity context.");
                    Toast.makeText(this, "Video invitation rejected.", Toast.LENGTH_SHORT).show();
                    hangUp();
                    break;
                case Constants.SIGNAL_VIDEO_END:
                    stopRingtone();
                    WifeLogger.log(TAG, "Signal matched: END. Teardown active activity context.");
                    Toast.makeText(this, "Video channel terminated.", Toast.LENGTH_SHORT).show();
                    hangUp();
                    break;
            }
        });
    }

    @Override
    public void onFrameReceived(Bitmap bitmap) {
        runOnUiThread(() -> {
            if (bitmap != null) {
                if (isLocalVideoFull) {
                    // If local stream is full-screen, the peer stream is routed to the PIP view
                    binding.ivLocalVideo.setImageBitmap(bitmap);
                } else {
                    // Standard routing: remote stream is background
                    binding.ivPeerVideo.setImageBitmap(bitmap);
                }
            }
        });
    }

    @Override
    public void onLocalFrameCaptured(Bitmap bitmap) {
        runOnUiThread(() -> {
            if (bitmap != null) {
                if (isLocalVideoFull) {
                    // If local stream is full-screen, local preview is routed to the full background
                    binding.ivPeerVideo.setImageBitmap(bitmap);
                } else {
                    // Standard routing: local preview is routed to the PIP view
                    binding.ivLocalVideo.setImageBitmap(bitmap);
                }
            }
        });
    }

    @Override
    public void onDecoderError(String error) {
        WifeLogger.log(TAG, "Decoder error received from VideoDecoderManager: " + error);
        runOnUiThread(() -> {
            Toast.makeText(this, "Decorder synchronization error: " + error, Toast.LENGTH_SHORT).show();
            hangUp();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WifeLogger.log(TAG, "onDestroy() invoked. Cleaning up static LocalFrameListener handles and releasing media players.");
        stopRingtone();
        VideoCaptureManager.setLocalFrameListener(null);
    }

    @Override
    public void onBackPressed() {
        WifeLogger.log(TAG, "User triggered native system back button press.");
        declineOrEndCall();
        super.onBackPressed();
    }
}