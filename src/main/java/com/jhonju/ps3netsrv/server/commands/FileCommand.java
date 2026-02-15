package com.jhonju.ps3netsrv.server.commands;

import com.jhonju.ps3netsrv.server.Context;
import com.jhonju.ps3netsrv.server.exceptions.PS3NetSrvException;
import com.jhonju.ps3netsrv.server.io.FileCustom;
import com.jhonju.ps3netsrv.server.io.IFile;
import com.jhonju.ps3netsrv.server.io.VirtualIsoFile;
import com.jhonju.ps3netsrv.server.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public abstract class FileCommand extends AbstractCommand {

    protected short filePathLength;

    public FileCommand(Context ctx, short filePathLength) {
        super(ctx);
        this.filePathLength = filePathLength;
    }

    protected IFile getFile() throws IOException, PS3NetSrvException {
        ByteBuffer buffer = Utils.readCommandData(ctx.getInputStream(), this.filePathLength);
        if (buffer == null) {
            send(ERROR_CODE_BYTEARRAY);
            throw new PS3NetSrvException("ERROR: command failed receiving filename.");
        }
        String path = new String(buffer.array(), StandardCharsets.UTF_8).replaceAll("\\x00+$", "");

        // Handle Virtual ISO prefixes
        if (path.startsWith("/***PS3***/") || path.startsWith("/***DVD***/")) {
            String subPath = path.substring(11);
            IFile targetDir = resolveFile(subPath);
            if (targetDir != null && targetDir.isDirectory()) {
                return new VirtualIsoFile(targetDir);
            }
        }

        return resolveFile(path);
    }

    private IFile resolveFile(String path) throws IOException {
        return ctx.getPathResolver().resolveFirst(path);
    }
}
