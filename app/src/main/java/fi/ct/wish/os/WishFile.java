/**
 * Copyright (C) 2020, ControlThings Oy Ab
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * @license Apache-2.0
 */
package fi.ct.wish.os;

import android.content.Context;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;


/**
 * This class aims at creating a minimal "posix"-looking file system operations layer using the Java FileInputStream and FileOutputStream.
 * The open, close, read, write, seek should work like their Unix system-call counterparts, at least to the extent that we need for Wish.
 *
 * Under this scheme, files are represented by "fileIDs", which are actually just integer id numbers to internal maps containg the state of each open file.
 * "fileIDs" and Unix file descriptors can be likened with each other in this context.
 * Note that wish_file_t is also defined to a (C) int32_t in wish_fs.h, so the definition is compatible as C int32_t and Java ints have equal size.
 *
 * Created by jeppe on 6/30/16.
 *
 */
public class WishFile {
    private final String TAG = "WishFile";

    Context _context;
    int latestFileID = 1;

    /** A map providing a mapping between fileIDs (plain integers) and positions within the file to represent open files */
    private Map<Integer, Long> filePositions = new HashMap<Integer, Long>();
    /** Mapping between fileIDs and filenames */
    private Map<Integer, String> fileNames = new HashMap<Integer, String>();

    public WishFile(Context context) {
        this._context = context;
    }

    private final int openMode = Context.MODE_APPEND;

    public int open(String filename) {

        FileOutputStream outputStream;
        try {
            outputStream = _context.openFileOutput(filename, openMode);
            /* Ensure that we open the file positioned at the begining */
            outputStream.getChannel().position(0);

            /* FIXME fileId allocation should be more clever, instead of just incrementing the fileId.
            For example, we could keep track of the latest fileId assigned, and then start from the lowest possible file ID searching for a free fileId.
             */
            filePositions.put(latestFileID, outputStream.getChannel().position());
            fileNames.put(latestFileID, filename);
            outputStream.close();
            //Log.d(TAG, "Opening file " + filename + " -> id " + latestFileID);

            return latestFileID++;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    public int close(int fileID) {
        //Log.d(TAG, "Closing file id " + fileID);
        if (filePositions.containsKey(fileID) && fileNames.containsKey(fileID)) {
            filePositions.remove(fileID);
            fileNames.remove(fileID);
            return 0;
        }
        else {
            return -1;
        }
    }



    public int write(int fileID, byte[] data) {
        FileOutputStream outputStream;
        //Log.d(TAG, "Writing to file name " + fileNames.get(fileID) + " id " + fileID + " data len " + data.length);
        try {
            if(checkValidId(fileID) == false) {
                throw new Exception("Bad fileId!");
            }
            outputStream = _context.openFileOutput(fileNames.get(fileID), openMode);
            FileChannel channel = outputStream.getChannel();
            channel.position(filePositions.get(fileID));
            //Log.d(TAG, "Write channel position before: " + channel.position());

            outputStream.write(data);

            long newPosition = outputStream.getChannel().position();
            filePositions.put(fileID, newPosition);
            //Log.d(TAG, "Write channel position after: " + channel.position());
            outputStream.close();
            return data.length;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    public int read(int fileId, byte[] data, int count) {
        FileInputStream inputStream;
        //Log.d(TAG, "Read from file name " + fileNames.get(fileId) + " id " + fileId + " len " + count);
        try {
            if(checkValidId(fileId) == false) {
                throw new Exception("Bad fileId!");
            }
            inputStream = _context.openFileInput(fileNames.get(fileId));
            FileChannel channel = inputStream.getChannel();

            channel.position(filePositions.get(fileId));
            //Log.d(TAG, "Read channel position before: " + channel.position());

            int numBytesRead = inputStream.read(data, 0, count);

            if (numBytesRead == -1) {
                //Log.d(TAG, "Read: EOF");
                /* End of file detected */
                /* Do NOT move stored file position */
                inputStream.close();
                return 0;
            }
            else {
                //Log.d(TAG, "Read " + numBytesRead + " bytes");
                long newPosition = inputStream.getChannel().position();
                filePositions.put(fileId, newPosition);
                //Log.d(TAG, "Read channel position after: " + channel.position());
                inputStream.close();
                return numBytesRead;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    /** seek method parameter 'whence' for setting absolute position. Must be equal to WISH_FS_SEEK_SET wish_fs.h */
    public final int SEEK_SET = 0;
    /** seek method parameter 'whence' for setting position relative to current position. Must be equal to WISH_FS_SEEK_CUR wish_fs.h */
    public final int SEEK_CUR = 1;
    /** seek method parameter 'whence' for setting position relative to end of file. Must be equal to WISH_FS_SEEK_END in wish_fs.h */
    public final int SEEK_END = 2;

    public long seek(int fileId, int offset, int whence) {

        //Log.d(TAG, "Seeking file id " + fileId + " offset " + offset + " whence " + whence);
        try {
            if(checkValidId(fileId) == false) {
                throw new Exception("Bad fileId!");
            }
            switch (whence) {
                case SEEK_SET:
                    /* Seek to absolute position in file */
                    filePositions.put(fileId, (long) offset);
                    break;
                case SEEK_CUR:
                    /* Seek to a position relative to the current position in the file */
                    long currentOffset = filePositions.get(fileId);
                    filePositions.put(fileId, currentOffset + offset);
                    break;
                case SEEK_END:
                     /* Seek to a position relative to the end of file (x bytes past the end of file) */
                    FileInputStream inputStream = _context.openFileInput(fileNames.get(fileId));
                    FileChannel channel = inputStream.getChannel();

                    long sizeOfFile = channel.size();
                    filePositions.put(fileId, sizeOfFile + offset);
                    inputStream.close();
                    break;
            }
            return filePositions.get(fileId);
        }
        catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    /**
     * Check the validity of a fileId. A file id is valid, if it exists in the internal mappings.
     * @param fileId the fileId to check.
     * @return true, if the fileId is valid
     */
    private boolean checkValidId(int fileId) {
        if (filePositions.containsKey(fileId) && fileNames.containsKey(fileId)) {
            return true;
        }
        return false;
    }

    /**
     * Rename a file.
     *
     * @param oldName the old name
     * @param newName the new name
     * @return 0 on success, -1 on fail
     */
    public int rename(String oldName, String newName) {
        File oldFile = _context.getFileStreamPath(oldName);
        File newFile = _context.getFileStreamPath(newName);
        int retval = -1;
        if (oldFile.renameTo(newFile)) {
            retval = 0;
        }
        else {
            Log.d(TAG, "Error renaming file!");
            retval = -1;
        }

        return retval;
    }

    public int remove(String fileName) {
        File file = _context.getFileStreamPath(fileName);
        int retval = -1;
        if (file.delete()) {
            retval = 0;
        }
        return retval;
    }
}
