package com.flarego.core.ports

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.R2NotificationCollection
import com.flarego.core.model.R2NotificationDeletion
import com.flarego.core.model.R2NotificationWrite
import com.flarego.core.model.R2NotificationQueue
import com.flarego.core.model.R2NotificationRuleSpec
import com.flarego.core.model.R2WriteTarget

/**
 * Typed port for the reviewed batch C event-notification plane (two reads, two writes).
 *
 * `appendRules` sends only new rules; the pinned `PUT` appends, so an existing rule's identity is
 * never re-submitted. `deleteRules` is either a non-empty explicit selection or the explicit
 * whole-queue clear; the two never share a payload shape. Both writes return only an
 * [ExecutionReceipt]; the controller's same-lock independent read-back belongs to Task 2.
 */
interface R2NotificationPort {
    suspend fun listConfigurations(target: R2WriteTarget): R2NotificationCollection

    suspend fun getConfiguration(target: R2WriteTarget, queueId: String): R2NotificationQueue

    suspend fun appendRules(target: R2WriteTarget, queueId: String, rules: List<R2NotificationRuleSpec>): ExecutionReceipt

    suspend fun deleteRules(target: R2WriteTarget, queueId: String, deletion: R2NotificationDeletion): ExecutionReceipt
}

/**
 * The credential-bound event-notification adapter used by the batch C controller.
 *
 * The write hands the client's **own** fresh pre-write observation to [beforeSend]: the caller
 * compares the document it is about to modify with the frozen preview, and the typed send follows
 * immediately. A caller cannot substitute another read here, so the guard can never compare against a
 * baseline that is not the one being written.
 */
interface R2NotificationSession : R2NotificationPort {
    suspend fun writeNotification(
        write: R2NotificationWrite,
        beforeSend: (R2NotificationQueue) -> Unit,
    ): ExecutionReceipt
}
