package com.flarego.core.cloudflare.protocol

/**
 * Handwritten R2 batch C event-notification allowlist: exactly the four pinned operations.
 *
 * The two reads keep the bounded read-only retry. `PUT` appends new rules and is a CHANGE; the
 * `DELETE` can remove every rule of the bucket/queue and is DESTRUCTIVE. Both writes never retry.
 */
internal fun r2NotificationRegistrations(): Array<ActionRegistration> {
    val base = "/accounts/{account_id}/event_notifications/r2/{bucket_name}/configuration"
    fun read(id: String, path: String) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET",
        path,
        ActionScope.ACCOUNT,
    )
    fun write(id: String, method: String, path: String, risk: RiskLevel) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, risk, ReadRetryPolicy()),
        method,
        path,
        ActionScope.ACCOUNT,
    )
    return arrayOf(
        read(R2_LIST_NOTIFICATIONS_KEY, base),
        read(R2_GET_NOTIFICATION_KEY, "$base/queues/{queue_id}"),
        write(R2_APPEND_NOTIFICATION_KEY, "PUT", "$base/queues/{queue_id}", RiskLevel.CHANGE),
        write(R2_DELETE_NOTIFICATION_KEY, "DELETE", "$base/queues/{queue_id}", RiskLevel.DESTRUCTIVE),
    )
}

internal const val R2_LIST_NOTIFICATIONS_KEY: String = "r2-get-event-notification-configs"
internal const val R2_GET_NOTIFICATION_KEY: String = "r2-get-event-notification-config"
internal const val R2_APPEND_NOTIFICATION_KEY: String = "r2-put-event-notification-config"
internal const val R2_DELETE_NOTIFICATION_KEY: String = "r2-event-notification-delete-config"
