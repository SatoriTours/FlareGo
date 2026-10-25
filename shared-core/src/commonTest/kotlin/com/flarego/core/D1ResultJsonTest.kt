package com.flarego.core

import com.flarego.core.application.d1.*
import com.flarego.core.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class D1ResultJsonTest {
    @Test fun oneSqlStringCanReturnMultipleResultsAndPreservesAllTypesAndNumbers() = runTest {
        val wire="""{"success":true,"result":[{"success":true,"results":[{"n":9007199254740993,"null":null,"bool":false,"a":[1,"雪"]}]},{"success":true,"results":[]}]}"""
        val input=d1Content(wire)
        val summary=D1ResultJson.validate(input,raw=false)
        assertEquals(2L,summary.resultCount);assertEquals(1L,summary.rowCount)
        assertEquals(D1Acceptance.COMPLETE,summary.acceptance)
        assertEquals(wire,d1Text(input))
    }
    @Test fun innerFailureIsPartialAndOuterFalseCannotBecomeSuccess() = runTest {
        val partial="""{"success":true,"result":[{"success":true,"results":[]},{"success":false,"error":"private SQL marker"}]}"""
        assertEquals(D1Acceptance.PARTIAL,D1ResultJson.validate(d1Content(partial),false).acceptance)
        val falseOuter="""{"success":false,"result":[{"success":true,"results":[]}]}"""
        assertEquals(D1Acceptance.UNKNOWN,D1ResultJson.validate(d1Content(falseOuter),false).acceptance)
    }
    @Test fun rawKeepsColumnsAndChecksEveryRowShapeIncludingEmptyResults() = runTest {
        val wire="""{"success":true,"result":[{"success":true,"results":{"columns":["a","b"],"rows":[[null,1],[false,"雪"]]}},{"success":true,"results":{"columns":[],"rows":[]}}]}"""
        val summary=D1ResultJson.validate(d1Content(wire),true)
        assertEquals(2L,summary.rowCount);assertEquals(D1Acceptance.COMPLETE,summary.acceptance)
        for(bad in listOf(
            """{"success":true,"result":[{"success":true,"results":{"columns":["a"],"rows":[[1,2]]}}]}""",
            """{"success":true,"result":[{"results":[]}]}""",
            """{"success":true,"result":[]}""",
            """{"success":true,"result":[{"success":true,"results":[]}]}garbage""",
        )) assertFailsWith<CloudFailure>{D1ResultJson.validate(d1Content(bad),true)}
    }
}
