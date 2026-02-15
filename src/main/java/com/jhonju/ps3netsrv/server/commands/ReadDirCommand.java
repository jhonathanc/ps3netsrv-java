package com.jhonju.ps3netsrv.server.commands;

import com.jhonju.ps3netsrv.server.Context;
import com.jhonju.ps3netsrv.server.exceptions.PS3NetSrvException;
import com.jhonju.ps3netsrv.server.io.IFile;
import com.jhonju.ps3netsrv.server.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ReadDirCommand extends AbstractCommand {
    private static final long MAX_ENTRIES = 4096;
    private static final short MAX_FILE_NAME_LENGTH = 512;
    private static final int READ_DIR_ENTRY_LENGTH = 529;

    public ReadDirCommand(Context ctx) {
        super(ctx);
    }

    // ... ReadDirResult and ReadDirEntry classes ...
    private static class ReadDirResult implements IResult {
        private final List<ReadDirEntry> entries;

        public ReadDirResult(List<ReadDirEntry> entries) {
            this.entries = entries;
        }

        public byte[] toByteArray() throws IOException {
            if (entries != null) {
                try (ByteArrayOutputStream out = new ByteArrayOutputStream(
                        entries.size() * READ_DIR_ENTRY_LENGTH + Utils.LONG_CAPACITY)) {
                    out.write(Utils.longToBytesBE(entries.size()));
                    for (ReadDirEntry entry : entries) {
                        out.write(entry.toByteArray());
                    }
                    return out.toByteArray();
                }
            }
            return null;
        }
    }

    public static class ReadDirEntry {
        public final long aFileSize;
        public final long bModifiedTime;
        public final boolean cIsDirectory;
        public final String dFileName;

        public ReadDirEntry(long fileSize, long modifiedTime, boolean isDirectory, String name) {
            this.aFileSize = fileSize;
            this.bModifiedTime = modifiedTime;
            this.cIsDirectory = isDirectory;
            this.dFileName = name;
        }

        public byte[] toByteArray() throws IOException {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream(READ_DIR_ENTRY_LENGTH)) {
                out.write(Utils.longToBytesBE(this.aFileSize));
                out.write(Utils.longToBytesBE(this.bModifiedTime));
                out.write(cIsDirectory ? 1 : 0);
                char[] nameChars = new char[MAX_FILE_NAME_LENGTH];
                int length = Math.min(dFileName.length(), MAX_FILE_NAME_LENGTH);
                for (int i = 0; i < length; i++) {
                    nameChars[i] = dFileName.charAt(i);
                }
                out.write(Utils.charArrayToByteArray(nameChars));
                return out.toByteArray();
            }
        }
    }

    @Override
    public void executeTask() throws IOException, PS3NetSrvException {
        String clientPath = ctx.getClientPath();
        if (clientPath == null) {
            send(Utils.longToBytesBE(EMPTY_SIZE));
            return;
        }

        List<IFile> matchingDirs = ctx.getPathResolver().resolveAllForDir(clientPath);
        if (matchingDirs.isEmpty()) {
            send(Utils.longToBytesBE(EMPTY_SIZE));
            ctx.setDirectoryEntries(null);
        } else {
            List<ReadDirEntry> entries = new ArrayList<>();
            Set<String> processedNames = new HashSet<>();

            for (IFile dir : matchingDirs) {
                if (dir.exists() && dir.isDirectory()) {
                    IFile[] files = dir.listFiles();
                    if (files != null) {
                        for (IFile f : files) {
                            if (entries.size() == MAX_ENTRIES)
                                break;
                            if (processedNames.contains(f.getName()))
                                continue;

                            entries.add(new ReadDirEntry(f.isDirectory() ? EMPTY_SIZE : f.length(),
                                    f.lastModified() / MILLISECONDS_IN_SECOND, f.isDirectory(), f.getName()));
                            processedNames.add(f.getName());
                        }
                    }
                }
                if (entries.size() == MAX_ENTRIES)
                    break;
            }
            send(new ReadDirResult(entries));
            ctx.setDirectoryEntries(entries);
        }
        ctx.setFile(null);
    }
}
