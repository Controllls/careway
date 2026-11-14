package com.example.careway.core

import java.util.concurrent.TimeoutException
import java.util.concurrent.CancellationException


data class Ownership(val original: Int, val applied: Int, val confirmed: Boolean = false)
interface OwnershipStore {
    fun load(): Map<Control, Ownership>
    fun save(value: Map<Control, Ownership>)
}

/** Serialized reconciliation with read-back, persisted restoration, backoff and manual override. */
class ActionCoordinator(
    private val gateway: VehicleGateway,
    private val store: OwnershipStore,
    private val report: (String, String) -> Unit,
) {
    private val owned = store.load().toMutableMap()
    private val suspended = mutableSetOf<Control>()
    private val retryAt = mutableMapOf<Control, Long>()
    private val lastStatus = mutableMapOf<Control, String>()
    val statuses: Map<Control, String> get() = lastStatus.toMap()
    val pendingRestoration get() = owned.isNotEmpty()

    fun reconcile(desired: Map<Control, Int>, now: Long, stillWanted: (Control, Int) -> Boolean = { _, _ -> true }) {
        suspended.retainAll(desired.keys)
        for (control in Control.entries) {
            if (now < (retryAt[control] ?: Long.MIN_VALUE)) continue
            val wanted = desired[control]
            var ownership = owned[control]
            if (wanted == null && ownership == null) continue
            if (control in suspended) continue
            try {
                val cap = gateway.capability(control)
                check(cap.supported) { cap.reason.ifBlank { "이 차량에서 지원하지 않습니다" } }
                val target = if (control == Control.AMBIENT && wanted == 0) cap.min else wanted
                if (target != null) check(cap.accepts(target)) { "지원 범위를 벗어난 제어 값입니다" }
                val current = gateway.read(control)
                if (ownership != null && !ownership.confirmed && current == ownership.original && current != ownership.applied) {
                    // Acknowledged but never applied is a retryable write, not a driver override.
                    owned.remove(control); store.save(owned); ownership = null
                    if (target == null) {
                        status(control, "원래 설정 확인", "미적용 명령의 복원 대기를 해제했습니다")
                        continue
                    }
                }
                if (ownership != null && current != ownership.applied) {
                    owned.remove(control); store.save(owned)
                    if (target != null) suspended += control
                    status(control, "운전자 설정 우선", "설정 변경을 감지하여 자동 제어를 멈췄습니다")
                    continue
                }
                if (ownership != null && !ownership.confirmed && current == ownership.applied) {
                    ownership = ownership.copy(confirmed = true)
                    owned[control] = ownership; store.save(owned)
                }
                if (target == null && ownership != null) {
                    check(cap.accepts(ownership.original)) { "복원 값의 지원 범위가 바뀌었습니다" }
                    status(control, "복원 중", "차량이 원래 설정으로 바뀌는지 확인합니다")
                    gateway.write(control, ownership.original)
                    check(gateway.read(control) == ownership.original) { "복원 확인 대기" }
                    owned.remove(control); store.save(owned)
                    status(control, "복원 완료", "자동 제어 전 설정으로 돌아왔습니다")
                } else if (target != null && current != target) {
                    if (!stillWanted(control, wanted!!)) continue
                    // Persist BEFORE the request: process death after a successful IPC must remain recoverable.
                    owned[control] = Ownership(ownership?.original ?: current, target)
                    store.save(owned)
                    status(control, "적용 확인 중", "요청한 값이 차량에 반영되는지 확인합니다")
                    gateway.write(control, target)
                    check(gateway.read(control) == target) { "차량 적용 확인 실패" }
                    owned[control] = owned.getValue(control).copy(confirmed = true); store.save(owned)
                    status(control, "적용 확인", "차량에서 적용된 값을 다시 읽었습니다")
                } else if (target != null) status(control, "설정 유지", "필요한 설정이 이미 적용되어 있습니다")
                retryAt.remove(control)
            } catch (e: TimeoutException) {
                retryAt[control] = now + 15_000
                status(control, "제어 보류", "차량 응답 시간 초과")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                retryAt[control] = now + 15_000
                status(control, "제어 보류", e.message ?: "차량 통신 실패")
            }
        }
    }
    private fun status(c: Control, state: String, detail: String) {
        val text = "$state · $detail"
        if (lastStatus.put(c, text) != text) report(c.label, text)
    }
}
