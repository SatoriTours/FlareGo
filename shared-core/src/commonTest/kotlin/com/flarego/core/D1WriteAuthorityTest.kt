package com.flarego.core

import com.flarego.core.application.d1.*
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class D1WriteAuthorityTest {
    private val connection=Connection("fixture","fixture","a".repeat(32))
    private val account=AccountScope(connection.id,connection.accountId)
    private val ref=D1DatabaseRef(account,"12345678-1234-4321-abcd-123456789abc")
    private val target=D1WriteTarget(account,ref.id)
    private val operation="d1-query-database"
    private val scope="com.cloudflare.api.account"
    private fun policy(group:String="writer",effect:String="allow",resource:String="$scope.${account.accountId}")=buildJsonObject {
        put("id",effect);put("effect",effect);put("permission_groups",JsonArray(listOf(buildJsonObject{put("id",group)})))
        put("resources",buildJsonObject{put(resource,"*")})
    }
    private inner class Harness(kind:TokenKind=TokenKind.USER_OWNED) {
        var revision=7L;var now=1000L;var owner=account.accountId;var policies=listOf(policy())
        var status="active";var condition:JsonObject?=null;var failPath:String?=null
        var userAvailable=kind!=TokenKind.ACCOUNT_OWNED;var accountAvailable=kind==TokenKind.ACCOUNT_OWNED
        var duplicate=false;var completeEnvelope=true
        val calls=mutableListOf<HttpRequest>()
        val issuer=D1TokenAuthorityIssuer(connection,7,{revision},OperationClock{now},kind)
        val gateway=CloudflareGateway(HttpTransport { request ->
            calls+=request
            fun response(result:JsonElement)=HttpResponse(200,buildJsonObject{put("success",true);if(completeEnvelope){put("errors",JsonArray(emptyList()));put("messages",JsonArray(emptyList()))};put("result",result)}.toString())
            fun row(id:String,name:String)=buildJsonObject{put("id",id);put("name",name);put("scopes",JsonArray(listOf(JsonPrimitive(scope))))}
            val identity=buildJsonObject{put("id","self-token");put("status",status)}
            when {
                request.path==failPath ->HttpResponse(503,"private-marker")
                request.path=="/user/tokens/verify" ->if(userAvailable)response(identity)else HttpResponse(401,"")
                request.path=="/accounts/${account.accountId}/tokens/verify" ->if(accountAvailable)response(identity)else HttpResponse(401,"")
                request.path.endsWith("/self-token") ->response(buildJsonObject{identity.forEach{(k,v)->put(k,v)};put("policies",JsonArray(policies));condition?.let{put("condition",it)}})
                request.path.endsWith("/permission_groups") ->response(JsonArray(listOf(row("writer","D1 Write"),row("reader","D1 Read"),row("kv","Workers KV Storage Write"),row("r2","Workers R2 Storage Write"))+if(duplicate)listOf(row("writer","D1 Write"))else emptyList()))
                request.path=="/accounts/${account.accountId}" ->response(buildJsonObject{put("id",owner)})
                request.method!="GET" ->response(buildJsonObject{put("uuid",ref.id);put("name","fixture")})
                else ->error("unexpected local request")
            }
        },sleeper={})
        fun observer()=CloudflareD1WriteAuthorityClient(issuer,gateway)
        suspend fun observe(t:D1WriteTarget=target,op:String=operation)=observer().observe(t,op,true)
        suspend fun proof():D1WriteProof {val observation=observe();assertEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,observation.decision,"${observation.reason}");return issuer.proofFor(observation)}
        fun session(guard:()->Unit={})=AuthorizedD1Session(connection,gateway,object:TransferPort {
            override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead {
                calls+=request;(request.payload as HttpBody.Stream).source.close()
                val raw=request.path.endsWith("/raw")
                val bytes=(if(raw)"""{"success":true,"result":[{"success":true,"results":{"columns":[],"rows":[]}}]}"""else """{"success":true,"result":[{"success":true,"results":[]}]}""").encodeToByteArray()
                sink.write(bytes,0,bytes.size);sink.close();return HttpResponseHead(200,emptyMap(),bytes.size.toLong())
            }
        },D1MemoryStore(),issuer,guard)
    }
    @Test fun issuerOnlyMintsIdentityBoundDatabaseOperationAndGenerationProof()=runTest {
        val h=Harness();val proof=h.proof();h.issuer.requireCurrent(proof,target,operation)
        assertFailsWith<CloudFailure>{h.issuer.requireCurrent(proof,target.copy(databaseId="99999999-1234-4321-abcd-123456789abc"),operation)}
        assertFailsWith<CloudFailure>{h.issuer.requireCurrent(proof,target,"d1-delete-database")}
        assertFailsWith<CloudFailure>{h.issuer.requireCurrent(D1WriteProof(),target,operation)}
        assertFailsWith<CloudFailure>{Harness().issuer.requireCurrent(proof,target,operation)}
        assertFailsWith<CloudFailure>{h.issuer.proofFor(h.observe().copy())}
    }
    @Test fun sixProvenMappingsAreExplicitAndFourMissingMappingsHaveZeroIntrospectionOrWrite()=runTest {
        val h=Harness()
        for(op in listOf("d1-create-database","d1-update-database","d1-update-partial-database","d1-delete-database","d1-query-database","d1-raw-database-query")) {
            val t=if(op=="d1-create-database")target.copy(databaseId=null)else target
            assertEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,h.observe(t,op).decision)
        }
        h.calls.clear()
        for(op in listOf("d1-import-database","d1-export-database","d1-time-travel-restore","d1-undelete-database","d1-get-database","r2-put-object"))
            assertEquals(WriteAuthorityReason.UNSUPPORTED_OPERATION,h.observe(op=op).reason)
        assertTrue(h.calls.isEmpty())
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,h.observe(target.copy(databaseId=null)).decision)
    }
    @Test fun d1ReadKvR2UnknownAndMatchingDenyNeverGrantCustomSql()=runTest {
        val h=Harness()
        for(p in listOf(listOf(policy("reader")),listOf(policy("kv")),listOf(policy("r2")),listOf(policy(),policy(effect="deny")),listOf(policy(resource="$scope.${"b".repeat(32)}")),listOf(policy(resource="unknown.resource")))) {
            h.policies=p;assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,h.observe().decision)
        }
    }
    @Test fun accountOwnerConditionsAndCatalogAmbiguityFailClosed()=runTest {
        val h=Harness();h.policies=listOf(policy(resource="$scope.*"));h.proof();h.owner="b".repeat(32)
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,h.observe().decision)
        h.owner=account.accountId;h.condition=buildJsonObject{put("request_ip",buildJsonObject{put("in",JsonArray(listOf(JsonPrimitive("127.0.0.1"))))})}
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,h.observe().decision)
        h.condition=null;h.duplicate=true;assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,h.observe().decision)
        val incomplete=Harness();incomplete.completeEnvelope=false
        assertEquals(WriteAuthorityReason.INVALID_RESPONSE,incomplete.observe().reason)
    }
    @Test fun expiredRevisionRollbackGenerationAndRefreshFailureRevokeProof()=runTest {
        for(invalidate in listOf<(Harness)->Unit>({it.revision++},{it.now--},{it.now+=30000},{it.issuer.invalidate(connection.id)})) {
            val h=Harness();val proof=h.proof();invalidate(h);assertFailsWith<CloudFailure>{h.issuer.requireCurrent(proof,target,operation)}
        }
        val h=Harness();val proof=h.proof();h.failPath="/user/tokens/verify";assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,h.observe().decision)
        assertFailsWith<CloudFailure>{h.issuer.requireCurrent(proof,target,operation)}
    }
    @Test fun unknownPrincipalKindNeverGuessesAfterAmbiguityOrFailedEndpoint()=runTest {
        val h=Harness(TokenKind.UNKNOWN);h.proof();h.accountAvailable=true;assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,h.observe().decision)
        val broken=Harness(TokenKind.UNKNOWN);broken.failPath="/accounts/${account.accountId}/tokens/verify"
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,broken.observe().decision)
    }
    @Test fun eachProductionWriteRefreshesThenChecksFinalGuardAndMissingOperationsCannotSend()=runTest {
        val h=Harness();var guards=0;val api=h.session{guards++}
        api.createDatabase(account,D1CreateInput("fixture"));api.updateDatabase(ref,D1Replication.auto);api.patchDatabase(ref,null);api.deleteDatabase(ref)
        val input=D1SqlJson.fromStatements(listOf(D1Statement("SELECT 1")),D1MemoryStore(),false)
        api.query(ref,input).close();api.raw(ref,input).close()
        assertEquals(6,guards);assertEquals(6,h.calls.count{it.path=="/user/tokens/verify"});assertEquals(6,h.calls.count{it.method!="GET"})
        h.calls.clear()
        assertFailsWith<CloudFailure>{api.importDatabase(ref,D1ImportRequest.Init("0".repeat(32)))}
        assertFailsWith<CloudFailure>{api.exportDatabase(ref,D1ExportOptions())}
        assertFailsWith<CloudFailure>{api.restore(ref,D1RestorePoint.Bookmark("b"))}
        assertFailsWith<CloudFailure>{api.undeleteDatabase(ref)}
        assertTrue(h.calls.isEmpty())
    }
    @Test fun guardRevocationDeniesSendWhileClosedFactoryBrowseNeedsNoWriteProof()=runTest {
        val h=Harness()
        assertFailsWith<CloudFailure>{h.session{h.issuer.invalidate(connection.id)}.deleteDatabase(ref)}
        assertEquals(0,h.calls.count{it.method!="GET"})
        val read=Harness();read.policies=listOf(policy("reader"))
        read.session().browse(ref,D1BrowseRequest.schema()).close()
        assertEquals(1,read.calls.size);assertEquals("POST",read.calls.single().method)
        val input=D1SqlJson.fromStatements(listOf(D1Statement("SELECT 1")),D1MemoryStore(),false)
        assertFailsWith<CloudFailure>{read.session().query(ref,input)}
        assertEquals(1,read.calls.count{it.method!="GET"})
    }
}
