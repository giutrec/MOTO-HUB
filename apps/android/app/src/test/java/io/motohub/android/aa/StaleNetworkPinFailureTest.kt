// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import java.io.IOException
import java.net.BindException
import java.net.SocketException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleNetworkPinFailureTest {
    @Test
    fun recognisesTheDeadProcessPinByItsErrnoAnywhereInTheChain() {
        // The shape seen in the field: the process is still bound to a T-Box network that just
        // died, and a loopback listener that never touches that network fails with ENONET.
        assertTrue(
            isStaleNetworkPinFailure(SocketException("socket failed: ENONET (Machine is not on the network)"))
        )
        assertTrue(
            isStaleNetworkPinFailure(IOException("bind failed", SocketException("Network is unreachable")))
        )
    }

    @Test
    fun leavesARealPortConflictAlone() {
        // EADDRINUSE means someone genuinely holds :5288 - clearing the process binding would not
        // free the port, and the caller's "port unavailable" message is the correct one.
        assertFalse(isStaleNetworkPinFailure(BindException("bind failed: EADDRINUSE (Address already in use)")))
        assertFalse(isStaleNetworkPinFailure(SecurityException("permission denied")))
        assertFalse(isStaleNetworkPinFailure(SocketException()))
    }
}
