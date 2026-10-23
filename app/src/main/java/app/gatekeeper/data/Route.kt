package app.gatekeeper.data

/** How an app's allowed traffic reaches the internet. Stored by name in Room. */
enum class Route {
    /** Straight out on Wi-Fi or mobile data, as without Gatekeeper. */
    DIRECT,

    /** Through Tor (only while Tor mode is on). Fails closed: never direct while Tor is down. */
    TOR,
}
