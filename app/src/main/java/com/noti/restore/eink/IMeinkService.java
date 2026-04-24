package com.noti.restore.eink;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IMeinkService extends IInterface {

    void setDisplayMode(String packageName, int mode) throws RemoteException;

    abstract class Stub extends Binder implements IMeinkService {
        private static final String DESCRIPTOR = "android.meink.IMeinkService";

        public Stub() { attachInterface(this, DESCRIPTOR); }

        public static IMeinkService asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IMeinkService) return (IMeinkService) iin;
            return new Proxy(obj);
        }

        @Override public IBinder asBinder() { return this; }

        private static class Proxy implements IMeinkService {
            private final IBinder remote;

            Proxy(IBinder remote) { this.remote = remote; }

            @Override public IBinder asBinder() { return remote; }

            @Override
            public void setDisplayMode(String packageName, int mode) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(packageName);
                    data.writeInt(mode);
                    remote.transact(5, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
