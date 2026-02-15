package com.jhonju.ps3netsrv.server.commands;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

import com.jhonju.ps3netsrv.server.Context;
import com.jhonju.ps3netsrv.server.exceptions.PS3NetSrvException;
import com.jhonju.ps3netsrv.server.io.FileCustom;
import com.jhonju.ps3netsrv.server.io.IFile;
import com.jhonju.ps3netsrv.server.utils.Utils;

public class StatFileCommand extends FileCommand {

    private static final int RESULT_LENGTH = 33;

    public StatFileCommand(Context ctx, short filePathLength) {
        super(ctx, filePathLength);
    }

    private static class StatFileResult implements IResult {
        public final long aFileSize;
        public final long bModifiedTime;
        public final long cCreationTime;
        public final long dLastAccessTime;
        public final boolean eIsDirectory;

        public StatFileResult(long fileSize, long modifiedTime, long creationTime, long lastAccessTime,
                boolean isDirectory) {
            this.aFileSize = fileSize;
            this.bModifiedTime = modifiedTime;
            this.cCreationTime = creationTime;
            this.dLastAccessTime = lastAccessTime;
            this.eIsDirectory = isDirectory;
        }

        public StatFileResult() {
            this.aFileSize = -1L;
            this.bModifiedTime = 0L;
            this.cCreationTime = 0L;
            this.dLastAccessTime = 0L;
            this.eIsDirectory = false;
        }

        public byte[] toByteArray() throws IOException {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream(RESULT_LENGTH)) {
                out.write(Utils.longToBytesBE(this.aFileSize));
                out.write(Utils.longToBytesBE(this.bModifiedTime));
                out.write(Utils.longToBytesBE(this.cCreationTime));
                out.write(Utils.longToBytesBE(this.dLastAccessTime));
                out.write(eIsDirectory ? 1 : 0);
                return out.toByteArray();
            }
        }
    }

    @Override
    public void executeTask() throws IOException, PS3NetSrvException {
        ctx.setFile(null);
        IFile file = getFile();
        if (file != null && file.exists()) {
            ctx.setFile(file);
            StatFileResult statResult;
            if (file.isDirectory()) {
                statResult = new StatFileResult(EMPTY_SIZE, file.lastModified() / MILLISECONDS_IN_SECOND,
                        file.lastModified() / MILLISECONDS_IN_SECOND, 0, true);
            } else {
                long creationTime = file.lastModified();
                long lastAccessTime = file.lastModified();

                // Try to get real file stats if possible
                if (file instanceof FileCustom) {
                    File realFile = ((FileCustom) file).getRealFile();
                    if (realFile != null) {
                        long[] fileStats = Utils.getFileStats(realFile);
                        creationTime = fileStats[0];
                        lastAccessTime = fileStats[1];
                    }
                }

                statResult = new StatFileResult(file.length(), file.lastModified() / MILLISECONDS_IN_SECOND,
                        creationTime / MILLISECONDS_IN_SECOND, lastAccessTime / MILLISECONDS_IN_SECOND, false);
            }
            send(statResult);
        } else {
            send(new StatFileResult());
        }
    }
}
