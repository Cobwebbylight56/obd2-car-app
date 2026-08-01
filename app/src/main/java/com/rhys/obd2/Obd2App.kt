package com.rhys.obd2

import android.app.Application
import com.rhys.obd2.data.ObdRepository
import com.rhys.obd2.data.Settings
import com.rhys.obd2.transport.DeviceScanner

/**
 * Holds the single connection to the car.
 *
 * The adapter is one physical resource that can serve one request at a time, so the
 * repository is deliberately application-scoped rather than tied to any screen — it has
 * to survive rotation and navigation without dropping the link.
 */
class Obd2App : Application() {

    val settings: Settings by lazy { Settings(this) }
    val repository: ObdRepository by lazy { ObdRepository(this, settings) }
    val scanner: DeviceScanner by lazy { DeviceScanner(this) }
}
