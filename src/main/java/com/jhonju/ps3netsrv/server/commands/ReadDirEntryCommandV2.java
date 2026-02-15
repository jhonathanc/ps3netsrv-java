package com.jhonju.ps3netsrv.server.commands;

import com.jhonju.ps3netsrv.server.Context;
import com.jhonju.ps3netsrv.server.exceptions.PS3NetSrvException;
import com.jhonju.ps3netsrv.server.utils.Utils;
import com.jhonju.ps3netsrv.server.io.IFile;
import com.jhonju.ps3netsrv.server.commands.ReadDirCommand.ReadDirEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ReadDirEntryCommandV2 extends AbstractCommand {

    private static final int RESULT_LENGTH = 290;
    private static final short MAX_FILE_NAME_LENGTH = 255;
    private static final short EMPTY_FILE_NAME_LENGTH = 0;

    public ReadDirEntryCommandV2(Context ctx) {
        super(ctx);
    }

    private static class ReadDirEntryResultV2 implements IResult {
        private final long aFileSize;
        private final long bModifiedTime;
        private final long cCreationTime;
        private final long dAccessedTime;
        private final short eFileNameLength;
        private final boolean fIsDirectory;
        private final String gFileName;

        public ReadDirEntryResultV2() {
            this.aFileSize = EMPTY_SIZE;
            this.bModifiedTime = 0L;
            this.cCreationTime = 0L;
            this.dAccessedTime = 0L;
            this.eFileNameLength = EMPTY_FILE_NAME_LENGTH;
            this.fIsDirectory = false;
            this.gFileName = null;
        }

        public ReadDirEntryResultV2(long aFileSize, long bModifiedTime, long cCreationTime, long dAccessedTime,
                short eFileNameLength, boolean fIsDirectory, String gFileName) {
            this.aFileSize = aFileSize;
            this.bModifiedTime = bModifiedTime;
            this.cCreationTime = cCreationTime;
            this.dAccessedTime = dAccessedTime;
            this.eFileNameLength = eFileNameLength;
            this.fIsDirectory = fIsDirectory;
            this.gFileName = gFileName;
        }

        public byte[] toByteArray() throws IOException {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream(RESULT_LENGTH)) {
                out.write(Utils.longToBytesBE(this.aFileSize));
                out.write(Utils.longToBytesBE(this.bModifiedTime));
                out.write(Utils.longToBytesBE(this.cCreationTime));
                out.write(Utils.longToBytesBE(this.dAccessedTime));
                out.write(Utils.shortToBytesBE(this.eFileNameLength));
                out.write(fIsDirectory ? 1 : 0);
                if (gFileName != null) {
                    out.write(gFileName.getBytes(StandardCharsets.UTF_8));
                }
                return out.toByteArray();
            }
        }
    }

    @Override
    public void executeTask() throws IOException, PS3NetSrvException {
        List<ReadDirEntry> entries = ctx.getDirectoryEntries();
        if (entries == null || entries.isEmpty()) {
            send(new ReadDirEntryResultV2());
            return;
        }

        ReadDirEntry entry = entries.remove(0);
        send(new ReadDirEntryResultV2(
                entry.aFileSize,
                entry.bModifiedTime,
                entry.bModifiedTime, // Creation time not always available for all IFiles, using modified as fallback
                entry.bModifiedTime, // Accessed time not always available for all IFiles, using modified as fallback
                (short) entry.dFileName.length(),
                entry.cIsDirectory,
                entry.dFileName));
    }
}
