package com.flarego

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.flarego.core.application.dns.DnsImportLimiter
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.identity.TokenAuthorityIssuer
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.platform.DnsDocumentFiles
import com.flarego.platform.CloudflareHttpDates
import java.util.UUID
import java.util.IdentityHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Public local lifecycle metadata; never Token permission or a confirmation. */
data class ManagementCredentialStamp(val connectionId:String,val accountId:String,val revision:Long,val generation:Long)
class ManagementCredentialTransition internal constructor(internal val id:String)
class ManagementCredentialBundle internal constructor(val stamp:ManagementCredentialStamp,val gateway:CloudflareGateway,
    val transfers:TransferPort,val issuer:TokenAuthorityIssuer)
class ManagementPlatformBorrower internal constructor(internal val id:String)
class ManagedDocumentLease internal constructor(internal val id:String,val file:DnsFileRef?,val target:DnsExportTarget?,val jobOwned:Boolean)

/** One Application owner; unrelated to the operation runtime/confirmation/whole-app write lock. */
class ManagementPlatformOwner private constructor(application:Application) {
    companion object {
        private val owners=IdentityHashMap<Application,ManagementPlatformOwner>()
        @Synchronized fun forApplication(application:Application):ManagementPlatformOwner =
            owners.getOrPut(application) { ManagementPlatformOwner(application) }
    }
    private val lock=Any()
    private val clock=OperationClock { System.currentTimeMillis() }
    val importLimiter:DnsImportLimiter by lazy { DnsImportLimiter(clock) }
    private val appContext=application.applicationContext
    private val documents=DnsDocumentFiles(appContext)
    private val fileLock=Mutex()
    private data class Grant(val uri:Uri,val baseline:Int,var added:Int,var documents:Int=0)
    private inner class Document(val uri:Uri,val grant:Grant) {
        var file:DnsFileRef?=null;var target:DnsExportTarget?=null;var transaction:OwnedExport?=null
        var committed:DnsFileRef?=null;var refs=0;var pending=false;var uncertainResolve=false;var uncertainBegin=false
    }
    private data class Held(val document:Document,val borrower:ManagementPlatformBorrower,val job:Boolean)
    private val grants=mutableMapOf<Uri,Grant>()
    private val localDocuments=mutableSetOf<Document>()
    private val leases=IdentityHashMap<ManagedDocumentLease,Held>()
    private val issuedLeases=java.util.WeakHashMap<ManagedDocumentLease,Boolean>()
    val files:DnsFileStore=object:DnsFileStore {
        override suspend fun open(ref:DnsFileRef):ByteSource {
            synchronized(lock) {
                if(localDocuments.none { it.file==ref && it.refs>0 && !it.pending }) throw fileConflict()
            }
            return documents.open(ref)
        }
        override suspend fun beginExport(target:DnsExportTarget):DnsExportTransaction = fileLock.withLock {
            val document=synchronized(lock) {
                localDocuments.singleOrNull { it.target===target && !it.pending && it.transaction==null &&
                    leases.values.any { held -> held.document===it && held.job } } ?: throw fileConflict()
            }
            document.uncertainBegin=true
            val actual=documents.beginExport(target)
            OwnedExport(document,actual).also { synchronized(lock) { document.transaction=it;document.uncertainBegin=false } }
        }
        // Core release cannot revoke a capability still held by a UI or app job.
        override suspend fun release(ref:DnsFileRef) {
            if(synchronized(lock) { localDocuments.none { it.file==ref || it.committed==ref } }) documents.release(ref)
        }
    }
    private data class Credential(val connection:Connection,val stamp:ManagementCredentialStamp,
        var active:Boolean,var issuer:TokenAuthorityIssuer?=null,var transition:ManagementCredentialTransition?=null,var ioStarted:Boolean=false)
    private val credentials=mutableMapOf<String,Credential>()
    private val borrowers=java.util.Collections.newSetFromMap(IdentityHashMap<ManagementPlatformBorrower,Boolean>())
    private val bundles=java.util.Collections.newSetFromMap(java.util.WeakHashMap<ManagementCredentialBundle,Boolean>())
    fun borrow():ManagementPlatformBorrower=synchronized(lock) {
        ManagementPlatformBorrower(UUID.randomUUID().toString()).also { borrowers.add(it) }
    }
    suspend fun releaseBorrower(borrower:ManagementPlatformBorrower) {
        val drafts=synchronized(lock) {
            if(!borrowers.remove(borrower)) return
            leases.filterValues { it.borrower===borrower && !it.job }.keys.toList()
        }
        withContext(NonCancellable) { drafts.forEach { release(it) } }
    }
    private fun fresh(connection:Connection,revision:Long,generation:Long,active:Boolean):Credential {
        val entry=Credential(connection,ManagementCredentialStamp(connection.id,connection.accountId,revision,generation),active)
        if(active && !connection.demo) entry.issuer=TokenAuthorityIssuer(connection,revision,{ credentialRevision(connection.id) },clock)
        return entry
    }
    fun registerExistingCredential(connection:Connection):ManagementCredentialStamp=synchronized(lock) {
        credentials[connection.id]?.let {
            if(!it.active || it.connection.accountId!=connection.accountId || it.connection.demo!=connection.demo) throw credentialFailure()
            return@synchronized it.stamp
        }
        fresh(connection,1,1,true).also { credentials[connection.id]=it }.stamp
    }
    fun credentialRevision(connectionId:String):Long=synchronized(lock) { credentials[connectionId]?.takeIf { it.active }?.stamp?.revision ?: -1 }
    fun currentCredentialStamp(connection:Connection):ManagementCredentialStamp?=synchronized(lock) {
        credentials[connection.id]?.takeIf { it.active && it.connection.accountId==connection.accountId && it.connection.demo==connection.demo }?.stamp
    }
    fun beginCredentialChange(connection:Connection):ManagementCredentialTransition=synchronized(lock) {
        val old=credentials[connection.id]
        if(old!=null && (old.connection.accountId!=connection.accountId || old.connection.demo!=connection.demo)) throw credentialFailure()
        if(old?.transition!=null) throw credentialFailure()
        old?.issuer?.invalidate(connection.id)
        val next=fresh(connection,(old?.stamp?.revision ?: 0)+1,(old?.stamp?.generation ?: 0)+1,false)
        val change=ManagementCredentialTransition(UUID.randomUUID().toString());next.transition=change;credentials[connection.id]=next;change
    }
    suspend fun finishCredentialChange(change:ManagementCredentialTransition,persist:suspend()->Unit):ManagementCredentialStamp {
        val entry=synchronized(lock) {
            val pending=credentials.values.singleOrNull { it.transition===change && !it.active && !it.ioStarted } ?: throw credentialFailure()
            pending.ioStarted=true;pending
        }
        try { withContext(Dispatchers.IO) { persist() } }
        catch(cancel:CancellationException) { synchronized(lock) { if(entry.transition===change) entry.transition=null };throw cancel }
        catch(error:Exception) { synchronized(lock) { if(entry.transition===change) entry.transition=null };throw credentialFailure() }
        return synchronized(lock) {
            if(credentials[entry.connection.id]!==entry || entry.transition!==change) throw credentialFailure()
            fresh(entry.connection,entry.stamp.revision,entry.stamp.generation,true).also { credentials[entry.connection.id]=it }.stamp
        }
    }
    suspend fun retireCredential(connection:Connection,remove:suspend()->Unit) {
        val change=beginCredentialChange(connection)
        try { withContext(Dispatchers.IO) { remove() } }
        catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) { throw credentialFailure() }
        finally { synchronized(lock) { credentials[connection.id]?.takeIf { it.transition===change }?.transition=null } }
    }
    private fun active(connection:Connection):Credential=synchronized(lock) {
        credentials[connection.id]?.takeIf { it.active && it.connection.accountId==connection.accountId && it.connection.demo==connection.demo }
            ?: throw credentialFailure()
    }
    private fun requireActive(entry:Credential) { synchronized(lock) {
        if(credentials[entry.connection.id]!==entry || !entry.active) throw credentialFailure()
    } }
    suspend fun acquireCredential(connection:Connection,loadToken:suspend()->String?,
        jsonForToken:(String)->HttpTransport,transfersForToken:(String)->TransferPort):ManagementCredentialBundle {
        if(connection.demo) throw credentialFailure()
        val entry=active(connection)
        try {
            return withContext(Dispatchers.IO) {
                requireActive(entry)
                val token=loadToken()?.takeIf { it.isNotBlank() } ?: throw credentialFailure()
                requireActive(entry)
                val transport=jsonForToken(token);requireActive(entry)
                val transfer=transfersForToken(token);requireActive(entry)
                val bundle=ManagementCredentialBundle(entry.stamp,CloudflareGateway(transport,nowMs={ clock.nowMs() },parseHttpDate=CloudflareHttpDates::parse),transfer,entry.issuer ?: throw credentialFailure())
                synchronized(lock) { requireActive(entry);bundles.add(bundle) };bundle
            }
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) { throw credentialFailure() }
    }
    fun requireCurrent(bundle:ManagementCredentialBundle) { synchronized(lock) {
        if(bundle !in bundles || credentials[bundle.stamp.connectionId]?.let { it.active && it.stamp==bundle.stamp && it.issuer===bundle.issuer }!=true) throw credentialFailure()
    } }
    /** No external callback while holding the Owner monitor: Picker.current may query this owner. */
    private fun checkSelection(borrower:ManagementPlatformBorrower,selection:DnsDocumentSelection) {
        if(selection.stillCurrent?.invoke()!=true) throw fileConflict()
        synchronized(lock) { checkLocalSelection(borrower,selection) }
    }
    /** Caller holds lock. Only local metadata and issued borrower identity are inspected. */
    private fun checkLocalSelection(borrower:ManagementPlatformBorrower,selection:DnsDocumentSelection) {
        val active=credentials[selection.stamp.credential.connectionId]
        if(borrower !in borrowers || active?.active!=true || active.stamp!=selection.stamp.credential ||
            selection.stamp.scope.connectionId!=active.stamp.connectionId || selection.stamp.scope.account.accountId!=active.stamp.accountId) throw fileConflict()
    }
    private fun modes(kind:DnsDocumentKind)=Intent.FLAG_GRANT_READ_URI_PERMISSION or
        (if(kind==DnsDocumentKind.EXPORT) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    private fun acquireGrant(selection:DnsDocumentSelection):Grant {
        val mode=modes(selection.kind)
        if(selection.flags and (mode or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=(mode or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) throw filePermission()
        val resolver=appContext.contentResolver
        val existing=grants[selection.uri]
        val before=existing?.baseline ?: resolver.persistedUriPermissions.filter { it.uri==selection.uri }.fold(0) { bits,p ->
            bits or (if(p.isReadPermission)Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if(p.isWritePermission)Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        }
        resolver.takePersistableUriPermission(selection.uri,mode)
        val grant=existing ?: Grant(selection.uri,before,0).also { grants[selection.uri]=it }
        grant.added=grant.added or (mode and grant.baseline.inv());grant.documents++
        return grant
    }
    private fun releaseGrant(grant:Grant) {
        if(grant.documents>1) { grant.documents--;return }
        // Provider deletion or the user may already have revoked these bits.
        val present=appContext.contentResolver.persistedUriPermissions.filter { it.uri==grant.uri }.fold(0) { bits,p ->
            bits or (if(p.isReadPermission)Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if(p.isWritePermission)Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        }
        val release=grant.added and present
        if(release!=0) appContext.contentResolver.releasePersistableUriPermission(grant.uri,release)
        grant.documents=0;grants.remove(grant.uri)
    }
    private fun issueLease(borrower:ManagementPlatformBorrower,document:Document,job:Boolean):ManagedDocumentLease {
        if(borrower !in borrowers || document.pending || document !in localDocuments) throw fileConflict()
        val issued=ManagedDocumentLease(UUID.randomUUID().toString(),document.file,document.target,job)
        document.refs++;leases[issued]=Held(document,borrower,job);issuedLeases[issued]=true;return issued
    }
    suspend fun select(borrower:ManagementPlatformBorrower,selection:DnsDocumentSelection):ManagedDocumentLease {
        var published:ManagedDocumentLease?=null
        try { return withContext(Dispatchers.IO) {
            fileLock.withLock {
                checkSelection(borrower,selection)
                if(!selection.consumed.compareAndSet(false,true)) throw fileConflict()
                if(synchronized(lock) { localDocuments.size>=256 }) throw fileConflict()
                val grant=acquireGrant(selection)
                val document=Document(selection.uri,grant)
                synchronized(lock) { localDocuments.add(document) }
                try {
                    if(selection.kind==DnsDocumentKind.IMPORT) document.file=documents.resolveImport(selection.uri)
                    else {
                        document.uncertainResolve=true
                        document.target=documents.resolveExportTarget(selection.uri)
                        document.uncertainResolve=false
                    }
                    checkSelection(borrower,selection);currentCoroutineContext().ensureActive()
                    synchronized(lock) { checkLocalSelection(borrower,selection);issueLease(borrower,document,false).also { published=it } }
                } catch(error:Exception) {
                    withContext(NonCancellable) { cleanup(document) };throw error
                }
            }
        } } catch(error:Exception) {
            withContext(NonCancellable) { published?.let { release(it) } }
            if(error is CancellationException) throw error
            throw safeFileFailure(error)
        }
    }
    fun retainDraft(borrower:ManagementPlatformBorrower,lease:ManagedDocumentLease):ManagedDocumentLease=synchronized(lock) {
        val held=leases[lease] ?: throw fileConflict();issueLease(borrower,held.document,false)
    }
    fun claimJob(borrower:ManagementPlatformBorrower,lease:ManagedDocumentLease):ManagedDocumentLease=synchronized(lock) {
        val held=leases[lease] ?: throw fileConflict()
        if(held.job || (held.document.target!=null && leases.values.any { it.document===held.document && it.job })) throw fileConflict()
        issueLease(borrower,held.document,true)
    }
    suspend fun release(lease:ManagedDocumentLease) {
        withContext(NonCancellable+Dispatchers.IO) {
            fileLock.withLock {
                val document=synchronized(lock) {
                    if(lease !in issuedLeases) throw fileConflict()
                    val held=leases.remove(lease) ?: return@withLock
                    held.document.also { it.refs-- }
                }
                if(document.refs==0) cleanup(document)
                Unit
            }
        }
    }
    /** Runs under fileLock; failed cleanup retains both ownership and its grant for explicit retry. */
    private suspend fun cleanup(document:Document):Boolean {
        if(!synchronized(lock) { if(document.refs!=0) false else { document.pending=true;true } }) return false
        return try {
            document.file?.let { documents.release(it) }
            document.committed?.let { documents.release(it) }
            if((document.uncertainResolve || document.uncertainBegin) && documents.retryPendingCleanup(64).pending!=0) return false
            val tx=document.transaction
            if(tx!=null) tx.abortActual()
            else document.target?.let { documents.discardExportTarget(it) }
            releaseGrant(document.grant)
            synchronized(lock) { localDocuments.remove(document) };true
        } catch(error:Exception) { false }
    }
    suspend fun retryCleanup(maxAttempts:Int=16):com.flarego.platform.DnsDocumentCleanupResult {
        if(maxAttempts !in 1..64) throw fileConflict()
        return withContext(Dispatchers.IO) { fileLock.withLock {
            val pending=synchronized(lock) { localDocuments.filter { it.pending && it.refs==0 }.take(maxAttempts) }
            var attempted=0
            for(document in pending) { currentCoroutineContext().ensureActive();attempted++;cleanup(document) }
            com.flarego.platform.DnsDocumentCleanupResult(attempted,synchronized(lock) { localDocuments.count { it.pending && it.refs==0 } })
        } }
    }
    private inner class OwnedExport(private val document:Document,private val actual:DnsExportTransaction):DnsExportTransaction {
        override val sink get()=actual.sink
        override suspend fun commit():DnsFileRef = fileLock.withLock {
            val committed=actual.commit();document.committed=committed;committed
        }
        override suspend fun abort() { withContext(NonCancellable+Dispatchers.IO) { fileLock.withLock { abortActual() } } }
        suspend fun abortActual() { actual.abort() }
    }

}

private fun credentialFailure()=CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED,"连接凭据已失效或正在变更，请重新连接")

private fun fileConflict()=CloudFailure(CloudFailureCode.CONFLICT,"文件请求或引用已失效，请重新选择文档")
private fun filePermission()=CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,"文件持久权限不足，请重新选择并授权")
private fun safeFileFailure(error:Exception):CloudFailure=when(error) {
    is CloudFailure -> CloudFailure(error.code,"本机文件请求无法完成，请检查当前选择、空间与持久权限")
    is SecurityException -> filePermission()
    else -> CloudFailure(CloudFailureCode.UNAVAILABLE,"本机文件无法访问或清理，请检查空间与权限后重试")
}
