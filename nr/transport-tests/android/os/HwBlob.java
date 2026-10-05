/* SPDX-License-Identifier: Apache-2.0 */
package android.os;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/** Bounds-checked little-endian storage; embedded blobs are tracked separately. */
public final class HwBlob {
    public final byte[] bytes;
    public final Map<Long, HwBlob> children = new HashMap<>();
    private final ByteBuffer mBuffer;

    public HwBlob(int size) {
        bytes = new byte[size];
        mBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private int index(long offset, int length) {
        if (offset < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("Blob write outside allocation");
        }
        return (int) offset;
    }

    public void putInt8(long offset, byte value) { bytes[index(offset, 1)] = value; }
    public void putBool(long offset, boolean value) { putInt8(offset, (byte) (value ? 1 : 0)); }
    public void putInt32(long offset, int value) { mBuffer.putInt(index(offset, 4), value); }
    public void putInt64(long offset, long value) { mBuffer.putLong(index(offset, 8), value); }
    public void putBlob(long offset, HwBlob child) {
        index(offset, 8);
        children.put(offset, child);
    }
    public int getInt32(long offset) { return mBuffer.getInt(index(offset, 4)); }
    public long getInt64(long offset) { return mBuffer.getLong(index(offset, 8)); }
}
