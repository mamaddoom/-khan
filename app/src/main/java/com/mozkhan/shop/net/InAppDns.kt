package com.mozkhan.shop.net

import io.grpc.EquivalentAddressGroup
import io.grpc.NameResolver
import io.grpc.NameResolverProvider
import io.grpc.NameResolverRegistry
import io.grpc.Status
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * DNS inside this app only — no VPN, nothing else on the phone is touched (another VPN can stay on).
 *
 * Firestore talks to its server over gRPC, and gRPC looks host names up through its own pluggable "name resolver".
 * We register a resolver for the default "dns" scheme with a higher priority than the built-in one, so Firestore asks
 * the chosen DNS server (directly over UDP) for firestore.googleapis.com. TLS still uses the real host name (SNI and
 * certificate check), which is exactly what anti-sanction DNS relays expect.
 * If the chosen servers do not answer, the phone's own DNS is used so sync never breaks because of this setting.
 *
 * Limits: Firebase sign-in (identitytoolkit / securetoken) and app updates use Android's HTTP stack, which an app
 * cannot redirect without a VPN; they keep using the phone's DNS (or the optional VPN mode).
 */
object InAppDns {
    @Volatile private var servers: () -> List<String> = { emptyList() }
    private val live: MutableSet<Resolver> = Collections.newSetFromMap(ConcurrentHashMap())
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "in-app-dns").apply { isDaemon = true } }
    @Volatile private var installed = false

    /** Call once, before Firestore opens its first connection. */
    @Synchronized
    fun install(currentServers: () -> List<String>) {
        servers = currentServers
        if (installed) return
        runCatching { NameResolverRegistry.getDefaultRegistry().register(Provider()) }.onSuccess { installed = true }
    }

    /** The DNS choice changed: look every live gRPC target up again (Firestore reconnects to the new address). */
    fun refreshAll() = live.toList().forEach { runCatching { it.refresh() } }

    /** Last answer, for the Settings screen: "host → ip (via server)". */
    @Volatile var lastLookup: String? = null
        private set

    fun lookup(host: String): List<InetAddress> {
        for (s in servers()) {
            val ips = runCatching { DnsClient.resolve(s, host).first }.getOrNull().orEmpty()
            if (ips.isNotEmpty()) {
                lastLookup = "$host ← ${ips.first()} (از $s)"
                return ips.map { InetAddress.getByName(it) }
            }
        }
        val sys = InetAddress.getAllByName(host).toList()
        lastLookup = "$host ← ${sys.firstOrNull()?.hostAddress} (DNS گوشی)"
        return sys
    }

    private class Provider : NameResolverProvider() {
        override fun isAvailable() = true
        override fun priority() = 7 // built-in DNS resolver is 5
        override fun getDefaultScheme() = "dns"

        override fun newNameResolver(targetUri: URI, args: NameResolver.Args): NameResolver? {
            if (targetUri.scheme != "dns") return null
            val name = (targetUri.path ?: return null).removePrefix("/")
            if (name.isEmpty()) return null
            val hp = URI.create("//$name")
            val host = hp.host ?: return null
            val port = if (hp.port > 0) hp.port else args.defaultPort
            return Resolver(name, host, port, args)
        }
    }

    private class Resolver(private val authority: String, private val host: String, private val port: Int, private val args: NameResolver.Args) : NameResolver() {
        @Volatile private var listener: Listener2? = null
        @Volatile private var busy = false

        override fun getServiceAuthority() = authority

        override fun start(l: Listener2) {
            listener = l
            live += this
            resolve()
        }

        override fun refresh() = resolve()

        private fun resolve() {
            if (busy || listener == null) return
            busy = true
            (args.offloadExecutor ?: pool).execute {
                val result = runCatching { lookup(host).map { InetSocketAddress(it, port) } }
                args.synchronizationContext.execute {
                    busy = false
                    val l = listener ?: return@execute
                    result.onSuccess { addrs ->
                        if (addrs.isEmpty()) l.onError(Status.UNAVAILABLE.withDescription("no address for $host"))
                        else l.onResult(ResolutionResult.newBuilder().setAddresses(listOf(EquivalentAddressGroup(addrs))).build())
                    }.onFailure { e -> l.onError(Status.UNAVAILABLE.withDescription("cannot resolve $host").withCause(e)) }
                }
            }
        }

        override fun shutdown() {
            live -= this
            listener = null
        }
    }
}
