package com.flarego.core.application.d1

import com.flarego.core.model.invalidInputFailure
import com.flarego.core.ports.D1Content

enum class D1Acceptance { COMPLETE, PROCESSING, PARTIAL, UNKNOWN }
data class D1ResultSummary(val resultCount: Long, val rowCount: Long, val failedCount: Long, val acceptance: D1Acceptance)

object D1ResultJson {
    suspend fun validate(content: D1Content, raw: Boolean): D1ResultSummary = scanD1(content) { r ->
        var outer: Boolean?=null;var results=false;var count=0L;var rows=0L;var failed=0L
        r.obj { field -> when(field){
            "success" -> outer=r.boolean()
            "result" -> {
                results=true
                r.array {
                    var success:Boolean?=null;var data=false
                    r.obj { name -> when(name){
                        "success" -> success=r.boolean()
                        "results" -> {
                            data=true
                            if(!raw) r.array{r.value();rows=increment(rows)}
                            else {
                                var columns:Long?=null;var hasRows=false;var width:Long?=null
                                r.obj{ part -> when(part){
                                    "columns" -> {var n=0L;r.array{r.stringPoints{};n=increment(n)};columns=n}
                                    "rows" -> {hasRows=true;r.array{var n=0L;r.array{r.value();n=increment(n)};if(width!=null && width!=n)bad();width=n;rows=increment(rows)}}
                                    else -> r.value()
                                }}
                                if(columns==null || !hasRows || width!=null && width!=columns)bad()
                            }
                        }
                        else -> r.value()
                    }}
                    if(success==null || success==true && !data)bad()
                    if(success==false) failed=increment(failed)
                    count=increment(count)
                }
            }
            else -> r.value()
        }}
        r.end()
        if(outer==null || !results || count==0L)bad()
        D1ResultSummary(count,rows,failed,when{outer!=true->D1Acceptance.UNKNOWN;failed>0->D1Acceptance.PARTIAL;else->D1Acceptance.COMPLETE})
    }
    private fun increment(n:Long):Long {if(n==Long.MAX_VALUE)bad();return n+1}
    private fun bad():Nothing=throw invalidInputFailure()
}
