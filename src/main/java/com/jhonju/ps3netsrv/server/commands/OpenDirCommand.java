package com.jhonju.ps3netsrv.server.commands;

import com.jhonju.ps3netsrv.server.Context;
import com.jhonju.ps3netsrv.server.exceptions.PS3NetSrvException;
import com.jhonju.ps3netsrv.server.io.IFile;
import com.jhonju.ps3netsrv.server.utils.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class OpenDirCommand extends FileCommand {

    public OpenDirCommand(Context ctx, short filePathLength) {
        super(ctx, filePathLength);
    }

    @Override
    public void executeTask() throws PS3NetSrvException, IOException {
        ByteBuffer buffer = Utils.readCommandData(ctx.getInputStream(), this.filePathLength);
        if (buffer == null) {
            send(ERROR_CODE_BYTEARRAY);
            throw new PS3NetSrvException("ERROR: command failed receiving filename.");
        }
        String path = new String(buffer.array(), StandardCharsets.UTF_8).replaceAll("\\x00+$", "");
        ctx.setClientPath(path);
        ctx.setDirectoryEntries(null); // Clear previous entries

        IFile file = ctx.getPathResolver().resolveFirst(path);
        if (file != null && file.exists()) {
            ctx.setFile(file);
            send(file.isDirectory() ? SUCCESS_CODE_BYTEARRAY : ERROR_CODE_BYTEARRAY);
        } else {
            ctx.setFile(null);
            send(ERROR_CODE_BYTEARRAY);
        }
    }
}
