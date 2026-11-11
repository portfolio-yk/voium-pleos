package com.example.pleos.car

import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener

/**
 * Pleos 차량 정보를 한곳에서 받아 화면에 알려 줍니다(앱 전체에서 하나).
 *
 * - 좌석에 앉은 사람 수 (Vehicle › Seat, CAR_SEATS) → 상태 줄의 "탑승 N명"
 * - 기어 (Vehicle › Gear, CAR_DRIVE) → P일 때만 프로필 편집·목소리 등록
 * - 실내 조명 색 (Vehicle › Light, CAR_LIGHTS) → 말하는 사람의 색으로 바꾸고, 끝나면 원래 색으로
 * - 내비게이션 목적지·경유지 (NaviHelper, NAVI_ROUTE) → 대화 제안("휴게소까지 20분")
 *
 * 차종에 따라 없는 기능은 null(모름)로 두고, 화면은 기존 방식(말한 사람 수 등)으로 대신합니다.
 * SDK 콜백은 다른 스레드에서 올 수 있어 모두 메인 스레드로 옮겨 처리합니다.
 */
object CarSignals {

    /** 다음 목적지(가장 가까운 경유지, 없으면 최종 목적지)와 남은 시간 */
    data class Trip(val place: String, val remainSeconds: Int)

    fun interface Listener {
        fun onCarSignalsChanged()
    }

    private const val TAG = "CarSignals"
    private const val TRIP_REFRESH_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var started = false
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null

    // ---------------------------------------------------------------- 지금 상태 (모르면 null)

    /** 사람이 앉은 좌석 수. 좌석 센서를 쓸 수 없으면 null */
    var occupiedSeats: Int? = null
        private set

    /** 기어가 P인지. 기어 정보를 쓸 수 없으면 null(편집을 막지 않음) */
    var isParked: Boolean? = null
        private set

    /** 실내 조명 색을 바꿀 수 있는지. 확인 전이면 null */
    var lightsSupported: Boolean? = null
        private set

    /** 비가 오는지(앞 유리 와이퍼가 작동 중인지). 알 수 없으면 null */
    var raining: Boolean? = null
        private set

    /** 내비게이션 안내 중인 다음 목적지. 안내 중이 아니면 null */
    var trip: Trip? = null
        private set

    private val seatStates = HashMap<SeatArea, Boolean>()
    private var lightAreas: List<SeatArea> = emptyList()
    private val originalLightColors = HashMap<SeatArea, Int>()
    private var appliedLightColor: Int? = null
    private var destination: DestinationInfo? = null
    private var waypoints: List<WaypointInfo> = emptyList()
    private var routing: Boolean? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        // 서비스 연결 전에 부른 API는 SDK가 모아 두었다가 연결되면 보냅니다.
        val car = Vehicle(app).also { it.initialize() }
        vehicle = car
        watchSeats(car)
        watchGear(car)
        checkLights(car)
        watchWiper(car)
        startNavi(app)
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun changed() {
        // 확인용: adb logcat -s CarSignals
        Log.i(TAG, "seats=$occupiedSeats parked=$isParked lights=$lightsSupported rain=$raining trip=$trip")
        main.post { listeners.toList().forEach { it.onCarSignalsChanged() } }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ---------------------------------------------------------------- 좌석

    private fun watchSeats(car: Vehicle) {
        val seat = car.getSeat()
        seat.checkSeatOccupancyCapabilities(
            onComplete = { config, areas ->
                onMain {
                    if (config.accessType == AccessType.NONE || areas.isEmpty()) {
                        Log.i(TAG, "seat occupancy not supported")
                        return@onMain
                    }
                    for (area in areas) {
                        seat.getCurrentSeatOccupancy(area, { occupied -> onMain { updateSeat(area, occupied) } }, { log("seat get", it) })
                        seat.registerSeatOccupancy(area, object : SeatOccupancyListener {
                            override fun onSeatOccupancyUpdated(occupancy: Boolean?) = onMain { updateSeat(area, occupancy) }
                            override fun onFailed(e: Exception) = log("seat listen", e)
                        })
                    }
                }
            },
            onFailed = { log("seat capability", it) },
        )
    }

    private fun updateSeat(area: SeatArea, occupied: Boolean?) {
        if (occupied == null) seatStates.remove(area) else seatStates[area] = occupied
        val count = if (seatStates.isEmpty()) null else seatStates.values.count { it }
        if (count != occupiedSeats) {
            occupiedSeats = count
            changed()
        }
    }

    // ---------------------------------------------------------------- 기어

    private fun watchGear(car: Vehicle) {
        val gear = car.getGear()
        gear.getCurrentGearSelectionState({ onMain { updateGear(it) } }, { log("gear get", it) })
        gear.registerGearSelection(object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = onMain { updateGear(gear) }
            override fun onFailed(e: Exception) = log("gear listen", e)
        })
    }

    private fun updateGear(gear: VehicleGear?) {
        val parked = when (gear) {
            null, VehicleGear.GEAR_UNKNOWN -> null
            else -> gear == VehicleGear.GEAR_PARK
        }
        if (parked != isParked) {
            isParked = parked
            changed()
        }
    }

    // ---------------------------------------------------------------- 와이퍼 (대화 제안의 날씨 카드)

    private fun watchWiper(car: Vehicle) {
        runCatching {
            car.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), object : WiperStateListener {
                override fun onWiperStateUpdated(state: WiperState?) = onMain {
                    val rain = when (state) {
                        WiperState.ON -> true
                        WiperState.OFF -> false
                        else -> null
                    }
                    if (rain != raining) {
                        raining = rain
                        changed()
                    }
                }

                override fun onFailed(e: Exception) = log("wiper listen", e)
            })
        }.onFailure { log("wiper", it) }
    }

    // ---------------------------------------------------------------- 실내 조명

    private fun checkLights(car: Vehicle) {
        val light = car.getLight()
        light.checkAmbientLightBrightnessColorCapabilities(
            onComplete = { config, areas ->
                onMain {
                    val writable = config.accessType == AccessType.WRITE || config.accessType == AccessType.READ_WRITE
                    lightAreas = if (writable) areas else emptyList()
                    lightsSupported = lightAreas.isNotEmpty()
                    // 자막을 쓰지 않을 때 되돌릴 원래 색을 기억해 둡니다.
                    for (area in lightAreas) {
                        light.getAmbientLightBrightnessColor(area, { color -> if (color != null) onMain { originalLightColors.putIfAbsent(area, color) } }, { log("light get", it) })
                    }
                    changed()
                }
            },
            onFailed = {
                onMain {
                    lightsSupported = false
                    changed()
                }
                log("light capability", it)
            },
        )
    }

    /**
     * 조명 밝기(설정 › 앰비언트 라이트 › 밝기): 어둡게 0.45, 보통 0.75, 밝게 1.0.
     * 색 자체를 그만큼 어둡게 보내므로 차량에 설정된 조명 밝기를 넘지 않습니다.
     */
    var cabinBrightness: Float = 0.75f
        set(value) {
            if (field == value) return
            field = value
            applyCabinColor()
        }

    private var requestedLightColor: Int? = null

    /**
     * 실내 조명을 이 색(말하는 사람의 색)으로 바꿉니다. null이면 원래 색으로 되돌립니다.
     * 같은 색을 다시 보내지 않습니다.
     */
    fun setCabinColor(color: Int?) {
        requestedLightColor = color
        applyCabinColor()
    }

    private fun applyCabinColor() {
        val light = vehicle?.getLight() ?: return
        if (lightAreas.isEmpty()) return
        val target = requestedLightColor?.let { scaleBrightness(0xFF000000.toInt() or (it and 0xFFFFFF), cabinBrightness) }
        if (target == appliedLightColor) return
        appliedLightColor = target
        for (area in lightAreas) {
            val value = target ?: originalLightColors[area] ?: continue
            light.setAmbientLightBrightnessColor(area, value, { _, _ -> }, { log("light set", it) })
        }
    }

    private fun scaleBrightness(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    // ---------------------------------------------------------------- 내비게이션

    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = onMain {
            destination = destinationInfo
            updateTrip()
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = onMain {
            waypoints = waypointInfoList
            updateTrip()
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = onMain {
            routing = routeStateInfo.isRouting
            updateTrip()
        }

        override fun onRoutingCompleted() = requestTrip()
        override fun onReRouteEnded() = requestTrip()
        override fun onDestinationChanged() = requestTrip()
        override fun onWaypointChanged(waypointChangedInfo: ai.pleos.playground.navi.data.WaypointChangedInfo) = requestTrip()

        override fun onRouteCancelled() = onMain { clearTrip() }
        override fun onDestinationArrived(destinationArrivedInfo: ai.pleos.playground.navi.data.DestinationArrivedInfo) = onMain { clearTrip() }
    }

    private val tripRefresh = object : Runnable {
        override fun run() {
            requestTrip()
            main.postDelayed(this, TRIP_REFRESH_MS)
        }
    }

    private fun startNavi(context: Context) {
        val helper = NaviHelper(context)
        navi = helper
        helper.addListener(naviListener)
        helper.initialize(
            onConnected = { main.post(tripRefresh) },
            onFailed = { log("navi connect", it) },
        )
    }

    /** 남은 시간은 계속 줄어들어 1분마다, 그리고 경로가 바뀔 때마다 다시 묻습니다. */
    private fun requestTrip() {
        val helper = navi ?: return
        runCatching {
            helper.getRouteStateInfo()
            helper.getDestinationInfo()
            helper.getWaypointInfo()
        }.onFailure { log("navi request", it) }
    }

    private fun clearTrip() {
        destination = null
        waypoints = emptyList()
        routing = false
        updateTrip()
    }

    private fun updateTrip() {
        val next = if (routing == false) null else TripPlanner.nextStop(destination, waypoints)
        if (next != trip) {
            trip = next
            changed()
        }
    }

    /**
     * 디버그 빌드에서 시연·확인용으로 차량 상태를 흉내 냅니다(DebugCarReceiver만 부릅니다).
     * 실제 차량 값이 다시 들어오면 그 값으로 바뀝니다.
     */
    fun simulate(
        seats: Int? = occupiedSeats,
        parked: Boolean? = isParked,
        trip: Trip? = this.trip,
        lights: Boolean? = lightsSupported,
        rain: Boolean? = raining,
    ) {
        raining = rain
        occupiedSeats = seats
        lightsSupported = lights
        isParked = parked
        this.trip = trip
        changed()
    }

    private fun log(what: String, e: Throwable) {
        Log.w(TAG, "$what failed: ${e.message}")
    }
}

/** 내비게이션 정보에서 대화 제안에 쓸 "다음 목적지"를 고릅니다. */
object TripPlanner {
    /** 아직 도착하지 않은 첫 경유지, 없으면 최종 목적지. 이름이나 남은 시간이 없으면 null */
    fun nextStop(destination: DestinationInfo?, waypoints: List<WaypointInfo>): CarSignals.Trip? {
        waypoints.firstOrNull { it.name.isNotBlank() && it.remainTime > 0 }?.let {
            return CarSignals.Trip(it.name.trim(), it.remainTime)
        }
        val dest = destination ?: return null
        if (dest.name.isBlank() || dest.remainTime <= 0) return null
        return CarSignals.Trip(dest.name.trim(), dest.remainTime)
    }
}
