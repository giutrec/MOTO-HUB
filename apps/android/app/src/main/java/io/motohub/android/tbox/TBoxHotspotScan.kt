// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Finding the dash when the *phone* hosts the network.
 *
 * In [io.motohub.android.session.TBoxConnectionMode.PHONE_HOTSPOT] every assumption the other two
 * transports rest on is inverted: there is no dash access point to join, no `Network` handed over
 * by `ConnectivityManager` for the tethering interface, and no fixed `.1` gateway to aim a probe
 * at - the phone is the gateway and the dash is just some DHCP client on the subnet.
 *
 * The address arithmetic lives here, apart from the sockets, because it is the half that can be
 * tested without a motorcycle. See `TBoxHotspotScanTest`.
 */
object TBoxHotspotScan {

    /**
     * Interface-name prefixes Android has used for the tethering/SoftAP side. The list is
     * corroboration, not a gate: [tetheringSubnets] accepts any interface that looks like a
     * hosted network, because vendors rename these freely and a missed name would cost the
     * whole feature. Names decide ordering, so the likeliest candidate is swept first - and
     * they decide one thing more, see [isHostedName].
     */
    private val LIKELY_AP_PREFIXES = listOf("ap", "swlan", "wlan1", "softap", "rndis", "tether")

    /**
     * Ranked below a real SoftAP interface but above an unrecognised name.
     *
     * A Wi-Fi Direct group is not a hotspot, yet it produces the same shape - the phone holding a
     * private /24 with the dash as the only other host - and several EasyConn dashes reach the
     * phone that way while the rider's hotspot is switched off entirely. Field log 2026-08-06
     * (OnePlus CPH2653, EASYCONN_5G-F3116E): three interfaces qualified at once (`p2p0`, `wlan0`,
     * `wlan2`), all tied at the bottom rank, so a stable sort left the choice to kernel
     * enumeration order - `p2p0` found the dash in 114ms, `wlan0` and `wlan2` swept 253 addresses
     * for 45s and found nothing. Same phone, same bike, opposite outcomes, decided by luck.
     */
    private val PEER_LINK_PREFIXES = listOf("p2p")

    /**
     * Whether a name is one Android uses for a network it *hosts*, as opposed to one it joined.
     *
     * This is the one place a name is more than a hint: [tetheringSubnets] refuses to drop such an
     * interface for being "in use". Excluding the phone's own uplink is what stops a rider's home
     * subnet from being swept, but if some OEM ever surfaced its SoftAP as a visible network too,
     * the same exclusion would delete the only correct answer. Immunity by name makes that
     * regression impossible rather than unlikely.
     *
     * Peer links earn the same immunity, and it took a contradiction to see why. Ranking `p2p0`
     * above an unknown name says a Wi-Fi Direct group is a plausible place to find the dash - it
     * is the reason [PEER_LINK_PREFIXES] exists at all, after that interface reached the dash in
     * 114ms. Letting the exclusion delete it anyway would leave the rank describing a candidate
     * that can no longer be chosen. Which way to resolve that is settled by who holds the subnet:
     * on a P2P group the phone is the group owner and the dash is its only client, so this is a
     * network the phone hosts in every sense the sweep cares about.
     */
    private fun isHostedName(interfaceName: String): Boolean {
        val lowered = interfaceName.lowercase()
        return LIKELY_AP_PREFIXES.any(lowered::startsWith) || PEER_LINK_PREFIXES.any(lowered::startsWith)
    }

    /** A hosted IPv4 subnet: the phone's own address on it, and how wide it is. */
    data class Subnet(val localAddress: Inet4Address, val prefixLength: Int, val interfaceName: String)

    /**
     * A snapshot of one interface, so enumeration can be tested without a device.
     * [addresses] pairs each address with its prefix length, matching `InterfaceAddress`.
     */
    data class InterfaceSnapshot(
        val name: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isPointToPoint: Boolean,
        val addresses: List<Pair<InetAddress, Int>>
    )

    /**
     * Picks the interfaces that look like a hotspot the phone is hosting.
     *
     * [excluding] is the address of the network the phone is *using* (its own Wi-Fi or mobile
     * link). Without it a phone that is both joined to a home network and hosting a hotspot would
     * have its home subnet swept too - slow, and it would probe strangers' devices.
     *
     * A /24 is the practical ceiling: Android hands out a /24 for tethering, and anything wider
     * would be a sweep of thousands of addresses that cannot finish inside a pairing attempt.
     */
    fun tetheringSubnets(
        interfaces: List<InterfaceSnapshot>,
        excluding: Set<InetAddress> = emptySet()
    ): List<Subnet> =
        interfaces
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { candidate ->
                candidate.addresses.mapNotNull { (address, prefixLength) ->
                    val ipv4 = address as? Inet4Address ?: return@mapNotNull null
                    if (ipv4 in excluding && !isHostedName(candidate.name)) return@mapNotNull null
                    if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress || ipv4.isAnyLocalAddress) {
                        return@mapNotNull null
                    }
                    // A hosted network is always private. Anything routable is the carrier's.
                    if (!ipv4.isSiteLocalAddress) return@mapNotNull null
                    if (prefixLength < 24 || prefixLength > 30) return@mapNotNull null
                    Subnet(ipv4, prefixLength, candidate.name)
                }
            }
            .sortedBy { subnet -> rank(subnet.interfaceName) }

    /**
     * SoftAP names first in the order of [LIKELY_AP_PREFIXES], then [PEER_LINK_PREFIXES], then
     * unknown names. Every rank is distinct so the sort is a decision rather than a tie broken by
     * enumeration order.
     */
    private fun rank(interfaceName: String): Int {
        val lowered = interfaceName.lowercase()
        val hosted = LIKELY_AP_PREFIXES.indexOfFirst(lowered::startsWith)
        if (hosted >= 0) return hosted
        val peer = PEER_LINK_PREFIXES.indexOfFirst(lowered::startsWith)
        if (peer >= 0) return LIKELY_AP_PREFIXES.size + peer
        return LIKELY_AP_PREFIXES.size + PEER_LINK_PREFIXES.size
    }

    /**
     * Every address a dash could hold on [subnet], excluding the network and broadcast addresses
     * and the phone's own. Ordered outward from the phone: Android's DHCP pool starts just above
     * the gateway, so the dash is overwhelmingly likely to be among the first few tried, and a
     * rider watching a progress line sees it resolve in a second rather than at address 200.
     */
    fun candidateHosts(subnet: Subnet): List<Inet4Address> {
        val local = subnet.localAddress.address
        val hostBits = 32 - subnet.prefixLength
        val size = 1 shl hostBits
        val localValue = local.fold(0) { accumulated, byte -> (accumulated shl 8) or (byte.toInt() and 0xFF) }
        val mask = if (hostBits == 32) 0 else (-1 shl hostBits)
        val network = localValue and mask
        val ordered = (1 until size - 1)
            .map { offset -> network + offset }
            .filter { candidate -> candidate != localValue }
            .sortedBy { candidate -> kotlin.math.abs(candidate - localValue) }
        return ordered.map { value ->
            InetAddress.getByAddress(
                byteArrayOf(
                    (value ushr 24).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 8).toByte(),
                    value.toByte()
                )
            ) as Inet4Address
        }
    }

    /** Live enumeration; [tetheringSubnets] holds the logic worth testing. */
    fun snapshotInterfaces(): List<InterfaceSnapshot> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().map { candidate ->
                InterfaceSnapshot(
                    name = candidate.name.orEmpty(),
                    isUp = runCatching { candidate.isUp }.getOrDefault(false),
                    isLoopback = runCatching { candidate.isLoopback }.getOrDefault(false),
                    isPointToPoint = runCatching { candidate.isPointToPoint }.getOrDefault(false),
                    addresses = candidate.interfaceAddresses.orEmpty().mapNotNull { interfaceAddress ->
                        interfaceAddress.address?.let { it to interfaceAddress.networkPrefixLength.toInt() }
                    }
                )
            }.toList()
        }.getOrDefault(emptyList())
}
