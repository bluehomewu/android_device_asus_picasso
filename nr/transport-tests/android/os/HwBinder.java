/* SPDX-License-Identifier: Apache-2.0 */
package android.os;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/** Test double only: no binder device or Android runtime is accessed. */
public abstract class HwBinder implements IHwBinder {
    public static final Map<String, IHwBinder> SERVICES = new HashMap<>();
    public static String lastInterface;
    public static String lastInstance;
    public static boolean lastRetry;
    public static int lookups;
    public static boolean unavailableThrows;

    public static IHwBinder getService(String iface, String instance, boolean retry)
            throws RemoteException {
        lastInterface = iface;
        lastInstance = instance;
        lastRetry = retry;
        lookups++;
        IHwBinder result = SERVICES.get(instance);
        if (result == null && unavailableThrows) {
            throw new NoSuchElementException(instance);
        }
        return result;
    }

    @Override
    public final void transact(int code, HwParcel request, HwParcel reply, int flags)
            throws RemoteException {
        onTransact(code, request, reply, flags);
    }

    public abstract void onTransact(int code, HwParcel request, HwParcel reply, int flags)
            throws RemoteException;
}
