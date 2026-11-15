package com.example.careway.core

import java.util.concurrent.TimeoutException
import java.util.concurrent.CancellationException


data class Ownership(val original: Int, val applied: Int, val confirmed: Boolean = false)
interface OwnershipStore {
    fun load(): Map<Control, Ownership>
    fun save(value: Map<Control, Ownership>)
}

/**
 * Serialized reconciliation with read-back, persisted restoration, backoff and manual override.
 *
 * A request is sent once and then confirmed by read-back on later passes, so a vehicle that applies
 * a setting slowly never blocks the other controls. Success is reported only after the value was read back.
 */
class ActionCoordinator(
    private val gateway: VehicleGateway,
    private val store: OwnershipStore,
    private val report: (String, String) -> Unit,
) {
    private val owned = store.load().toMutableMap()
    private val suspended = mutableSetOf<Control>()
    private val retryAt = mutableMapOf<Control, Long>()
    private val lastStatus = mutableMapOf<Control, String>()
    // In-memory only: when the latest unconfirmed request / restoration was sent in this process.
    private val sentAt = mutableMapOf<Control, Long>()
    private val attempts = mutableMapOf<Control, Int>()
    private val restoring = mutableMapOf<Control, Long>()
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
                if (target != null) restoring.remove(control)
                val restoreSent = restoring[control]
                if (ownership != null && restoreSent != null) {
                    if (current == ownership.original) {
                        release(control)
                        status(control, "복원 완료", "자동 제어 전 설정으로 돌아왔습니다")
                        continue
                    }
                    if (current == ownership.applied && now - restoreSent < RESEND_MS) {
                        retryAt[control] = now + RECHECK_MS
                        continue
                    }
                    restoring.remove(control) // Request again below, or yield to a manual change.
                }
                if (ownership != null && !ownership.confirmed && current == ownership.original && current != ownership.applied) {
                    val sent = sentAt[control]
                    if (sent != null && target == ownership.applied && now - sent < RESEND_MS) {
                        // The request was delivered; keep watching the read-back instead of sending it again.
                        retryAt[control] = now + RECHECK_MS
                        continue
                    }
                    // Acknowledged but never applied is a retryable write, not a driver override.
                    val sentCount = attempts[control]
                    release(control); ownership = null
                    if (target != null && sentCount != null) attempts[control] = sentCount
                    if (target == null) {
                        // A request from this session may still land late: queue the original value behind it.
                        if (sent != null) gateway.write(control, current)
                        status(control, "원래 설정 확인", "미적용 명령의 복원 대기를 해제했습니다")
                        continue
                    }
                }
                if (ownership != null && current != ownership.applied) {
                    release(control)
                    if (target != null) suspended += control
                    status(control, "운전자 설정 우선", "설정 변경을 감지하여 자동 제어를 멈췄습니다")
                    continue
                }
                if (ownership != null && !ownership.confirmed && current == ownership.applied) {
                    ownership = ownership.copy(confirmed = true)
                    owned[control] = ownership; store.save(owned)
                    sentAt.remove(control); attempts.remove(control)
                }
                if (target == null && ownership != null) {
                    check(cap.accepts(ownership.original)) { "복원 값의 지원 범위가 바뀌었습니다" }
                    status(control, "복원 중", "차량이 원래 설정으로 바뀌는지 확인합니다")
                    gateway.write(control, ownership.original)
                    if (gateway.read(control) == ownership.original) {
                        release(control)
                        status(control, "복원 완료", "자동 제어 전 설정으로 돌아왔습니다")
                    } else {
                        restoring[control] = now; retryAt[control] = now + RECHECK_MS
                        status(control, "복원 대기", "원래 설정을 요청했고 차량 반영을 기다립니다")
                        continue
                    }
                } else if (target != null && current != target) {
                    if (!stillWanted(control, wanted!!)) continue
                    // Persist BEFORE the request: process death after a successful IPC must remain recoverable.
                    owned[control] = Ownership(ownership?.original ?: current, target)
                    store.save(owned)
                    val attempt = (attempts[control] ?: 0) + 1
                    attempts[control] = attempt
                    status(control, "적용 확인 중", "요청한 값이 차량에 반영되는지 확인합니다")
                    gateway.write(control, target)
                    if (gateway.read(control) == target) {
                        owned[control] = owned.getValue(control).copy(confirmed = true); store.save(owned)
                        sentAt.remove(control); attempts.remove(control)
                        status(control, "적용 확인", "차량에서 적용된 값을 다시 읽었습니다")
                    } else {
                        // Not a success yet: only a read-back that matches is reported as applied.
                        sentAt[control] = now; retryAt[control] = now + RECHECK_MS
                        status(control, "반영 대기", if (attempt == 1) "요청을 전달했고 차량 값이 바뀌는지 확인하고 있습니다"
                            else "요청을 ${attempt}회 전달했고 차량 값이 바뀌는지 확인하고 있습니다")
                        continue
                    }
                } else if (target != null) {
                    if (ownership != null) status(control, "적용 확인", "차량에서 적용된 값을 다시 읽었습니다")
                    else status(control, "설정 유지", "필요한 설정이 이미 적용되어 있습니다")
                }
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
    private fun release(control: Control) {
        owned.remove(control); store.save(owned)
        sentAt.remove(control); attempts.remove(control); restoring.remove(control); retryAt.remove(control)
    }
    private fun status(c: Control, state: String, detail: String) {
        val text = "$state · $detail"
        if (lastStatus.put(c, text) != text) report(c.label, text)
    }
    companion object {
        /** How often an unconfirmed value is read again. */
        const val RECHECK_MS = 1_000L
        /** A request that was delivered but still not visible after this long is sent again. */
        const val RESEND_MS = 15_000L
    }
}
