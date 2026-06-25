package com.wife.app;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

public final class FileJoiner {
    private static final String TAG = "FileJoiner";

    private FileJoiner() {
        // Prevent instantiation of utility class
    }

    /**
     * Merges parallel raw temporary chunk parts sequentially into a single target file 
     * using high-speed zero-copy kernel-level FileChannels (Glitch 1 & ENOSPC Fix).
     *
     * @param context     The application context to access directory boundaries.
     * @param fileId      The unique transaction identifier matching the chunks.
     * @param totalChunks The aggregate count of chunks expected for this file.
     * @param destFile    The final target destination File descriptor.
     * @return true if the chunks were successfully merged and cleaned up; false on any I/O failure.
     */
    public static boolean mergeParts(Context context, String fileId, int totalChunks, File destFile) {
        WifeLogger.log(TAG, "mergeParts() invoked. Merging " + totalChunks + " chunks into: " + destFile.getAbsolutePath());
        
        File parentDir = destFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        File backupDir = getBackupDirectory();

        try (FileOutputStream fos = new FileOutputStream(destFile);
             FileChannel outChannel = fos.getChannel()) {
            
            long position = 0;
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                // Resolved ENOSPC crash: Pointing directly to the spacious external backups target folder
                File tempRawChunk = new File(backupDir, "chunk_" + fileId + "_" + chunkIndex + ".raw");
                if (!tempRawChunk.exists()) {
                    throw new IOException("Missing raw part file segment at chunk index: " + chunkIndex);
                }

                try (FileInputStream fis = new FileInputStream(tempRawChunk);
                     FileChannel inChannel = fis.getChannel()) {
                    
                    long bytesTransferred = 0;
                    long size = inChannel.size();
                    
                    // High-speed kernel-level zero-copy transfer loop
                    while (bytesTransferred < size) {
                        long transferred = inChannel.transferTo(bytesTransferred, size - bytesTransferred, outChannel);
                        if (transferred <= 0) {
                            break;
                        }
                        bytesTransferred += transferred;
                    }
                    position += bytesTransferred;
                }
                
                // Symmetrical instantly purge raw segment file to preserve device storage (ENOSPC fix)
                boolean deleted = tempRawChunk.delete();
                WifeLogger.log(TAG, "Purged temporary raw segment index: " + chunkIndex + " | Deleted: " + deleted);
            }
            
            WifeLogger.log(TAG, "NIO parallel chunk merge completed successfully. Merged Size: " + destFile.length() + " bytes.");
            return true;
        } catch (IOException e) {
            WifeLogger.log(TAG, "NIO parallel chunk merge failed with exception: " + e.getMessage(), e);
            return false;
        }
    }

    private static File getBackupDirectory() {
        File rootDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "wife shared/backups");
        } else {
            rootDir = new File(Environment.getExternalStorageDirectory(), "wife shared/backups");
        }
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
        return rootDir;
    }
}