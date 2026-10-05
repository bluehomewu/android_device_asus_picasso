/* SPDX-License-Identifier: Apache-2.0 */
package android.os;

public class RemoteException extends Exception {
    private static final long serialVersionUID = 1L;

    public RemoteException(String message) { super(message); }
}
