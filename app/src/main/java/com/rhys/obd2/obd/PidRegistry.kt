package com.rhys.obd2.obd

/**
 * The service-01 parameter table, per SAE J1979 / ISO 15031-5.
 *
 * Formulas are given in terms of the returned bytes A, B, C, D … exactly as the standard
 * writes them, so each entry can be checked against the spec line by line.
 *
 * Not every PID here has a clean scalar formula — a handful of the diesel and
 * aftertreatment ones pack several sub-values plus a support bitmask into one response.
 * Those are decoded into multiple labelled readings where the layout is unambiguous, and
 * left as raw bytes where it isn't, rather than inventing a number that looks
 * authoritative and is wrong.
 */
object PidRegistry {

    // Byte accessors, named to match the standard's formula notation.
    private fun IntArray.a() = this[0]
    private fun IntArray.b() = this[1]
    private fun IntArray.c() = this[2]
    private fun IntArray.d() = this[3]

    private fun one(value: Double, label: String = "", unit: String = "", text: String? = null) =
        listOf(Reading(label, value, unit, text))

    /** 16-bit big-endian from the first two bytes. */
    private fun IntArray.word(offset: Int = 0) = (this[offset] shl 8) or this[offset + 1]

    /** Percentage encoded as 0–255 across 0–100 %. */
    private fun pct255(v: Int) = v * 100.0 / 255.0

    /** Fuel trim encoding: 0–255 across -100 % to +99.2 %. */
    private fun trim(v: Int) = v / 1.28 - 100.0

    /** Temperature encoding: unsigned byte offset by 40 °C. */
    private fun temp(v: Int) = v - 40.0

    val ALL: List<Pid> = buildList {

        // --- Status and support bitmaps -------------------------------------------------
        add(Pid(0x00, "Supported PIDs 01-20", bytes = 4, category = PidCategory.STATUS) {
            one(0.0, text = "bitmask")
        })
        add(Pid(0x01, "Monitor status since DTCs cleared", bytes = 4, category = PidCategory.STATUS) {
            one(((it.a() and 0x7F)).toDouble(), "Stored DTC count", "")
        })
        add(Pid(0x02, "Freeze frame DTC", bytes = 2, category = PidCategory.STATUS) {
            one(0.0, text = Dtc.decodePair(it.a(), it.b()) ?: "None")
        })
        add(Pid(0x03, "Fuel system status", bytes = 2, category = PidCategory.FUEL) {
            listOf(
                Reading("Fuel system 1", it.a().toDouble(), "", fuelSystemStatus(it.a())),
                Reading("Fuel system 2", it.b().toDouble(), "", fuelSystemStatus(it.b())),
            )
        })

        // --- Core engine ----------------------------------------------------------------
        add(Pid(0x04, "Calculated engine load", "%", 0.0, 100.0, 1, PidCategory.ENGINE, featured = true) {
            one(pct255(it.a()))
        })
        add(Pid(0x05, "Engine coolant temperature", "°C", -40.0, 215.0, 1, PidCategory.TEMPERATURE, featured = true) {
            one(temp(it.a()))
        })
        add(Pid(0x0C, "Engine RPM", "rpm", 0.0, 8000.0, 2, PidCategory.ENGINE, featured = true) {
            one(it.word() / 4.0)
        })
        add(Pid(0x0D, "Vehicle speed", "km/h", 0.0, 255.0, 1, PidCategory.SPEED, featured = true) {
            one(it.a().toDouble())
        })
        add(Pid(0x0E, "Timing advance", "°", -64.0, 63.5, 1, PidCategory.ENGINE) {
            one(it.a() / 2.0 - 64.0)
        })
        add(Pid(0x11, "Throttle position", "%", 0.0, 100.0, 1, PidCategory.ENGINE, featured = true) {
            one(pct255(it.a()))
        })
        add(Pid(0x1F, "Run time since engine start", "s", 0.0, 65535.0, 2, PidCategory.ENGINE) {
            one(it.word().toDouble())
        })
        add(Pid(0x43, "Absolute load value", "%", 0.0, 25700.0, 2, PidCategory.ENGINE) {
            one(it.word() * 100.0 / 255.0)
        })
        add(Pid(0x45, "Relative throttle position", "%", 0.0, 100.0, 1, PidCategory.ENGINE) {
            one(pct255(it.a()))
        })
        add(Pid(0x47, "Absolute throttle position B", "%", 0.0, 100.0, 1, PidCategory.ENGINE) {
            one(pct255(it.a()))
        })
        add(Pid(0x48, "Absolute throttle position C", "%", 0.0, 100.0, 1, PidCategory.ENGINE) {
            one(pct255(it.a()))
        })
        add(Pid(0x49, "Accelerator pedal position D", "%", 0.0, 100.0, 1, PidCategory.ENGINE) {
            one(pct255(it.a()))
        })
        add(Pid(0x4A, "Accelerator pedal position E", "%", 0.0, 100.0, 1, PidCategory.ENGINE) {
            one(pct255(it.a()))
        })
        add(Pid(0x4B, "Accelerator pedal position F", "%", 0.0, 100.0, 1, PidCategory.ENGINE) {
            one(pct255(it.a()))
        })
        add(Pid(0x4C, "Commanded throttle actuator", "%", 0.0, 100.0, 1, PidCategory.ENGINE) {
            one(pct255(it.a()))
        })
        add(Pid(0x5A, "Relative accelerator pedal position", "%", 0.0, 100.0, 1, PidCategory.ENGINE) {
            one(pct255(it.a()))
        })
        add(Pid(0x61, "Driver's demand engine torque", "%", -125.0, 130.0, 1, PidCategory.ENGINE) {
            one(it.a() - 125.0)
        })
        add(Pid(0x62, "Actual engine torque", "%", -125.0, 130.0, 1, PidCategory.ENGINE) {
            one(it.a() - 125.0)
        })
        add(Pid(0x63, "Engine reference torque", "Nm", 0.0, 65535.0, 2, PidCategory.ENGINE) {
            one(it.word().toDouble())
        })
        add(Pid(0x64, "Engine percent torque", "%", -125.0, 130.0, 5, PidCategory.ENGINE) {
            listOf(
                Reading("Idle torque", it[0] - 125.0, "%"),
                Reading("Engine point 1", it[1] - 125.0, "%"),
                Reading("Engine point 2", it[2] - 125.0, "%"),
                Reading("Engine point 3", it[3] - 125.0, "%"),
                Reading("Engine point 4", it[4] - 125.0, "%"),
            )
        })

        // --- Fuel and trims -------------------------------------------------------------
        add(Pid(0x06, "Short term fuel trim — Bank 1", "%", -100.0, 99.2, 1, PidCategory.FUEL, featured = true) {
            one(trim(it.a()))
        })
        add(Pid(0x07, "Long term fuel trim — Bank 1", "%", -100.0, 99.2, 1, PidCategory.FUEL, featured = true) {
            one(trim(it.a()))
        })
        add(Pid(0x08, "Short term fuel trim — Bank 2", "%", -100.0, 99.2, 1, PidCategory.FUEL) {
            one(trim(it.a()))
        })
        add(Pid(0x09, "Long term fuel trim — Bank 2", "%", -100.0, 99.2, 1, PidCategory.FUEL) {
            one(trim(it.a()))
        })
        add(Pid(0x0A, "Fuel pressure", "kPa", 0.0, 765.0, 1, PidCategory.FUEL) {
            one(it.a() * 3.0)
        })
        add(Pid(0x22, "Fuel rail pressure (rel. to manifold vacuum)", "kPa", 0.0, 5177.3, 2, PidCategory.FUEL) {
            one(it.word() * 0.079)
        })
        add(Pid(0x23, "Fuel rail gauge pressure", "kPa", 0.0, 655350.0, 2, PidCategory.FUEL) {
            one(it.word() * 10.0)
        })
        add(Pid(0x2F, "Fuel tank level", "%", 0.0, 100.0, 1, PidCategory.FUEL, featured = true) {
            one(pct255(it.a()))
        })
        add(Pid(0x44, "Commanded air-fuel equivalence ratio", "λ", 0.0, 2.0, 2, PidCategory.FUEL) {
            one(it.word() / 32768.0)
        })
        add(Pid(0x51, "Fuel type", bytes = 1, category = PidCategory.FUEL) {
            one(it.a().toDouble(), text = fuelType(it.a()))
        })
        add(Pid(0x52, "Ethanol fuel", "%", 0.0, 100.0, 1, PidCategory.FUEL) {
            one(pct255(it.a()))
        })
        add(Pid(0x55, "Short term secondary O2 trim — Bank 1/3", "%", -100.0, 99.2, 2, PidCategory.FUEL) {
            listOf(Reading("Bank 1", trim(it.a()), "%"), Reading("Bank 3", trim(it.b()), "%"))
        })
        add(Pid(0x56, "Long term secondary O2 trim — Bank 1/3", "%", -100.0, 99.2, 2, PidCategory.FUEL) {
            listOf(Reading("Bank 1", trim(it.a()), "%"), Reading("Bank 3", trim(it.b()), "%"))
        })
        add(Pid(0x57, "Short term secondary O2 trim — Bank 2/4", "%", -100.0, 99.2, 2, PidCategory.FUEL) {
            listOf(Reading("Bank 2", trim(it.a()), "%"), Reading("Bank 4", trim(it.b()), "%"))
        })
        add(Pid(0x58, "Long term secondary O2 trim — Bank 2/4", "%", -100.0, 99.2, 2, PidCategory.FUEL) {
            listOf(Reading("Bank 2", trim(it.a()), "%"), Reading("Bank 4", trim(it.b()), "%"))
        })
        add(Pid(0x59, "Fuel rail absolute pressure", "kPa", 0.0, 655350.0, 2, PidCategory.FUEL) {
            one(it.word() * 10.0)
        })
        add(Pid(0x5D, "Fuel injection timing", "°", -210.0, 302.0, 2, PidCategory.FUEL) {
            one((it.word() - 26880) / 128.0)
        })
        add(Pid(0x5E, "Engine fuel rate", "L/h", 0.0, 3212.75, 2, PidCategory.FUEL) {
            one(it.word() / 20.0)
        })
        add(Pid(0x9D, "Engine fuel rate (multi)", "g/s", 0.0, 3212.75, 4, PidCategory.FUEL) {
            listOf(
                Reading("Engine fuel rate", it.word(0) / 32.0, "g/s"),
                Reading("Vehicle fuel rate", it.word(2) / 32.0, "g/s"),
            )
        })
        add(Pid(0xA2, "Cylinder fuel rate", "mg/stroke", 0.0, 2047.96, 2, PidCategory.FUEL) {
            one(it.word() / 32.0)
        })

        // --- Air and intake -------------------------------------------------------------
        add(Pid(0x0B, "Intake manifold absolute pressure", "kPa", 0.0, 255.0, 1, PidCategory.AIR, featured = true) {
            one(it.a().toDouble())
        })
        add(Pid(0x0F, "Intake air temperature", "°C", -40.0, 215.0, 1, PidCategory.TEMPERATURE, featured = true) {
            one(temp(it.a()))
        })
        add(Pid(0x10, "Mass air flow rate", "g/s", 0.0, 655.35, 2, PidCategory.AIR, featured = true) {
            one(it.word() / 100.0)
        })
        add(Pid(0x33, "Absolute barometric pressure", "kPa", 0.0, 255.0, 1, PidCategory.AIR) {
            one(it.a().toDouble())
        })
        add(Pid(0x50, "Maximum value for air flow rate", "g/s", 0.0, 2550.0, 4, PidCategory.AIR) {
            one(it.a() * 10.0)
        })
        add(Pid(0x66, "Mass air flow sensor", "g/s", 0.0, 2047.97, 5, PidCategory.AIR) {
            buildList {
                if (it[0] and 0x01 != 0) add(Reading("Sensor A", it.word(1) / 32.0, "g/s"))
                if (it[0] and 0x02 != 0 && it.size >= 5) add(Reading("Sensor B", it.word(3) / 32.0, "g/s"))
            }
        })
        add(Pid(0x87, "Intake manifold absolute pressure (extended)", "kPa", 0.0, 8031.0, 5, PidCategory.AIR) {
            buildList {
                if (it[0] and 0x01 != 0) add(Reading("Sensor A", it.word(1) / 32.0, "kPa"))
                if (it[0] and 0x02 != 0 && it.size >= 5) add(Reading("Sensor B", it.word(3) / 32.0, "kPa"))
            }
        })

        // --- Temperatures ---------------------------------------------------------------
        add(Pid(0x46, "Ambient air temperature", "°C", -40.0, 215.0, 1, PidCategory.TEMPERATURE) {
            one(temp(it.a()))
        })
        add(Pid(0x5C, "Engine oil temperature", "°C", -40.0, 210.0, 1, PidCategory.TEMPERATURE, featured = true) {
            one(temp(it.a()))
        })
        add(Pid(0x67, "Engine coolant temperature (sensors)", "°C", -40.0, 215.0, 3, PidCategory.TEMPERATURE) {
            buildList {
                if (it[0] and 0x01 != 0) add(Reading("Sensor 1", temp(it[1]), "°C"))
                if (it[0] and 0x02 != 0 && it.size >= 3) add(Reading("Sensor 2", temp(it[2]), "°C"))
            }
        })
        add(Pid(0x68, "Intake air temperature (sensors)", "°C", -40.0, 215.0, 3, PidCategory.TEMPERATURE) {
            buildList {
                if (it[0] and 0x01 != 0) add(Reading("Bank 1 Sensor 1", temp(it[1]), "°C"))
                if (it[0] and 0x02 != 0 && it.size >= 3) add(Reading("Bank 1 Sensor 2", temp(it[2]), "°C"))
            }
        })
        add(Pid(0x3C, "Catalyst temperature — Bank 1 Sensor 1", "°C", -40.0, 6513.5, 2, PidCategory.EMISSIONS) {
            one(it.word() / 10.0 - 40.0)
        })
        add(Pid(0x3D, "Catalyst temperature — Bank 2 Sensor 1", "°C", -40.0, 6513.5, 2, PidCategory.EMISSIONS) {
            one(it.word() / 10.0 - 40.0)
        })
        add(Pid(0x3E, "Catalyst temperature — Bank 1 Sensor 2", "°C", -40.0, 6513.5, 2, PidCategory.EMISSIONS) {
            one(it.word() / 10.0 - 40.0)
        })
        add(Pid(0x3F, "Catalyst temperature — Bank 2 Sensor 2", "°C", -40.0, 6513.5, 2, PidCategory.EMISSIONS) {
            one(it.word() / 10.0 - 40.0)
        })
        add(Pid(0x77, "Charge air cooler temperature", "°C", -40.0, 6513.5, 5, PidCategory.DIESEL) {
            buildList {
                if (it[0] and 0x01 != 0) add(Reading("Bank 1 Sensor 1", it.word(1) / 10.0 - 40.0, "°C"))
                if (it[0] and 0x02 != 0 && it.size >= 5) add(Reading("Bank 1 Sensor 2", it.word(3) / 10.0 - 40.0, "°C"))
            }
        })
        add(Pid(0x78, "Exhaust gas temperature — Bank 1", "°C", -40.0, 6513.5, 9, PidCategory.DIESEL) {
            egt(it, "Bank 1")
        })
        add(Pid(0x79, "Exhaust gas temperature — Bank 2", "°C", -40.0, 6513.5, 9, PidCategory.DIESEL) {
            egt(it, "Bank 2")
        })

        // --- Electrical -----------------------------------------------------------------
        add(Pid(0x42, "Control module voltage", "V", 0.0, 65.535, 2, PidCategory.ELECTRICAL, featured = true) {
            one(it.word() / 1000.0)
        })

        // --- Speed, distance, odometer --------------------------------------------------
        add(Pid(0x21, "Distance travelled with MIL on", "km", 0.0, 65535.0, 2, PidCategory.EMISSIONS) {
            one(it.word().toDouble())
        })
        add(Pid(0x31, "Distance since codes cleared", "km", 0.0, 65535.0, 2, PidCategory.SPEED) {
            one(it.word().toDouble())
        })
        add(Pid(0x4D, "Time run with MIL on", "min", 0.0, 65535.0, 2, PidCategory.EMISSIONS) {
            one(it.word().toDouble())
        })
        add(Pid(0x4E, "Time since trouble codes cleared", "min", 0.0, 65535.0, 2, PidCategory.EMISSIONS) {
            one(it.word().toDouble())
        })
        add(Pid(0x30, "Warm-ups since codes cleared", "", 0.0, 255.0, 1, PidCategory.EMISSIONS) {
            one(it.a().toDouble())
        })
        add(Pid(0xA6, "Odometer", "km", 0.0, 429496729.5, 4, PidCategory.SPEED, featured = true) {
            val raw = (it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()
            one(raw / 10.0)
        })

        // --- Emissions and EGR ----------------------------------------------------------
        add(Pid(0x12, "Commanded secondary air status", bytes = 1, category = PidCategory.EMISSIONS) {
            one(it.a().toDouble(), text = secondaryAirStatus(it.a()))
        })
        add(Pid(0x13, "Oxygen sensors present (2 banks)", bytes = 1, category = PidCategory.OXYGEN) {
            one(it.a().toDouble(), text = "bitmask")
        })
        add(Pid(0x1C, "OBD standards compliance", bytes = 1, category = PidCategory.STATUS) {
            one(it.a().toDouble(), text = obdStandard(it.a()))
        })
        add(Pid(0x1D, "Oxygen sensors present (4 banks)", bytes = 1, category = PidCategory.OXYGEN) {
            one(it.a().toDouble(), text = "bitmask")
        })
        add(Pid(0x1E, "Auxiliary input status", bytes = 1, category = PidCategory.STATUS) {
            one((it.a() and 0x01).toDouble(), text = if (it.a() and 0x01 != 0) "PTO active" else "PTO inactive")
        })
        add(Pid(0x2C, "Commanded EGR", "%", 0.0, 100.0, 1, PidCategory.EMISSIONS) {
            one(pct255(it.a()))
        })
        add(Pid(0x2D, "EGR error", "%", -100.0, 99.2, 1, PidCategory.EMISSIONS) {
            one(trim(it.a()))
        })
        add(Pid(0x2E, "Commanded evaporative purge", "%", 0.0, 100.0, 1, PidCategory.EMISSIONS) {
            one(pct255(it.a()))
        })
        add(Pid(0x32, "Evap system vapour pressure", "Pa", -8192.0, 8192.0, 2, PidCategory.EMISSIONS) {
            // Signed 16-bit, quarter-Pascal resolution.
            val signed = it.word().let { w -> if (w > 32767) w - 65536 else w }
            one(signed / 4.0)
        })
        add(Pid(0x53, "Absolute evap system vapour pressure", "kPa", 0.0, 327.675, 2, PidCategory.EMISSIONS) {
            one(it.word() / 200.0)
        })
        add(Pid(0x54, "Evap system vapour pressure (wide)", "Pa", -32767.0, 32768.0, 2, PidCategory.EMISSIONS) {
            one((it.word() - 32767).toDouble())
        })
        add(Pid(0x5F, "Emission requirements", bytes = 1, category = PidCategory.EMISSIONS) {
            one(it.a().toDouble(), text = "Design type 0x%02X".format(it.a()))
        })
        add(Pid(0x83, "NOx sensor", "ppm", 0.0, 65535.0, 5, PidCategory.DIESEL) {
            buildList {
                if (it[0] and 0x01 != 0) add(Reading("Sensor 1", it.word(1).toDouble(), "ppm"))
                if (it[0] and 0x02 != 0 && it.size >= 5) add(Reading("Sensor 2", it.word(3).toDouble(), "ppm"))
            }
        })
        add(Pid(0x9E, "Engine exhaust flow rate", "kg/h", 0.0, 3276.75, 2, PidCategory.DIESEL) {
            one(it.word() / 5.0)
        })

        // --- Oxygen sensors: narrowband voltage plus its associated trim -----------------
        for (sensor in 0 until 8) {
            val id = 0x14 + sensor
            add(
                Pid(
                    id,
                    "O2 Sensor ${sensorLabel(sensor)}",
                    "V", 0.0, 1.275, 2, PidCategory.OXYGEN,
                    featured = sensor == 0,
                ) { data ->
                    buildList {
                        add(Reading("Voltage", data.a() / 200.0, "V"))
                        // 0xFF in byte B is the standard's "trim not used with this sensor".
                        if (data.b() != 0xFF) add(Reading("Short term trim", trim(data.b()), "%"))
                    }
                }
            )
        }

        // --- Wideband O2: equivalence ratio plus voltage ---------------------------------
        for (sensor in 0 until 8) {
            val id = 0x24 + sensor
            add(
                Pid(id, "O2 Sensor ${sensorLabel(sensor)} (wide range)", "λ", 0.0, 2.0, 4, PidCategory.OXYGEN) { data ->
                    listOf(
                        Reading("Equivalence ratio", data.word(0) / 32768.0, "λ"),
                        Reading("Voltage", data.word(2) / 8192.0, "V"),
                    )
                }
            )
        }

        // --- Wideband O2: equivalence ratio plus current ---------------------------------
        for (sensor in 0 until 8) {
            val id = 0x34 + sensor
            add(
                Pid(id, "O2 Sensor ${sensorLabel(sensor)} (wide range, current)", "λ", 0.0, 2.0, 4, PidCategory.OXYGEN) { data ->
                    listOf(
                        Reading("Equivalence ratio", data.word(0) / 32768.0, "λ"),
                        Reading("Current", data.word(2) / 256.0 - 128.0, "mA"),
                    )
                }
            )
        }

        // --- Diesel, turbo and aftertreatment -------------------------------------------
        add(Pid(0x6B, "EGR temperature", "°C", -40.0, 215.0, 5, PidCategory.DIESEL) {
            buildList {
                if (it[0] and 0x01 != 0) add(Reading("Bank 1", temp(it[1]), "°C"))
                if (it[0] and 0x02 != 0 && it.size >= 3) add(Reading("Bank 2", temp(it[2]), "°C"))
            }
        })
        add(Pid(0x6F, "Turbocharger compressor inlet pressure", "kPa", 0.0, 255.0, 3, PidCategory.DIESEL) {
            buildList {
                if (it[0] and 0x01 != 0) add(Reading("Sensor A", it[1].toDouble(), "kPa"))
                if (it[0] and 0x02 != 0 && it.size >= 3) add(Reading("Sensor B", it[2].toDouble(), "kPa"))
            }
        })
        add(Pid(0x70, "Boost pressure control", "kPa", 0.0, 2047.97, 9, PidCategory.DIESEL) {
            buildList {
                if (it.size >= 5) {
                    add(Reading("Commanded boost A", it.word(1) / 32.0, "kPa"))
                    add(Reading("Actual boost A", it.word(3) / 32.0, "kPa"))
                }
            }
        })
        add(Pid(0x71, "Variable geometry turbo control", "%", 0.0, 100.0, 6, PidCategory.DIESEL) {
            buildList {
                if (it.size >= 3) {
                    add(Reading("Commanded A", pct255(it[1]), "%"))
                    add(Reading("Actual A", pct255(it[2]), "%"))
                }
            }
        })
        add(Pid(0x72, "Wastegate control", "%", 0.0, 100.0, 5, PidCategory.DIESEL) {
            buildList {
                if (it.size >= 5) {
                    add(Reading("Commanded A", it.word(1) / 32.0, "%"))
                    add(Reading("Actual A", it.word(3) / 32.0, "%"))
                }
            }
        })
        add(Pid(0x73, "Exhaust pressure", "kPa", 0.0, 2047.97, 5, PidCategory.DIESEL) {
            buildList {
                if (it[0] and 0x01 != 0 && it.size >= 3) add(Reading("Sensor 1", it.word(1) / 128.0, "kPa"))
                if (it[0] and 0x02 != 0 && it.size >= 5) add(Reading("Sensor 2", it.word(3) / 128.0, "kPa"))
            }
        })
        add(Pid(0x74, "Turbocharger RPM", "rpm", 0.0, 655350.0, 5, PidCategory.DIESEL) {
            buildList {
                if (it[0] and 0x01 != 0 && it.size >= 3) add(Reading("Turbo A", it.word(1) * 10.0, "rpm"))
                if (it[0] and 0x02 != 0 && it.size >= 5) add(Reading("Turbo B", it.word(3) * 10.0, "rpm"))
            }
        })
        add(Pid(0x7C, "Diesel particulate filter temperature", "°C", -40.0, 6513.5, 9, PidCategory.DIESEL) {
            buildList {
                if (it.size >= 5) {
                    add(Reading("Inlet", it.word(1) / 10.0 - 40.0, "°C"))
                    add(Reading("Outlet", it.word(3) / 10.0 - 40.0, "°C"))
                }
                if (it.size >= 7) add(Reading("Bed", it.word(5) / 10.0 - 40.0, "°C"))
            }
        })
        add(Pid(0x7F, "Engine run time (extended)", "s", 0.0, 4294967295.0, 13, PidCategory.ENGINE) {
            buildList {
                if (it.size >= 5) add(Reading("Total run time", dword(it, 1), "s"))
                if (it.size >= 9) add(Reading("Idle run time", dword(it, 5), "s"))
                if (it.size >= 13) add(Reading("PTO run time", dword(it, 9), "s"))
            }
        })
        add(Pid(0x86, "Particulate matter sensor", "mg/m³", 0.0, 8191.75, 5, PidCategory.DIESEL) {
            buildList {
                if (it[0] and 0x01 != 0 && it.size >= 3) add(Reading("Bank 1", it.word(1) / 8.0, "mg/m³"))
                if (it[0] and 0x02 != 0 && it.size >= 5) add(Reading("Bank 2", it.word(3) / 8.0, "mg/m³"))
            }
        })

        // --- Hybrid and transmission ----------------------------------------------------
        add(Pid(0x5B, "Hybrid battery pack remaining life", "%", 0.0, 100.0, 1, PidCategory.HYBRID) {
            one(pct255(it.a()))
        })
        add(Pid(0xA4, "Transmission actual gear", "", 0.0, 65.535, 4, PidCategory.OTHER) {
            buildList {
                if (it[0] and 0x01 != 0 && it.size >= 4) add(Reading("Gear ratio", it.word(2) / 1000.0, ""))
            }
        })
        add(Pid(0xA5, "Diesel exhaust fluid sensor", "%", 0.0, 100.0, 4, PidCategory.DIESEL) {
            buildList {
                if (it.size >= 4) {
                    add(Reading("DEF concentration", it[1] / 2.0, "%"))
                    add(Reading("DEF tank level", pct255(it[2]), "%"))
                    add(Reading("DEF temperature", temp(it[3]), "°C"))
                }
            }
        })

        // The support bitmaps for higher ranges. Kept so the UI can show what's been probed.
        listOf(0x20 to "21-40", 0x40 to "41-60", 0x60 to "61-80", 0x80 to "81-A0", 0xA0 to "A1-C0", 0xC0 to "C1-E0")
            .forEach { (id, range) ->
                add(Pid(id, "Supported PIDs $range", bytes = 4, category = PidCategory.STATUS) {
                    one(0.0, text = "bitmask")
                })
            }
    }.sortedBy { it.id }

    private val byId: Map<Int, Pid> = ALL.associateBy { it.id }

    operator fun get(id: Int): Pid? = byId[id]

    fun contains(id: Int): Boolean = byId.containsKey(id)

    /** PIDs shown on the dashboard when the user hasn't chosen their own set. */
    val DEFAULT_DASHBOARD = listOf(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x42)

    /** The support-bitmap PIDs, which are polled to discover what the car implements. */
    val SUPPORT_PIDS = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    /** Bitmaps and other PIDs that make no sense as a live gauge. */
    fun isPollable(id: Int): Boolean = id !in SUPPORT_PIDS && id != 0x01 && id != 0x02

    private fun sensorLabel(index: Int): String {
        val bank = index / 4 + 1
        val sensor = index % 4 + 1
        return "$bank-$sensor"
    }

    private fun dword(d: IntArray, offset: Int): Double =
        ((d[offset].toLong() shl 24) or (d[offset + 1].toLong() shl 16) or
            (d[offset + 2].toLong() shl 8) or d[offset + 3].toLong()).toDouble()

    private fun egt(d: IntArray, bank: String): List<Reading> = buildList {
        // Low nibble of byte A is a per-sensor support mask for the four sensors following.
        for (sensor in 0 until 4) {
            val offset = 1 + sensor * 2
            if (d[0] and (1 shl sensor) != 0 && d.size >= offset + 2) {
                add(Reading("$bank Sensor ${sensor + 1}", d.word(offset) / 10.0 - 40.0, "°C"))
            }
        }
    }

    fun fuelSystemStatus(value: Int): String = when (value) {
        0 -> "Not present"
        1 -> "Open loop — engine not yet warm"
        2 -> "Closed loop — using O2 sensor feedback"
        4 -> "Open loop — driving conditions (hard accel or decel)"
        8 -> "Open loop — system fault detected"
        16 -> "Closed loop, but one O2 sensor is faulty"
        else -> "Unknown (0x%02X)".format(value)
    }

    fun secondaryAirStatus(value: Int): String = when (value) {
        1 -> "Upstream"
        2 -> "Downstream of catalyst"
        4 -> "From the atmosphere / off"
        8 -> "Pump commanded on for diagnostics"
        else -> "Unknown (0x%02X)".format(value)
    }

    fun fuelType(value: Int): String = when (value) {
        0 -> "Not available"
        1 -> "Petrol"
        2 -> "Methanol"
        3 -> "Ethanol"
        4 -> "Diesel"
        5 -> "LPG"
        6 -> "CNG"
        7 -> "Propane"
        8 -> "Electric"
        9 -> "Bifuel — petrol"
        10 -> "Bifuel — methanol"
        11 -> "Bifuel — ethanol"
        12 -> "Bifuel — LPG"
        13 -> "Bifuel — CNG"
        14 -> "Bifuel — propane"
        15 -> "Bifuel — electric"
        16 -> "Bifuel — electric and combustion"
        17 -> "Hybrid petrol"
        18 -> "Hybrid ethanol"
        19 -> "Hybrid diesel"
        20 -> "Hybrid electric"
        21 -> "Hybrid — mixed"
        22 -> "Hybrid regenerative"
        else -> "Unknown (0x%02X)".format(value)
    }

    fun obdStandard(value: Int): String = when (value) {
        1 -> "OBD-II (California ARB)"
        2 -> "OBD (US EPA)"
        3 -> "OBD and OBD-II"
        4 -> "OBD-I"
        5 -> "Not OBD compliant"
        6 -> "EOBD (Europe)"
        7 -> "EOBD and OBD-II"
        8 -> "EOBD and OBD"
        9 -> "EOBD, OBD and OBD-II"
        10 -> "JOBD (Japan)"
        11 -> "JOBD and OBD-II"
        12 -> "JOBD and EOBD"
        13 -> "JOBD, EOBD and OBD-II"
        17 -> "EMD (Engine Manufacturer Diagnostics)"
        18 -> "EMD+"
        19 -> "HD OBD-C"
        20 -> "HD OBD"
        21 -> "WWH OBD"
        23 -> "HD EOBD-I"
        24 -> "HD EOBD-I N"
        25 -> "HD EOBD-II"
        26 -> "HD EOBD-II N"
        28 -> "OBDBr-1 (Brazil)"
        29 -> "OBDBr-2 (Brazil)"
        30 -> "KOBD (Korea)"
        31 -> "IOBD I (India)"
        32 -> "IOBD II (India)"
        33 -> "HD EOBD-IV"
        else -> "Unknown (0x%02X)".format(value)
    }
}
