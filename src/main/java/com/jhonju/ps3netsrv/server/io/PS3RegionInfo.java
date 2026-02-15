package com.jhonju.ps3netsrv.server.io;

public class PS3RegionInfo {
    private final boolean encrypted;
    private final long firstAddress;
    private final long lastAddress;

    public PS3RegionInfo(boolean encrypted, long firstAddress, long lastAddress) {
        this.encrypted = encrypted;
        this.firstAddress = firstAddress;
        this.lastAddress = lastAddress;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public long getFirstAddress() {
        return firstAddress;
    }

    public long getLastAddress() {
        return lastAddress;
    }
}
