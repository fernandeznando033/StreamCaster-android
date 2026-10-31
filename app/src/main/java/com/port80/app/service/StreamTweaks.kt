package com.port80.app.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live tweaks that must survive bridge/preview recreation during a session.
 */
object StreamTweaks {
    /** Rotate the outgoing stream 180 degrees (preview is not affected). */
    val flip180 = MutableStateFlow(false)
}
