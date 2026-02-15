package com.jhonju.ps3netsrv.server.commands;

import com.jhonju.ps3netsrv.server.Context;
import com.jhonju.ps3netsrv.server.exceptions.PS3NetSrvException;
import com.jhonju.ps3netsrv.server.io.IFile;
import java.io.IOException;

public class ReadFileCriticalCommand extends ReadFileCommand {

    public ReadFileCriticalCommand(Context ctx, int numBytes, long offset) {
        super(ctx, numBytes, offset);
    }

    @Override
    public void executeTask() throws IOException, PS3NetSrvException {
        byte[] result = new byte[numBytes];
        IFile file = ctx.getFile();
        if (file == null) {
            throw new PS3NetSrvException("Error reading file: no file opened.");
        }
        try {
            if (file.read(result, 0, numBytes, offset) < 0) {
                throw new PS3NetSrvException("Error reading file. EOF");
            }
        } catch (IOException e) {
            throw new PS3NetSrvException("Error reading file.");
        }
        send(result);
    }
}
