package com.flarego.core.application.d1

import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class D1SqlInput internal constructor(val content: D1Content, val queryCount: Long) {
    override fun toString() = "D1SqlInput(redacted)"
}

object D1SqlJson {
    suspend fun prepareJson(content: D1Content): D1SqlInput = scanD1(content) { reader ->
        var count = 0L; var single = false; var batch = false; var hasParams = false
        fun statementFields(field: String) {
            when(field) {
                "sql" -> {
                    var nonblank = false
                    reader.stringPoints { cp ->
                        if(cp == 0) throw invalidInputFailure()
                        if(cp > 65535 || !cp.toChar().isWhitespace()) nonblank = true
                    }
                    if(!nonblank) throw invalidInputFailure()
                }
                "params" -> {
                    var params = 0
                    reader.array { if(++params > 100) throw invalidInputFailure(); reader.stringPoints { } }
                }
                else -> throw invalidInputFailure()
            }
        }
        reader.obj { field ->
            when(field) {
                "batch" -> {
                    batch = true
                    reader.array {
                        var hasSql = false
                        reader.obj { name -> if(name == "sql") hasSql = true; statementFields(name) }
                        if(!hasSql || count == Long.MAX_VALUE) throw invalidInputFailure()
                        count++
                    }
                }
                "sql", "params" -> { if(field == "sql") single = true else hasParams = true; statementFields(field) }
                else -> throw invalidInputFailure()
            }
        }
        reader.end()
        if(single == batch || batch && (count == 0L || hasParams)) throw invalidInputFailure()
        D1SqlInput(content, if(single) 1 else count)
    }

    suspend fun fromSqlText(content: D1Content, store: D1ContentStore, params: List<String> = emptyList()): D1SqlInput {
        D1Validation.params(params)
        val tail = buildJsonObject { put("params", JsonArray(params.map(::JsonPrimitive))) }.toString()
        val source = content.openVerified()
        val encoded = SqlTextSource(source, ("\"," + tail.drop(1)).encodeToByteArray())
        val staged = store.stage(encoded, Long.MAX_VALUE)
        try { return prepareJson(staged) } catch(failure: Throwable) { staged.close(); throw failure }
    }

    suspend fun fromStatements(statements: List<D1Statement>, store: D1ContentStore, batch: Boolean): D1SqlInput {
        if(statements.isEmpty() || !batch && statements.size != 1) throw invalidInputFailure()
        fun row(statement: D1Statement) = buildJsonObject {
            put("sql", statement.sql); put("params", JsonArray(statement.params.map(::JsonPrimitive)))
        }
        val json = if(batch) buildJsonObject{ put("batch", JsonArray(statements.map(::row))) } else row(statements.single())
        val bytes = json.toString().encodeToByteArray()
        val source = object : ByteSource {
            var at=0; var closed=false
            override fun read(buffer: ByteArray): Int { check(!closed); if(at==bytes.size)return -1;val n=minOf(buffer.size,bytes.size-at);bytes.copyInto(buffer,0,at,at+n);at+=n;return n }
            override fun close(){ closed=true;bytes.fill(0) }
        }
        val content = store.stage(source, bytes.size.toLong())
        try { return prepareJson(content) } catch(failure: Throwable) { content.close(); throw failure }
    }
}

internal suspend fun <T> scanD1(content: D1Content, action: (D1JsonCursor) -> T): T = withContext(Dispatchers.Default) {
    if(content.descriptor.length < 1) throw invalidInputFailure()
    val source=content.openVerified(); val context=currentCoroutineContext()
    try { action(D1JsonCursor(source,content.descriptor.length){context.ensureActive()}) }
    catch(cancel: CancellationException){throw cancel}
    catch(_: Exception){throw invalidInputFailure()}
    finally{ source.close() }
}

/** Byte-wise JSON escaping preserves original UTF-8 and every SQL whitespace byte. */
private class SqlTextSource(private val source: ByteSource, private val tail: ByteArray) : ByteSource {
    private val input=ByteArray(8192);private var at=0;private var size=0;private var ended=false;private var closed=false
    private var pending="{\"sql\":\"".encodeToByteArray();private var pendingAt=0;private var tailUsed=false
    override fun read(buffer: ByteArray): Int {
        check(!closed); if(buffer.isEmpty())throw invalidInputFailure()
        var out=0
        while(out<buffer.size){
            if(pendingAt<pending.size){buffer[out++]=pending[pendingAt++];continue}
            if(ended){if(tailUsed)break;pending=tail;pendingAt=0;tailUsed=true;continue}
            if(at==size){val n=source.read(input);if(n == -1){ended=true;continue};if(n !in 1..input.size)throw invalidInputFailure();at=0;size=n}
            val b=input[at++].toInt() and 255
            if(b !in 0..31 && b != 34 && b != 92) { buffer[out++]=b.toByte(); continue }
            pending=when(b){34->byteArrayOf(92,34);92->byteArrayOf(92,92);else->("\\u"+b.toString(16).padStart(4,'0')).encodeToByteArray()};pendingAt=0
        }
        return if(out==0)-1 else out
    }
    override fun close(){closed=true;input.fill(0);pending.fill(0);tail.fill(0);source.close()}
}
