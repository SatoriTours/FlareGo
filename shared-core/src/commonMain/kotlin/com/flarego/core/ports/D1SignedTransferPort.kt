package com.flarego.core.ports

import com.flarego.core.model.D1DownloadLocation
import com.flarego.core.model.D1UploadLocation

/** Isolated client: never the credential-bearing fixed Cloudflare API transport. */
interface D1SignedTransferPort {
    /** A single PUT; verifies length and the response ETag against MD5 before returning. */
    suspend fun upload(location: D1UploadLocation, content: D1Content, md5: String)
    /** A single GET, no redirects/retries/cookies/Authorization; closes sink on every path. */
    suspend fun download(location: D1DownloadLocation, sink: ByteSink): Long
}

/** Platform hashes every byte of the authenticated sealed file with bounded memory. */
fun interface D1ContentFingerprint { suspend fun md5(content: D1Content): String }
