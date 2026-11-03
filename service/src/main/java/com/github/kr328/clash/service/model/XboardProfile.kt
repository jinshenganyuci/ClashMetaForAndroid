package com.github.kr328.clash.service.model

import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.TimeUnit

object XboardProfile {
    // Reserved account slot. Normal profiles are created with random v4 UUIDs.
    val id: UUID = UUID.fromString("8e0cfabe-2004-5af1-ae0d-a98227a67659")
    internal val defaultInterval = TimeUnit.HOURS.toMillis(1)
    internal const val unavailable = "XBOARD_PROFILE_UNAVAILABLE"
    internal val lock = Mutex()
}
