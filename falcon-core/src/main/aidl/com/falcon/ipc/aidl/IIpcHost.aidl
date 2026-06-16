package com.falcon.ipc.aidl;

import com.falcon.ipc.protocol.IpcEnvelope;
import com.falcon.ipc.aidl.IIpcEventCallback;

interface IIpcHost {
    IpcEnvelope invoke(in IpcEnvelope request);
    void subscribe(String eventKey, IIpcEventCallback callback);
    void unsubscribe(String eventKey, IIpcEventCallback callback);
    String getServiceInfo();
}
