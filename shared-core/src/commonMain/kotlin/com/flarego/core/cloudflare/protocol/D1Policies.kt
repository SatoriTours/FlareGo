package com.flarego.core.cloudflare.protocol

/** Maximum effect, never inferred from the first word of a SQL program. */
internal fun d1Registrations(): Array<ActionRegistration> {
    val base = "/accounts/{account_id}/d1/database"
    val db = "$base/{database_id}"
    fun read(key: String, path: String) = ActionRegistration(
        ActionPolicy(key, ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET", path, ActionScope.ACCOUNT,
    )
    fun write(key: String, method: String, path: String, risk: RiskLevel = RiskLevel.DESTRUCTIVE) = ActionRegistration(
        ActionPolicy(key, ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, risk, ReadRetryPolicy()),
        method, path, ActionScope.ACCOUNT,
    )
    return arrayOf(
        read("d1-list-databases", base),
        write("d1-create-database", "POST", base, RiskLevel.CHANGE),
        read("d1-get-database", db),
        write("d1-update-database", "PUT", db, RiskLevel.CHANGE),
        write("d1-update-partial-database", "PATCH", db, RiskLevel.CHANGE),
        write("d1-delete-database", "DELETE", db),
        write("d1-undelete-database", "POST", "$db/undelete"),
        write("d1-query-database", "POST", "$db/query"),
        write("d1-raw-database-query", "POST", "$db/raw"),
        write("d1-export-database", "POST", "$db/export"),
        write("d1-import-database", "POST", "$db/import"),
        read("d1-time-travel-get-bookmark", "$db/time_travel/bookmark"),
        write("d1-time-travel-restore", "POST", "$db/time_travel/restore"),
    )
}
