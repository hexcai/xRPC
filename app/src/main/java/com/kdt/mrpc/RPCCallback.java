package com.kdt.mrpc;

public interface RPCCallback
{
    public static final int STATUS_CONNECTED = 1;
    public static final int STATUS_CLOSED = 1000;

    public void appendToLog(String line);
    public void onStatusChanged(int code, String reason, boolean remote);
}
