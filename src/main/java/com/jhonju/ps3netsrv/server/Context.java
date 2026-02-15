package com.jhonju.ps3netsrv.server;

import com.jhonju.ps3netsrv.server.enums.CDSectorSize;
import com.jhonju.ps3netsrv.server.commands.ReadDirCommand.ReadDirEntry;
import com.jhonju.ps3netsrv.server.io.IFile;
import com.jhonju.ps3netsrv.server.utils.PathResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;

public class Context implements AutoCloseable {
    private Socket socket;
    private final String rootDirectory;
    private final boolean readOnly;
    private IFile file;
    private IFile writeOnlyFile;
    private CDSectorSize cdSectorSize;
    private final PathResolver pathResolver;
    private String clientPath;
    private List<ReadDirEntry> directoryEntries;

    public Context(Socket socket, String rootDirectory, boolean readOnly) {
        this.rootDirectory = rootDirectory;
        this.socket = socket;
        this.cdSectorSize = CDSectorSize.CD_SECTOR_2352;
        this.readOnly = readOnly;
        this.pathResolver = new PathResolver(rootDirectory);
    }

    public String getRootDirectory() {
        return rootDirectory;
    }

    public boolean isSocketConnected() {
        return socket.isConnected();
    }

    public CDSectorSize getCdSectorSize() {
        return cdSectorSize;
    }

    public void setCdSectorSize(CDSectorSize cdSectorSize) {
        this.cdSectorSize = cdSectorSize;
    }

    public InetAddress getRemoteAddress() {
        return socket.getInetAddress();
    }

    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    public IFile getFile() {
        return file;
    }

    public void setFile(IFile file) {
        this.file = file;
    }

    public IFile getWriteOnlyFile() {
        return writeOnlyFile;
    }

    public void setWriteOnlyFile(IFile writeOnlyFile) {
        this.writeOnlyFile = writeOnlyFile;
    }

    public PathResolver getPathResolver() {
        return pathResolver;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public String getClientPath() {
        return clientPath;
    }

    public void setClientPath(String clientPath) {
        this.clientPath = clientPath;
    }

    public List<ReadDirEntry> getDirectoryEntries() {
        return directoryEntries;
    }

    public void setDirectoryEntries(List<ReadDirEntry> directoryEntries) {
        this.directoryEntries = directoryEntries;
    }

    @Override
    public void close() {
        try {
            if (file != null)
                file.close();
        } catch (IOException ignored) {
        } finally {
            file = null;
        }

        try {
            if (writeOnlyFile != null)
                writeOnlyFile.close();
        } catch (IOException ignored) {
        } finally {
            writeOnlyFile = null;
        }

        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            } finally {
                socket = null;
            }
        }
    }
}
