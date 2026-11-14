package com.example.careway.vehicle

import com.example.careway.core.*

enum class DemoScenario(val label: String) {
    PARKED("출발 준비"), NIGHT("야간 미점등"), RAIN("야간 빗길"),
    TUNNEL_ENTRY("터널 180m 전"), TUNNEL_INSIDE("터널 안"), TUNNEL_EXIT("출구 80m 전"),
    GLARE("직사광선"), CLEAR("통과 후 복원"), RAIN_TUNNEL("빗길 + 터널"), DISCONNECTED("센서 단절")
}

class DemoVehicle : VehicleGateway {
    var scenario = DemoScenario.PARKED
    var brakePressed = false
    var headlights = false
    var failure = false
    val values = Control.entries.associateWith { if (it == Control.AMBIENT) 65 else 0 }.let { java.util.concurrent.ConcurrentHashMap(it) }
    override fun capability(control: Control) = Capability(true, 0,
        when (control) { Control.AMBIENT -> 100; Control.GLASS_FRONT, Control.GLASS_ROOF -> 5; else -> 1 })
    override fun read(control: Control): Int {
        check(!failure) { "시뮬레이션: 차량 통신 실패" }
        return values.getValue(control)
    }
    override fun write(control: Control, value: Int) {
        check(!failure) { "시뮬레이션: 차량 통신 실패" }
        values[control] = value
    }
    fun snapshot(now: Long): VehicleSnapshot {
        if (scenario == DemoScenario.DISCONNECTED) return VehicleSnapshot()
        val parked = scenario == DemoScenario.PARKED
        val dark = scenario in setOf(DemoScenario.NIGHT, DemoScenario.RAIN, DemoScenario.TUNNEL_INSIDE, DemoScenario.RAIN_TUNNEL)
        val rain = scenario in setOf(DemoScenario.RAIN, DemoScenario.RAIN_TUNNEL)
        val tunnel = when (scenario) {
            DemoScenario.TUNNEL_ENTRY -> Tunnel(TunnelPhase.APPROACH, 180.0)
            DemoScenario.TUNNEL_INSIDE, DemoScenario.RAIN_TUNNEL -> Tunnel(TunnelPhase.INSIDE)
            DemoScenario.TUNNEL_EXIT -> Tunnel(TunnelPhase.EXIT, 80.0)
            else -> Tunnel(TunnelPhase.CLEAR)
        }
        return VehicleSnapshot(Reading(if (parked) 0f else 16.67f, now),
            Reading(if (parked) Gear.PARK else Gear.DRIVE, now), Reading(parked, now),
            Reading(brakePressed, now), Reading(dark, now), Reading(headlights, now),
            Reading(rain, now), Reading(tunnel, now), Reading(scenario == DemoScenario.GLARE, now),
            uxRestricted = Reading(false, now), brakeFromEvent = true,
            wiperLevel = Reading(if (rain) "CONTINUOUS_LEVEL_2" else "OFF", now))
    }
}
