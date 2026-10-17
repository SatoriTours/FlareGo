package com.flarego.core.model

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.ports.HttpResponseHead

const val DNS_IMPORT_MAX_BYTES: Long = 262144
const val DNS_IMPORT_OPERATION = "dns-records-for-a-zone-import-dns-records"
const val DNS_EXPORT_OPERATION = "dns-records-for-a-zone-export-dns-records"
data class DnsFileRef(val id: String, val displayName: String, val length: Long, val sha256: String)
data class DnsExportTarget(val id: String, val displayName: String)
data class DnsExportResult(val scope: ZoneScope, val file: DnsFileRef, val head: HttpResponseHead)
data class DnsImportReadback(val scope: ZoneScope, val observedRecords: Int, val total: Long?)
sealed interface DnsImportOutcome {
    data class ReportedSuccess(val added: Long, val parsed: Long, val readback: DnsImportReadback) : DnsImportOutcome
    data class Partial(val added: Long?, val parsed: Long?, val readback: DnsImportReadback?) : DnsImportOutcome
    data class Unknown(val reason: CloudFailureCode = CloudFailureCode.UNKNOWN_RESULT) : DnsImportOutcome
    fun receipt(): ExecutionReceipt = when (this) {
        is ReportedSuccess -> ExecutionReceipt.Completed()
        is Partial -> ExecutionReceipt.Partial("供应商仅报告部分新增或同时返回错误；其余语义尚未核对，不要重复导入")
        is Unknown -> ExecutionReceipt.Unknown()
    }
}
interface DnsFilesAuthority {
    /** Verified Cloudflare write permission for current credential/scope; not successful reads or local consent. */
    fun mayImport(scope: ZoneScope): Boolean? = null
}
data class DnsFileImportState(val owner: ZoneDetails, val writeAllowed: Boolean?)
data class DnsImportPreview(val scope: ZoneScope, val domain: String, val file: DnsFileRef, val proxied: Boolean,
    val recordCount: Int?, val warnings: List<String>)
internal fun dnsFilesUnavailable() = CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, "DNS 文件操作尚未实现，当前不可发送")
