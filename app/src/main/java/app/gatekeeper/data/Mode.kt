package app.gatekeeper.data

/** Internet access mode for a single app. Stored by name in Room. */
enum class Mode {
    /** Normal internet. */
    ALLOWED,

    /** No internet, ever. */
    BLOCKED,

    /** Internet only while the app is on screen, plus a grace period after leaving it. */
    WHILE_OPEN,
}
