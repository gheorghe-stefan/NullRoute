package com.nullroute.vpn

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Pure-Kotlin state machine for selecting the best physical (non-VPN) network
 * and its upstream DNS targets. Isolated from Android Context/VpnService so all
 * roaming, DHCP-delay, and split-horizon DNS behaviors are unit-tested.
 */
class PhysicalNetworkSelector<T : Any> {

    enum class Transport {
        WIFI,
        CELLULAR,
        OTHER
    }

    data class NetworkState(
        val transport: Transport,
        val isValidated: Boolean,
        val dnsServers: List<InetAddress> = emptyList()
    )

    data class UpdateResult<T>(
        val activeNetwork: T?,
        val networkChanged: Boolean,
        val dnsServers: List<InetAddress>,
        val dnsChanged: Boolean,
        val shouldRebuildSocket: Boolean
    )

    private val networks = LinkedHashMap<T, NetworkState>()
    private var activeNetwork: T? = null
    private var activeDnsServers: List<InetAddress> = emptyList()

    @Synchronized
    fun onAvailable(
        network: T,
        transport: Transport,
        isValidated: Boolean,
        dnsServers: List<InetAddress> = emptyList()
    ): UpdateResult<T> {
        val normalizedDns = orderDnsServers(dnsServers)
        networks[network] = NetworkState(transport, isValidated, normalizedDns)
        return recompute(forceRebuild = false)
    }

    @Synchronized
    fun onCapabilitiesChanged(
        network: T,
        transport: Transport,
        isValidated: Boolean
    ): UpdateResult<T> {
        val existing = networks[network]
        networks[network] = NetworkState(
            transport = transport,
            isValidated = isValidated,
            dnsServers = existing?.dnsServers ?: emptyList()
        )
        return recompute(forceRebuild = false)
    }

    @Synchronized
    fun onLinkPropertiesChanged(
        network: T,
        dnsServers: List<InetAddress>
    ): UpdateResult<T> {
        val existing = networks[network] ?: return UpdateResult(
            activeNetwork = activeNetwork,
            networkChanged = false,
            dnsServers = activeDnsServers,
            dnsChanged = false,
            shouldRebuildSocket = false
        )
        val normalizedDns = orderDnsServers(dnsServers)
        networks[network] = existing.copy(dnsServers = normalizedDns)
        return recompute(forceRebuild = false)
    }

    @Synchronized
    fun onLost(network: T): UpdateResult<T> {
        val wasActive = network == activeNetwork
        networks.remove(network)
        return recompute(forceRebuild = wasActive)
    }

    @Synchronized
    fun getActiveNetwork(): T? = activeNetwork

    @Synchronized
    fun getActiveDnsServers(): List<InetAddress> = activeDnsServers

    private fun recompute(forceRebuild: Boolean): UpdateResult<T> {
        val best = selectBestNetwork()
        val networkChanged = best != activeNetwork
        val newDns = if (best != null) networks[best]?.dnsServers ?: emptyList() else emptyList()
        val dnsChanged = newDns != activeDnsServers

        activeNetwork = best
        activeDnsServers = newDns

        // Rebuild socket whenever the physical network switches, or when DHCP/provisioning
        // populates DNS servers on the active network (e.g. [] -> [192.168.0.1]).
        val shouldRebuildSocket = forceRebuild || networkChanged || (best != null && dnsChanged)

        return UpdateResult(
            activeNetwork = best,
            networkChanged = networkChanged,
            dnsServers = newDns,
            dnsChanged = dnsChanged,
            shouldRebuildSocket = shouldRebuildSocket
        )
    }

    private fun selectBestNetwork(): T? {
        var bestWifi: T? = null
        var bestWifiScore = -1
        var bestCellular: T? = null
        var bestCellularScore = -1
        var fallbackOther: T? = null

        for ((net, state) in networks) {
            // Score: +2 if validated, +1 if DNS servers are already provisioned
            val score = (if (state.isValidated) 2 else 0) + (if (state.dnsServers.isNotEmpty()) 1 else 0)
            when (state.transport) {
                Transport.WIFI -> {
                    if (bestWifi == null || score > bestWifiScore) {
                        bestWifi = net
                        bestWifiScore = score
                    }
                }
                Transport.CELLULAR -> {
                    if (bestCellular == null || score > bestCellularScore) {
                        bestCellular = net
                        bestCellularScore = score
                    }
                }
                Transport.OTHER -> {
                    if (fallbackOther == null) {
                        fallbackOther = net
                    }
                }
            }
        }
        return bestWifi ?: bestCellular ?: fallbackOther
    }

    companion object {
        /**
         * Orders DNS servers with IPv4 addresses first (preserving DHCP order),
         * followed by IPv6 addresses if any, deduplicated.
         */
        fun orderDnsServers(raw: List<InetAddress>): List<InetAddress> {
            if (raw.isEmpty()) return emptyList()
            val v4 = ArrayList<InetAddress>(raw.size)
            val v6 = ArrayList<InetAddress>(raw.size)
            for (addr in raw) {
                if (addr is Inet4Address) {
                    if (!v4.contains(addr)) v4.add(addr)
                } else {
                    if (!v6.contains(addr)) v6.add(addr)
                }
            }
            return v4 + v6
        }

        /**
         * Selects upstream DNS targets for a query.
         * If the active network provides local/DHCP DNS servers, ONLY those are used
         * (up to 2) so corporate/split-horizon DNS is never raced against 1.1.1.1.
         * Public fallbacks are returned only when networkDns is empty.
         */
        fun selectUpstreamTargets(
            networkDns: List<InetAddress>,
            fallbackPublicDns: List<InetAddress>
        ): List<InetAddress> {
            val v4Local = networkDns.filterIsInstance<Inet4Address>()
            if (v4Local.isNotEmpty()) {
                return v4Local.take(2)
            }
            if (networkDns.isNotEmpty()) {
                return networkDns.take(2)
            }
            return fallbackPublicDns.take(2)
        }
    }
}
