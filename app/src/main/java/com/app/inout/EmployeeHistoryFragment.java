package com.inout.app;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.inout.app.adapters.AttendanceAdapter;
import com.inout.app.databinding.FragmentEmployeeHistoryBinding;
import com.inout.app.models.AttendanceRecord;
import com.inout.app.models.User;
import com.inout.app.utils.EncryptionHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Fragment for Employees to view their own personal attendance history.
 * UPDATED: Displays updated 14-column logic including Emergency Leave remarks.
 */
public class EmployeeHistoryFragment extends Fragment {

    private static final String TAG = "EmployeeHistoryFrag";
    private FragmentEmployeeHistoryBinding binding;
    
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    
    private List<AttendanceRecord> historyLogs;
    private AttendanceAdapter adapter;
    private String employeeId;
    private User currentUserProfile;

    // Track active listener to prevent background leaks and NPE crashes [2]
    private ListenerRegistration logsListenerRegistration;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentEmployeeHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();
        historyLogs = new ArrayList<>();

        setupRecyclerView();
        fetchEmployeeIdAndLoadLogs();

        // Standard CSV Export Trigger [2]
        binding.btnExportHistory.setOnClickListener(v -> {
            if (historyLogs != null && !historyLogs.isEmpty() && currentUserProfile != null) {
                String fileName = "My_Attendance_" + new SimpleDateFormat("MMM_yyyy", Locale.US).format(new Date());
                CsvExportHelper.exportAttendanceToCsv(requireContext(), historyLogs, fileName);
            } else {
                Toast.makeText(getContext(), "No history to export.", Toast.LENGTH_SHORT).show();
            }
        });

        // New: PDF Export Trigger linking to the dynamic landscape compiler [2]
        binding.btnExportPdfHistory.setOnClickListener(v -> {
            if (historyLogs != null && !historyLogs.isEmpty() && currentUserProfile != null) {
                String fileName = "My_Attendance_" + new SimpleDateFormat("MMM_yyyy", Locale.US).format(new Date());
                PdfExportHelper.exportAttendanceToPdf(requireContext(), currentUserProfile, historyLogs, fileName);
            } else {
                Toast.makeText(getContext(), "No history to export.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupRecyclerView() {
        binding.rvHistoryTable.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AttendanceAdapter(historyLogs);
        binding.rvHistoryTable.setAdapter(adapter);
    }

    private void fetchEmployeeIdAndLoadLogs() {
        if (mAuth.getCurrentUser() == null) return;
        
        String uid = mAuth.getCurrentUser().getUid();
        binding.progressBar.setVisibility(View.VISIBLE);

        db.collection("users").document(uid).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        currentUserProfile = documentSnapshot.toObject(User.class);
                        if (currentUserProfile != null && currentUserProfile.getEmployeeId() != null) {
                            this.employeeId = currentUserProfile.getEmployeeId();
                            
                            binding.tvHistoryName.setText(currentUserProfile.getName());
                            binding.tvHistoryId.setText("ID: " + this.employeeId);
                            
                            String company = EncryptionHelper.getInstance(requireContext()).getCompanyName();
                            binding.tvHistoryCompany.setText(company);

                            binding.tvHistoryMonth.setText(new SimpleDateFormat("MMMM yyyy", Locale.US).format(new Date()));

                            if (currentUserProfile.getPhotoUrl() != null) {
                                Glide.with(this).load(currentUserProfile.getPhotoUrl()).circleCrop().into(binding.ivHistoryPhoto);
                            }
                            
                            loadMyLogs();
                        } else {
                            binding.progressBar.setVisibility(View.GONE);
                            binding.tvNoData.setText("Employee ID not assigned yet.");
                            binding.tvNoData.setVisibility(View.VISIBLE);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    binding.progressBar.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Failed to load profile.", Toast.LENGTH_SHORT).show();
                });
    }

    private void loadMyLogs() {
        logsListenerRegistration = db.collection("attendance")
                .whereEqualTo("employeeId", employeeId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener((value, error) -> {
                    // Safety check to verify view still exists before updating progress loader [2]
                    if (binding == null) return;
                    
                    binding.progressBar.setVisibility(View.GONE);
                    
                    if (error != null) {
                        Log.e(TAG, "Error listening for history logs", error);
                        return;
                    }

                    if (value != null) {
                        historyLogs.clear();
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                        SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE", Locale.US);

                        for (DocumentSnapshot doc : value) {
                            AttendanceRecord record = doc.toObject(AttendanceRecord.class);
                            if (record != null) {
                                try {
                                    Date date = sdf.parse(record.getDate());
                                    if (date != null) {
                                        record.setDayOfWeek(dayFormat.format(date));
                                    }
                                } catch (Exception e) {
                                    record.setDayOfWeek("Unknown");
                                }
                                historyLogs.add(record);
                            }
                        }
                        
                        adapter.notifyDataSetChanged();
                        
                        if (historyLogs.isEmpty()) {
                            binding.tvNoData.setVisibility(View.VISIBLE);
                            binding.tableHeader.getRoot().setVisibility(View.GONE);
                        } else {
                            binding.tvNoData.setVisibility(View.GONE);
                            binding.tableHeader.getRoot().setVisibility(View.VISIBLE);
                        }
                    }
                });
    }

    @Override
    public void onDestroyView() {
        // Safe lifecycle cleanup: Remove active listeners before clearing references [2]
        if (logsListenerRegistration != null) {
            logsListenerRegistration.remove();
            logsListenerRegistration = null;
        }
        super.onDestroyView();
        binding = null;
    }
}