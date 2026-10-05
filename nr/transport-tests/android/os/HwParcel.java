/* SPDX-License-Identifier: Apache-2.0 */
package android.os;

import java.util.ArrayList;
import java.util.List;

/** Typed parcel double. Deliberately fails missing status and truncated payloads. */
public final class HwParcel {
    public String token;
    public final List<Object> payload = new ArrayList<>();
    public Integer status;
    public int verifyCalls;
    public int sendCalls;
    public boolean temporaryReleased;
    public boolean released;
    private int mPosition;

    public void writeInterfaceToken(String value) { token = value; }
    public void enforceInterface(String expected) {
        if (!expected.equals(token)) throw new SecurityException("Incorrect interface token");
    }
    public void writeInt32(int value) { payload.add(value); }
    public int readInt32() { return (Integer) payload.get(mPosition++); }
    public void writeStrongBinder(IHwBinder value) { payload.add(value); }
    public IHwBinder readStrongBinder() { return (IHwBinder) payload.get(mPosition++); }
    public void writeString(String value) { payload.add(value); }
    public String readString() { return (String) payload.get(mPosition++); }
    public void writeStringVector(ArrayList<String> value) {
        payload.add(new ArrayList<>(value));
    }
    public ArrayList<String> readStringVector() {
        Object value = payload.get(mPosition++);
        if (!(value instanceof List<?>)) throw new IllegalStateException("Not a string vector");
        ArrayList<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) result.add((String) item);
        return result;
    }
    public void writeNativeHandle(Object value) { payload.add(value); }
    public Object readNativeHandle() { return payload.get(mPosition++); }
    public void writeBuffer(HwBlob value) { payload.add(value); }
    public void writeStatus(int value) { status = value; }
    public void verifySuccess() throws RemoteException {
        verifyCalls++;
        if (status == null || status != 0) throw new RemoteException("Unsuccessful reply");
    }
    public void send() { sendCalls++; }
    public void releaseTemporaryStorage() { temporaryReleased = true; }
    public void release() { released = true; }
    public int unreadCount() { return payload.size() - mPosition; }
}
