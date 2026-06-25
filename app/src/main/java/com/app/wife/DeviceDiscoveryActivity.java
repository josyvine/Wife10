package com.wife.app;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.wife.app.databinding.ActivityDeviceDiscoveryBinding;

import java.util.ArrayList;
import java.util.List;

public class DeviceDiscoveryActivity extends AppCompatActivity implements 
        WiFiDirectManager.PeerChangeListener, 
        WiFiDirectManager.ConnectionChangeListener {

    private static final String TAG = "DeviceDiscoveryActivity";

    private ActivityDeviceDiscoveryBinding binding;
    private WiFiDirectManager wifiDirectManager;
    private DeviceAdapter adapter;
    private final List<WifiP2pDevice> peerList = new ArrayList<>();
    
    // State guard to prevent multi-selection or double-connection race conditions
    private boolean isConnecting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDeviceDiscoveryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WifeLogger.log(TAG, "onCreate() invoked. Initializing DeviceDiscoveryActivity.");

        wifiDirectManager = WiFiDirectManager.getInstance(this);

        setupToolbar();
        setupRecyclerView();

        binding.btnStartDiscovery.setOnClickListener(v -> {
            if (isConnecting) {
                WifeLogger.log(TAG, "Mesh scan click ignored: Connection attempt in progress.");
                return;
            }
            WifeLogger.log(TAG, "User triggered INITIATE MESH SCAN button. Starting peer discovery sweep.");
            binding.pbDiscoveryProgress.setVisibility(View.VISIBLE);
            wifiDirectManager.discoverPeers();
        });

        // Trigger an initial discovery sweep
        WifeLogger.log(TAG, "Launching initial automated peer discovery sweep on activity creation.");
        wifiDirectManager.discoverPeers();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarDiscovery);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbarDiscovery.setNavigationOnClickListener(v -> {
            WifeLogger.log(TAG, "Navigation back button clicked. Exiting DeviceDiscoveryActivity.");
            onBackPressed();
        });
    }

    private void setupRecyclerView() {
        WifeLogger.log(TAG, "Initializing DeviceAdapter and binding LayoutManager to RecyclerView.");
        adapter = new DeviceAdapter(peerList, device -> {
            if (isConnecting) {
                WifeLogger.log(TAG, "User interaction blocked: A connection attempt is already in progress.");
                return;
            }
            
            // Lock UI controls immediately to prevent race conditions
            isConnecting = true;
            binding.btnStartDiscovery.setEnabled(false);
            binding.rvDiscoveredDevices.setEnabled(false);

            String deviceDetails = "Name: " + device.deviceName + " | Mac: " + device.deviceAddress;
            WifeLogger.log(TAG, "User selected target device for connection. " + deviceDetails);
            Toast.makeText(this, "Connecting to " + device.deviceName + "...", Toast.LENGTH_SHORT).show();
            
            // Execute connection directly to prevent driver-level state machine locks (Reason: 0)
            executeConnection(device);
        });
        binding.rvDiscoveredDevices.setLayoutManager(new LinearLayoutManager(this));
        binding.rvDiscoveredDevices.setAdapter(adapter);
    }

    private void executeConnection(WifiP2pDevice device) {
        wifiDirectManager.connect(device, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                WifeLogger.log(TAG, "P2P connection request successfully queued by the system framework.");
                Toast.makeText(DeviceDiscoveryActivity.this, "Connection request posted successfully.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reason) {
                WifeLogger.log(TAG, "P2P connection request rejected by system framework. Reason Code: " + reason);
                
                // Unlock UI controls on failure
                isConnecting = false;
                binding.btnStartDiscovery.setEnabled(true);
                binding.rvDiscoveredDevices.setEnabled(true);

                // Defensive check: If reason is 0 (generic system error) but the group is already formed,
                // bypass the failure, notify the user, and return to the main dashboard.
                if (reason == 0 && wifiDirectManager.getConnectionInfo() != null && wifiDirectManager.getConnectionInfo().groupFormed) {
                    WifeLogger.log(TAG, "Bypassing Reason: 0 because the P2P connection group is already active under-the-hood. Redirecting to home.");
                    Toast.makeText(DeviceDiscoveryActivity.this, "P2P Network is already connected. Returning to home.", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(DeviceDiscoveryActivity.this, "Failed connecting. Reason: " + reason, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Dynamically add a Group Hosting option to the toolbar to resolve 1-to-N group limits
        menu.add(0, 1, 0, "Host Group")
            .setIcon(android.R.drawable.ic_menu_add)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            WifeLogger.log(TAG, "User triggered Host Group menu item. Pre-creating Autonomous P2P Group.");
            createAutonomousP2PGroup();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void createAutonomousP2PGroup() {
        Toast.makeText(this, "Pre-creating stable P2P Group AP...", Toast.LENGTH_SHORT).show();
        wifiDirectManager.createGroup(new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                WifeLogger.log(TAG, "Autonomous P2P Group pre-creation command posted successfully.");
                Toast.makeText(DeviceDiscoveryActivity.this, "Group pre-created. Clients can now join.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reason) {
                WifeLogger.log(TAG, "Autonomous P2P Group pre-creation failed. Reason: " + reason);
                Toast.makeText(DeviceDiscoveryActivity.this, "Group pre-creation failed. Reason: " + reason, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        WifeLogger.log(TAG, "onResume() called. Registering as PeerChangeListener and ConnectionChangeListener.");
        wifiDirectManager.registerPeerChangeListener(this);
        wifiDirectManager.registerConnectionChangeListener(this);
        
        // Populate current peer list immediately if available
        onPeersChanged(wifiDirectManager.getPeersList());
    }

    @Override
    protected void onPause() {
        super.onPause();
        WifeLogger.log(TAG, "onPause() called. Unregistering as PeerChangeListener and ConnectionChangeListener.");
        wifiDirectManager.unregisterPeerChangeListener(this);
        wifiDirectManager.unregisterConnectionChangeListener(this);
    }

    @Override
    public void onPeersChanged(List<WifiP2pDevice> peers) {
        if (isConnecting) return; // Ignore peer updates while actively connecting
        WifeLogger.log(TAG, "onPeersChanged() callback triggered. Discovered peers count: " + peers.size());
        binding.pbDiscoveryProgress.setVisibility(View.GONE);
        peerList.clear();
        peerList.addAll(peers);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onConnectionChanged(android.net.wifi.p2p.WifiP2pInfo info) {
        boolean groupFormed = info != null && info.groupFormed;
        WifeLogger.log(TAG, "onConnectionChanged() callback triggered. Group Formed Status: " + groupFormed);
        if (groupFormed) {
            isConnecting = false;
            Toast.makeText(this, "P2P Network Group Formed successfully!", Toast.LENGTH_SHORT).show();
            WifeLogger.log(TAG, "Wi-Fi P2P Group formed successfully. Terminating DeviceDiscoveryActivity and returning to main dashboard.");
            finish(); // Go back to Home Dashboard once connected, where detailed status will show
        } else {
            isConnecting = false;
            binding.btnStartDiscovery.setEnabled(true);
            binding.rvDiscoveredDevices.setEnabled(true);
            
            // Symmetrical State Clear: Reset local peer tracking lists when connection is not formed
            WifeLogger.log(TAG, "Connection lost or not formed in discovery view. Resetting local peer lists.");
            peerList.clear();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            // Symmetrical Refresh: Automatically initiate a fresh peer scan sweep
            WifeLogger.log(TAG, "Initiating post-disconnect peer discovery sweep.");
            wifiDirectManager.discoverPeers();
        }
    }
}