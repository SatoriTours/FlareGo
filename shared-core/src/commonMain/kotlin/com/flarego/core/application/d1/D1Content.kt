package com.flarego.core.application.d1

import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*

/** Returned only directly to a private protected view, never a StateFlow/task/SavedState. */
class D1PrivatePage(val text:String,val nextOffset:Long?) {
    val hasMore get()=nextOffset!=null
    override fun toString()="D1PrivatePage(redacted)"
}
object D1ContentPages {
    suspend fun page(content:D1Content,offset:Long,maxBytes:Int=D1_EDITOR_BYTES):D1PrivatePage=withContext(Dispatchers.Default){
        if(offset<0 || offset>content.descriptor.length || maxBytes !in 4..D1_EDITOR_BYTES)throw invalidInputFailure()
        val source=content.openVerified();val b=ByteArray(8192);val bytes=ByteArray(maxBytes+4)
        var position=0L;var used=0
        try{
            while(used<bytes.size){
                ensureActive();val n=source.read(b);if(n == -1)break;if(n !in 1..b.size)throw invalidInputFailure()
                val start=(offset-position).coerceIn(0,n.toLong()).toInt()
                if(start<n){val size=minOf(n-start,bytes.size-used);b.copyInto(bytes,used,start,start+size);used+=size}
                position+=n
            }
            var end=minOf(maxBytes,used)
            while(end>0 && end<used && bytes[end].toInt() and 0xc0 == 0x80)end--
            val text=try{bytes.decodeToString(0,end,throwOnInvalidSequence=true)}catch(_:CharacterCodingException){throw invalidInputFailure()}
            val next=offset+end
            if(end==0 && next<content.descriptor.length)throw invalidInputFailure()
            D1PrivatePage(text,next.takeIf{it<content.descriptor.length})
        }finally{source.close();b.fill(0);bytes.fill(0)}
    }
}
