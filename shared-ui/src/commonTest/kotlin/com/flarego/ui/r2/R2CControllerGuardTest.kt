package com.flarego.ui.r2

import com.flarego.core.application.r2.R2Command
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2AccountMetrics
import com.flarego.core.model.R2ClassMetrics
import com.flarego.core.model.R2JobWrite
import com.flarego.core.ports.R2MetricsPort
import com.flarego.core.model.R2MetricNumber
import com.flarego.core.model.R2NotificationDeletion
import com.flarego.core.model.R2NotificationRuleSpec
import com.flarego.core.model.R2NotificationWrite
import com.flarego.core.model.R2SizeMetrics
import com.flarego.core.model.R2StorageClass
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 3, the batch C root guard (JVM).
 *
 * The controller never touches the runtime directly in these tests: it calls the root's
 * `prepareCommand`/`confirmCommand` hooks, which is exactly the boundary that must refuse a
 * destructive write without the three stages. Every case asserts the number of *prepares* and
 * *confirms* (the only two ways a write can start), and the ticket/handle binding.
 */
class R2CControllerGuardTest {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private var routeEpoch = 3L
    private var credentialRevision = 7L

    private inner class Harness(scope: TestScope) {
        val prepared = mutableListOf<R2Command>()
        val confirmed = mutableListOf<R2CConfirmationTicket?>()
        var bucket = "my-bucket"
        var metricsValue: R2AccountMetrics? = null
        var metricsError: CloudFailure? = null
        /** False models a root that cannot prepare (for example no runtime/session at all). */
        var prepareAllowed = true

        /** A single already-known queue (the read is what the surface needs; writes go through the root). */
        private val notificationPort =
            object : com.flarego.core.ports.R2NotificationSession {
                override suspend fun listConfigurations(target: com.flarego.core.model.R2WriteTarget) =
                    com.flarego.core.model.R2NotificationCollection(
                        bucketName = target.bucket,
                        queues =
                            listOf(
                                com.flarego.core.model.R2NotificationQueue(
                                    "queue-1",
                                    "events",
                                    listOf(
                                        com.flarego.core.model.R2NotificationRule(
                                            "rule-a",
                                            listOf("PutObject"),
                                            "existing",
                                            null,
                                            null,
                                            "2026-10-22T00:00:00Z",
                                        ),
                                    ),
                                    interpretable = true,
                                ),
                            ),
                        interpretable = true,
                    )

                override suspend fun getConfiguration(target: com.flarego.core.model.R2WriteTarget, queueId: String) =
                    listConfigurations(target).queues.single()

                override suspend fun appendRules(
                    target: com.flarego.core.model.R2WriteTarget,
                    queueId: String,
                    rules: List<R2NotificationRuleSpec>,
                ) = com.flarego.core.application.operations.ExecutionReceipt.Completed()

                override suspend fun deleteRules(
                    target: com.flarego.core.model.R2WriteTarget,
                    queueId: String,
                    deletion: R2NotificationDeletion,
                ) = com.flarego.core.application.operations.ExecutionReceipt.Completed()

                override suspend fun writeNotification(
                    write: R2NotificationWrite,
                    beforeSend: (com.flarego.core.model.R2NotificationQueue) -> Unit,
                ) = com.flarego.core.application.operations.ExecutionReceipt.Completed()
            }

        private val metricsPort =
            object : R2MetricsPort {
                override suspend fun getMetrics(scope: AccountScope): R2AccountMetrics {
                    metricsError?.let { throw it }
                    return metricsValue ?: R2AccountMetrics(null, null, interpretable = true)
                }
            }

        private fun prepare(command: R2Command): Boolean {
            if (!prepareAllowed) return false
            prepared += command
            // This is a controller-only fixture: explicitly simulate the root's completed read.
            // Real root/runtime/authority/wire behaviour is covered by R2CBrowserRuntimeIntegrationTest.
            controller.onRootPreparing(command)
            controller.onRootPreview(command, "synthetic frozen preview")
            return true
        }

        val controller =
            R2CController(
                scope = scope.backgroundScope,
                context = { R2BrowserContext(connection, credentialRevision, routeEpoch) },
                prepareCommand = ::prepare,
                confirmCommand = { ticket ->
                    confirmed += ticket
                    Job().apply { complete() }
                },
                bucket = { bucket },
                runtime = null,
                jobs = null,
                notifications = { notificationPort },
                metrics = { metricsPort },
            )

        fun state() = controller.state.value
    }

    private fun deletionDraft() = R2CJobDraft(migration = false, prefix = "logs/", wholeBucketClear = false, manualMpuDeclared = true)

    @Test
    fun aDestructiveCommandCannotBeConfirmedWithoutTheThreeStages() = runTest {
        val h = Harness(this)
        h.controller.open(R2CScreen.JOBS)
        h.controller.onJobDraftChanged(deletionDraft())
        assertTrue(h.controller.prepare())
        assertEquals(1, h.prepared.size)
        // The reviewed-confirm path refuses a destructive command outright.
        assertNull(h.controller.confirmReviewed())
        // And the final submit has no ticket yet.
        assertNull(h.controller.confirmFinal())
        assertEquals(0, h.confirmed.size)
        // Only one stage at a time: acknowledge → typed name → final.
        h.controller.beginDeletion(R2CDeleteKind.PREFIX_DELETE)
        assertNull(h.controller.confirmFinal())
        assertEquals(0, h.confirmed.size)
        h.controller.acknowledgeRisk()
        assertNull(h.controller.confirmFinal())
        assertEquals(0, h.confirmed.size)
    }

    @Test
    fun theStagesRequireTheExactNameAndTheTicketIsConsumedExactlyOnce() = runTest {
        val h = Harness(this)
        h.controller.open(R2CScreen.JOBS)
        h.controller.onJobDraftChanged(deletionDraft())
        h.controller.prepare()
        h.controller.beginDeletion(R2CDeleteKind.PREFIX_DELETE)
        h.controller.acknowledgeRisk()
        h.controller.typeBucketName("My-Bucket")
        assertFalse(h.controller.openFinal(), "a non-exact name cannot open the final stage")
        assertEquals(0, h.confirmed.size)
        h.controller.typeBucketName("my-bucket")
        assertTrue(h.controller.openFinal())
        assertNotNull(h.controller.confirmFinal(), "the first final submit confirms once")
        assertEquals(1, h.confirmed.size)
        // A repeated click cannot reach a second write: the ticket was consumed and cleared.
        assertNull(h.controller.confirmFinal())
        assertEquals(1, h.confirmed.size)
        // The confirmed ticket names the exact draft and the live route/credential binding.
        val ticket = assertNotNull(h.confirmed.single())
        assertEquals("$routeEpoch/$credentialRevision", ticket.liveBinding)
    }

    @Test
    fun aRouteOrCredentialChangeAfterTheFinalStageWritesNothing() = runTest {
        for (change in listOf("route", "credential")) {
            val h = Harness(this)
            routeEpoch = 3L
            credentialRevision = 7L
            h.controller.open(R2CScreen.JOBS)
            h.controller.onJobDraftChanged(deletionDraft())
            h.controller.prepare()
            h.controller.beginDeletion(R2CDeleteKind.PREFIX_DELETE)
            h.controller.acknowledgeRisk()
            h.controller.typeBucketName("my-bucket")
            assertTrue(h.controller.openFinal())
            when (change) {
                "route" -> routeEpoch = 4L
                else -> credentialRevision = 8L
            }
            assertNull(h.controller.confirmFinal(), "$change change must refuse the submit")
            assertEquals(0, h.confirmed.size, change)
            routeEpoch = 3L
            credentialRevision = 7L
        }
    }

    @Test
    fun anEditedDraftOrTargetRevokesTheWholeConfirmation() = runTest {
        val h = Harness(this)
        h.controller.open(R2CScreen.JOBS)
        h.controller.onJobDraftChanged(deletionDraft())
        h.controller.prepare()
        h.controller.beginDeletion(R2CDeleteKind.PREFIX_DELETE)
        h.controller.acknowledgeRisk()
        h.controller.typeBucketName("my-bucket")
        assertTrue(h.controller.openFinal())
        h.controller.onJobDraftChanged(deletionDraft().copy(prefix = "other/"))
        assertNull(h.controller.confirmFinal())
        assertEquals(0, h.confirmed.size)
        assertEquals(R2CStage.NONE, h.state().confirmation.stage)

        // Another bucket is another target: the stages and the ticket die with it.
        val second = Harness(this)
        second.controller.open(R2CScreen.JOBS)
        second.controller.onJobDraftChanged(deletionDraft())
        second.controller.prepare()
        second.controller.beginDeletion(R2CDeleteKind.PREFIX_DELETE)
        second.controller.acknowledgeRisk()
        second.controller.typeBucketName("my-bucket")
        assertTrue(second.controller.openFinal())
        second.controller.onTargetChanged("other-bucket")
        assertNull(second.controller.confirmFinal())
        assertEquals(0, second.confirmed.size)
    }

    @Test
    fun aMigrationUsesTheReviewedPreviewConfirmAndNeverTheThreeStages() = runTest {
        val h = Harness(this)
        h.controller.open(R2CScreen.JOBS)
        h.controller.onJobDraftChanged(
            R2CJobDraft(migration = true, source = R2StorageClass.STANDARD, destination = R2StorageClass.INFREQUENT_ACCESS),
        )
        assertTrue(h.controller.prepare())
        assertNotNull(h.controller.confirmReviewed())
        assertEquals(1, h.confirmed.size)
        assertNull(h.confirmed.single(), "a migration is not a three-stage deed")
        // Same storage class on both sides is not a valid migration command.
        h.controller.onJobDraftChanged(
            R2CJobDraft(migration = true, source = R2StorageClass.STANDARD, destination = R2StorageClass.STANDARD),
        )
        assertNull(h.controller.liveCommand())
    }

    @Test
    fun anIncompleteOrUnsafeDraftCanNeverBePrepared() = runTest {
        val h = Harness(this)
        h.controller.open(R2CScreen.JOBS)
        // A non-empty prefix must end with '/'.
        h.controller.onJobDraftChanged(deletionDraft().copy(prefix = "logs"))
        assertNull(h.controller.liveCommand())
        assertFalse(h.controller.prepare())
        // The whole-bucket mode is explicit and still needs the manual multipart statement.
        h.controller.onJobDraftChanged(R2CJobDraft(wholeBucketClear = true, manualMpuDeclared = false))
        assertNull(h.controller.liveCommand())
        assertFalse(h.controller.prepare())
        h.controller.onJobDraftChanged(R2CJobDraft(wholeBucketClear = true, manualMpuDeclared = true))
        val command = assertNotNull(h.controller.liveCommand())
        assertTrue((command as R2Command.Job).write is R2JobWrite.PrefixDelete)
        assertEquals("", ((command.write) as R2JobWrite.PrefixDelete).prefix)
        assertEquals(0, h.prepared.size)
    }

    @Test
    fun theNotificationDeletionsUseTheSameGuardAndTheAppendOnlyTheReviewedConfirm() = runTest {
        val h = Harness(this)
        h.controller.open(R2CScreen.NOTIFICATIONS)
        runCurrent()
        // The queue is loaded, but an append without any pinned action is not buildable.
        assertNull(h.controller.liveCommand())
        // An append needs at least one pinned action.
        h.controller.onNotificationDraftChanged(R2CNotificationDraft(actions = emptySet()))
        assertNull(h.controller.liveCommand())
        h.controller.onNotificationDraftChanged(R2CNotificationDraft(actions = setOf("PutObject"), prefix = "images/"))
        val append = assertNotNull(h.controller.liveCommand()) as R2Command.Notification
        assertTrue(append.write is R2NotificationWrite.Append)
        assertTrue(h.controller.prepare())
        assertNotNull(h.controller.confirmReviewed())
        assertEquals(1, h.confirmed.size)
        assertNull(h.confirmed.single())
    }

    @Test
    fun theAccountMetricsKeepTheirExactLexicalTextAndNeverInventAValue() = runTest {
        val h = Harness(this)
        h.metricsValue =
            R2AccountMetrics(
                standard =
                    R2ClassMetrics(
                        uploaded =
                            R2SizeMetrics(
                                metadataSize = R2MetricNumber.parse("12345678901234567890.123456789"),
                                objects = R2MetricNumber.parse("9007199254740993"),
                                payloadSize = R2MetricNumber.parse("1.5e+300"),
                            ),
                        published = null,
                    ),
                infrequentAccess = R2ClassMetrics(uploaded = null, published = null),
                interpretable = true,
            )
        h.controller.open(R2CScreen.METRICS)
        runCurrent()
        val metrics = assertNotNull(h.state().metrics)
        val values = metrics.rows.map { it.second }
        assertTrue("12345678901234567890.123456789" in values, values.toString())
        assertTrue("9007199254740993" in values, values.toString())
        assertTrue("1.5e+300" in values, values.toString())
        // A missing class stays explicitly unknown, never 0.
        assertTrue(values.count { it == "未知" } >= 3, values.toString())
        assertNull(metrics.readOnlyReason)
    }

    @Test
    fun aMissingSessionOrRuntimeKeepsTheSurfaceReadOnly() = runTest {
        val h = Harness(this)
        h.prepareAllowed = false
        h.controller.open(R2CScreen.JOBS)
        h.controller.onJobDraftChanged(deletionDraft())
        // runtime == null: the root refuses to prepare and nothing is confirmed.
        assertFalse(h.controller.prepare())
        assertEquals(0, h.prepared.size)
        assertNull(h.controller.confirmReviewed())
        assertNull(h.controller.confirmFinal())
        assertEquals(0, h.confirmed.size)
        assertNotNull(h.state().error)
        assertFalse(h.state().available && h.state().screen == R2CScreen.JOBS && h.state().error == null)
    }
}
