package com.flarego.core

import com.flarego.core.application.d1.D1Validation
import com.flarego.core.model.*
import kotlin.test.*

class D1ValidationTest {
    @Test fun databaseUuidAndNameCannotBecomePathOrSqlFragments() {
        assertEquals("12345678-1234-4321-ABCD-123456789abc", D1Validation.databaseId("12345678-1234-4321-ABCD-123456789abc"))
        for (id in listOf("name", "../id", "12345678-1234-4321-abcd-123456789abc/restore", " 12345678-1234-4321-abcd-123456789abc")) {
            assertFailsWith<CloudFailure> { D1Validation.databaseId(id) }
        }
        assertEquals("My_database-1", D1Validation.name("My_database-1"))
        for (name in listOf("", "-db", "db name", "db/next", "db\n")) assertFailsWith<CloudFailure> { D1Validation.name(name) }
    }

    @Test fun parameterBoundaryPreservesEmptyUnicodeAndNumericStrings() {
        val params = listOf("", "参数", "null", "00012") + List(96) { "?" }
        assertEquals(params, D1Validation.params(params))
        assertFailsWith<CloudFailure> { D1Validation.params(params + "overflow") }
        assertFailsWith<CloudFailure> { D1Validation.params(listOf("\uD800")) }
    }

    @Test fun exportAllTablesIsExplicitAndConflictingFlagsFail() {
        val all = D1ExportOptions(emptyList())
        assertTrue(D1Validation.exportOptions(all).allTables)
        assertFailsWith<CloudFailure> { D1Validation.exportOptions(D1ExportOptions(listOf("a", "a"))) }
        assertFailsWith<CloudFailure> { D1Validation.exportOptions(D1ExportOptions(emptyList(), noData = true, noSchema = true)) }
        assertFalse(D1Validation.exportOptions(D1ExportOptions(listOf("a\"; DROP TABLE x;--"))).allTables)
    }

    @Test fun controlledBrowseQuotesIdentifiersAndBindsFilterValues() {
        val request = D1BrowseRequest.rows("a\"; DROP TABLE x;--", 2, filterColumn = "n\"q", filterValue = "' OR 1=1--")
        assertEquals("SELECT * FROM \"a\"\"; DROP TABLE x;--\" WHERE \"n\"\"q\" = ? LIMIT 50 OFFSET 100", request.statement.sql)
        assertEquals(listOf("' OR 1=1--"), request.statement.params)
        assertFailsWith<CloudFailure> { D1BrowseRequest.rows("bad\u0000name", 0) }
        assertFailsWith<CloudFailure> { D1BrowseRequest.rows("x", -1) }
        assertFailsWith<CloudFailure> { D1BrowseRequest.rows("x", 0, filterColumn = "x") }
        assertFalse(request.toString().contains("DROP"))
    }

    @Test fun createDoesNotSilentlyRemoveTheLocationHint() {
        val input = D1CreateInput("db", D1Jurisdiction.eu, D1Location.wnam, D1Replication.auto)
        assertEquals(input, D1Validation.create(input))
        assertEquals(D1Location.wnam, input.location)
    }
}
