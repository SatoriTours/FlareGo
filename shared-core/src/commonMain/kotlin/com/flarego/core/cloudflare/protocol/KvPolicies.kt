package com.flarego.core.cloudflare.protocol

/** Standard Workers KV. The deprecated DELETE /bulk is deliberately not sendable. */
internal fun kvRegistrations(): Array<ActionRegistration> {
    val namespaces = "/accounts/{account_id}/storage/kv/namespaces"
    val namespace = "$namespaces/{namespace_id}"
    fun read(id: String, path: String, method: String = "GET", kind: ResponseKind = ResponseKind.ENVELOPE_JSON) =
        ActionRegistration(
            ActionPolicy("workers-kv-namespace-$id", ActionSemantics.READ, kind, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
            method, path, ActionScope.ACCOUNT,
        )
    fun write(id: String, method: String, path: String, risk: RiskLevel = RiskLevel.DESTRUCTIVE) =
        ActionRegistration(
            ActionPolicy("workers-kv-namespace-$id", ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, risk, ReadRetryPolicy()),
            method, path, ActionScope.ACCOUNT,
        )
    return arrayOf(
        read("list-namespaces", namespaces),
        write("create-a-namespace", "POST", namespaces, RiskLevel.CHANGE),
        read("get-a-namespace", namespace),
        write("rename-a-namespace", "PUT", namespace, RiskLevel.CHANGE),
        write("remove-a-namespace", "DELETE", namespace),
        read("list-a-namespace'-s-keys", "$namespace/keys"),
        read("read-the-metadata-for-a-key", "$namespace/metadata/{key_name}"),
        read("read-key-value-pair", "$namespace/values/{key_name}", kind = ResponseKind.BINARY),
        write("write-key-value-pair-with-metadata", "PUT", "$namespace/values/{key_name}"),
        write("delete-key-value-pair", "DELETE", "$namespace/values/{key_name}"),
        read("get-multiple-key-value-pairs", "$namespace/bulk/get", method = "POST"),
        write("write-multiple-key-value-pairs", "PUT", "$namespace/bulk"),
        write("delete-multiple-key-value-pairs", "POST", "$namespace/bulk/delete"),
    )
}
