package app.gatekeeper.tor

/**
 * Where the forwarder sends traffic while Tor mode is on: Tor's local SOCKS and DNS ports.
 *
 * [epoch] is part of the SOCKS password. Tor isolates streams by SOCKS username and password
 * (IsolateSOCKSAuth, on by default), so every app (the username) gets its own circuits, and
 * bumping the epoch ("New identity") moves every app onto fresh circuits.
 */
data class TorRoute(
    val socksPort: Int,
    val dnsPort: Int,
    val epoch: Int = 0,
)
