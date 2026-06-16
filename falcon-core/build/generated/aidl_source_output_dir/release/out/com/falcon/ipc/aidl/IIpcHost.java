/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package com.falcon.ipc.aidl;
public interface IIpcHost extends android.os.IInterface
{
  /** Default implementation for IIpcHost. */
  public static class Default implements com.falcon.ipc.aidl.IIpcHost
  {
    @Override public com.falcon.ipc.protocol.IpcEnvelope invoke(com.falcon.ipc.protocol.IpcEnvelope request) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void subscribe(java.lang.String eventKey, com.falcon.ipc.aidl.IIpcEventCallback callback) throws android.os.RemoteException
    {
    }
    @Override public void unsubscribe(java.lang.String eventKey, com.falcon.ipc.aidl.IIpcEventCallback callback) throws android.os.RemoteException
    {
    }
    @Override public java.lang.String getServiceInfo() throws android.os.RemoteException
    {
      return null;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.falcon.ipc.aidl.IIpcHost
  {
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.falcon.ipc.aidl.IIpcHost interface,
     * generating a proxy if needed.
     */
    public static com.falcon.ipc.aidl.IIpcHost asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.falcon.ipc.aidl.IIpcHost))) {
        return ((com.falcon.ipc.aidl.IIpcHost)iin);
      }
      return new com.falcon.ipc.aidl.IIpcHost.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      switch (code)
      {
        case INTERFACE_TRANSACTION:
        {
          reply.writeString(descriptor);
          return true;
        }
      }
      switch (code)
      {
        case TRANSACTION_invoke:
        {
          com.falcon.ipc.protocol.IpcEnvelope _arg0;
          _arg0 = _Parcel.readTypedObject(data, com.falcon.ipc.protocol.IpcEnvelope.CREATOR);
          com.falcon.ipc.protocol.IpcEnvelope _result = this.invoke(_arg0);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_subscribe:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          com.falcon.ipc.aidl.IIpcEventCallback _arg1;
          _arg1 = com.falcon.ipc.aidl.IIpcEventCallback.Stub.asInterface(data.readStrongBinder());
          this.subscribe(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_unsubscribe:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          com.falcon.ipc.aidl.IIpcEventCallback _arg1;
          _arg1 = com.falcon.ipc.aidl.IIpcEventCallback.Stub.asInterface(data.readStrongBinder());
          this.unsubscribe(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getServiceInfo:
        {
          java.lang.String _result = this.getServiceInfo();
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.falcon.ipc.aidl.IIpcHost
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public com.falcon.ipc.protocol.IpcEnvelope invoke(com.falcon.ipc.protocol.IpcEnvelope request) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        com.falcon.ipc.protocol.IpcEnvelope _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, request, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_invoke, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, com.falcon.ipc.protocol.IpcEnvelope.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void subscribe(java.lang.String eventKey, com.falcon.ipc.aidl.IIpcEventCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(eventKey);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_subscribe, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void unsubscribe(java.lang.String eventKey, com.falcon.ipc.aidl.IIpcEventCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(eventKey);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_unsubscribe, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public java.lang.String getServiceInfo() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getServiceInfo, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_invoke = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_subscribe = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_unsubscribe = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_getServiceInfo = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
  }
  public static final java.lang.String DESCRIPTOR = "com.falcon.ipc.aidl.IIpcHost";
  public com.falcon.ipc.protocol.IpcEnvelope invoke(com.falcon.ipc.protocol.IpcEnvelope request) throws android.os.RemoteException;
  public void subscribe(java.lang.String eventKey, com.falcon.ipc.aidl.IIpcEventCallback callback) throws android.os.RemoteException;
  public void unsubscribe(java.lang.String eventKey, com.falcon.ipc.aidl.IIpcEventCallback callback) throws android.os.RemoteException;
  public java.lang.String getServiceInfo() throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}
