package com.falcon.ipc.aidl;

import com.falcon.ipc.protocol.IpcEnvelope;

interface IIpcEventCallback {
    void onEvent(in IpcEnvelope event);
    String getEventKey();
}
