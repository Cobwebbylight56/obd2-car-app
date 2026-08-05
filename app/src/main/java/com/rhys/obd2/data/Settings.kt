package com.rhys.obd2.data

import android.content.Context
import android.content.SharedPreferences
import com.rhys.obd2.obd.PidRegistry
import com.rhys.obd2.transport.AdapterKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences. Small enough that SharedPreferences is the right tool. */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("openobd", Context.MODE_PRIVATE)

    private val _units = MutableStateFlow(UnitSystem.valueOf(prefs.getString(KEY_UNITS, UnitSystem.METRIC.name)!!))
    val units: StateFlow<UnitSystem> = _units.asStateFlow()

    private val _dashboardPids = MutableStateFlow(loadDashboardPids())
    val dashboardPids: StateFlow<List<Int>> = _dashboardPids.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEY_KEEP_SCREEN_ON, true))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, true))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    /**
     * Whether the odometer card sits at the top of the dashboard.
     *
     * On by default because a car that does report its mileage should say so without
     * being asked, and because the rollback warning is the kind of thing you want to be
     * shown rather than to have to go looking for. Off is a legitimate preference: the
     * card occupies the most valuable strip of the screen, and on a car that has no
     * odometer to report it is occupying it for nothing.
     */
    private val _showOdometer = MutableStateFlow(prefs.getBoolean(KEY_SHOW_ODOMETER, true))
    val showOdometer: StateFlow<Boolean> = _showOdometer.asStateFlow()

    fun setUnits(value: UnitSystem) {
        _units.value = value
        prefs.edit().putString(KEY_UNITS, value.name).apply()
    }

    fun setDashboardPids(pids: List<Int>) {
        _dashboardPids.value = pids
        prefs.edit().putString(KEY_DASHBOARD, pids.joinToString(",")).apply()
    }

    fun setKeepScreenOn(value: Boolean) {
        _keepScreenOn.value = value
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
    }

    fun setAutoConnect(value: Boolean) {
        _autoConnect.value = value
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()
    }

    fun setShowOdometer(value: Boolean) {
        _showOdometer.value = value
        prefs.edit().putBoolean(KEY_SHOW_ODOMETER, value).apply()
    }

    /** The adapter used last time, so the connect screen can offer to reconnect. */
    var lastDevice: SavedDevice?
        get() {
            val address = prefs.getString(KEY_LAST_ADDRESS, null) ?: return null
            val kindName = prefs.getString(KEY_LAST_KIND, null) ?: return null
            val kind = runCatching { AdapterKind.valueOf(kindName) }.getOrNull() ?: return null
            return SavedDevice(
                name = prefs.getString(KEY_LAST_NAME, address) ?: address,
                address = address,
                kind = kind,
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_LAST_ADDRESS); remove(KEY_LAST_NAME); remove(KEY_LAST_KIND)
                } else {
                    putString(KEY_LAST_ADDRESS, value.address)
                    putString(KEY_LAST_NAME, value.name)
                    putString(KEY_LAST_KIND, value.kind.name)
                }
            }.apply()
        }

    private fun loadDashboardPids(): List<Int> {
        val stored = prefs.getString(KEY_DASHBOARD, null) ?: return PidRegistry.DEFAULT_DASHBOARD
        val parsed = stored.split(',').mapNotNull { it.trim().toIntOrNull() }
        return parsed.ifEmpty { PidRegistry.DEFAULT_DASHBOARD }
    }

    data class SavedDevice(val name: String, val address: String, val kind: AdapterKind)

    private companion object {
        const val KEY_UNITS = "units"
        const val KEY_DASHBOARD = "dashboard_pids"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_SHOW_ODOMETER = "show_odometer"
        const val KEY_LAST_ADDRESS = "last_address"
        const val KEY_LAST_NAME = "last_name"
        const val KEY_LAST_KIND = "last_kind"
    }
}

enum class UnitSystem(val label: String, val detail: String) {
    METRIC("Metric", "km/h, °C, kPa, litres"),

    /**
     * What Britain actually uses, which is neither of the other two.
     *
     * Road speed and distance are imperial because the road signs are, but everything
     * under the bonnet is metric — a UK garage quotes coolant in Celsius and boost in bar
     * or kPa, and no British driver thinks in Fahrenheit. Offering only Metric and
     * Imperial forces a choice where both options are wrong: metric gives you km/h you
     * can't compare to a speedometer, imperial gives you Fahrenheit nobody uses.
     */
    UK("UK", "mph and miles, °C, kPa"),

    IMPERIAL("Imperial (US)", "mph, °F, psi"),
}

/**
 * Converts decoded values for display.
 *
 * The OBD standard is metric throughout, so metric values are what come off the wire and
 * imperial is always a conversion. Keeping that one-directional avoids the rounding
 * drift you get from converting back and forth.
 */
object Units {

    data class Converted(val value: Double, val unit: String)

    fun convert(value: Double, unit: String, system: UnitSystem): Converted {
        if (system == UnitSystem.METRIC) return Converted(value, unit)

        // The UK converts distance and speed and leaves everything else alone.
        if (system == UnitSystem.UK) return when (unit) {
            "km/h" -> Converted(value * 0.621371, "mph")
            "km" -> Converted(value * 0.621371, "miles")
            else -> Converted(value, unit)
        }

        return when (unit) {
            "km/h" -> Converted(value * 0.621371, "mph")
            "°C" -> Converted(value * 9.0 / 5.0 + 32.0, "°F")
            "km" -> Converted(value * 0.621371, "miles")
            "kPa" -> Converted(value * 0.145038, "psi")
            "Pa" -> Converted(value * 0.000145038, "psi")
            "L/h" -> Converted(value * 0.219969, "gal/h")
            "g/s" -> Converted(value * 0.132277, "lb/min")
            "Nm" -> Converted(value * 0.737562, "lb-ft")
            "kg/h" -> Converted(value * 2.20462, "lb/h")
            else -> Converted(value, unit)
        }
    }

    /**
     * Formats to a sensible number of decimals for the magnitude. Large values like RPM
     * and odometer readings get none; small ones like lambda get three.
     */
    fun format(value: Double, unit: String): String {
        val decimals = when {
            // Speed and revs are whole numbers on every dashboard ever made. Showing
            // "0.00 mph" reads as a malfunctioning instrument rather than a stationary car.
            unit == "rpm" || unit == "km" || unit == "miles" -> 0
            unit == "km/h" || unit == "mph" -> 0
            unit == "λ" -> 3
            kotlin.math.abs(value) >= 1000 -> 0
            kotlin.math.abs(value) >= 100 -> 1
            kotlin.math.abs(value) >= 10 -> 1
            else -> 2
        }
        return "%.${decimals}f".format(java.util.Locale.UK, value)
    }
}
