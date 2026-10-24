package com.flarego.ui.r2

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2Command
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class R2CReadAndIntentTest {
    private val context = R2BrowserContext(Connection("read", "fixture", "a".repeat(32)), 1, 1)
    private inner class Harness(scope: TestScope) {
        var queueCount = 1
        val observedJobs = mutableListOf<String>()
        val targets = mutableListOf<R2WriteTarget>()
        val api = object : R2NotificationSession {
            override suspend fun listConfigurations(target: R2WriteTarget): R2NotificationCollection {
                targets += target
                return R2NotificationCollection(target.bucket, (1..queueCount).map { n ->
                    R2NotificationQueue("queue-$n", "events-$n", listOf(
                        R2NotificationRule("rule-$n", listOf("PutObject"), null, null, null, "2026-10-24T00:00:00Z")), true)
                }, true)
            }
            override suspend fun getConfiguration(target: R2WriteTarget, queueId: String) = listConfigurations(target).queues.single { it.queueId == queueId }
            override suspend fun appendRules(target: R2WriteTarget, queueId: String, rules: List<R2NotificationRuleSpec>) = error("read-only fixture")
            override suspend fun deleteRules(target: R2WriteTarget, queueId: String, deletion: R2NotificationDeletion) = error("read-only fixture")
            override suspend fun writeNotification(write: R2NotificationWrite, beforeSend: (R2NotificationQueue) -> Unit) = error("read-only fixture")
        }
        val jobApi = object : R2JobSession {
            override suspend fun listJobs(target: R2WriteTarget, query: R2JobListQuery): R2JobPage {
                targets += target
                return R2JobPage(listOf(row("provider-job", R2JobStatus.ENQUEUED)), null, true)
            }
            override suspend fun getJob(target: R2WriteTarget, jobId: String): R2BucketJob {
                targets += target; observedJobs += jobId
                return row(jobId, R2JobStatus.COMPLETED)
            }
            override suspend fun createJob(target: R2WriteTarget, request: R2JobCreateRequest): R2JobSubmission = error("read-only fixture")
            override suspend fun createJob(target: R2WriteTarget, request: R2JobCreateRequest, beforeSend: () -> Unit): R2JobSubmission = error("read-only fixture")
            override suspend fun authorizeCreate(target: R2WriteTarget, request: R2JobCreateRequest): R2JobCreateAuthorization = error("read-only fixture")
        }
        val controller = R2CController(scope.backgroundScope, { context }, { false }, bucket = { "my-bucket" },
            confirmCommand = { null }, notifications = { api }, jobs = { jobApi })
    }
    private fun row(id: String, status: R2JobStatus) = R2BucketJob(id, R2JobType.PREFIX_DELETE, status, null, null,
        R2JobDetails.PrefixDelete(R2PrefixDeleteDetails("logs/", false, 3)), true)

    @Test fun editingAnAppendExplicitlyLeavesSelectedDeletionMode() = runTest {
        val h = Harness(this); h.controller.open(R2CScreen.NOTIFICATIONS); runCurrent()
        h.controller.onSelectionChanged(setOf("rule-1"))
        assertTrue((h.controller.liveCommand() as R2Command.Notification).write is R2NotificationWrite.Delete)
        h.controller.onNotificationDraftChanged(R2CNotificationDraft(actions = setOf("PutObject"), prefix = "images/"))
        assertTrue((h.controller.liveCommand() as R2Command.Notification).write is R2NotificationWrite.Append)
        assertTrue(h.controller.state.value.selectedRuleIds.isEmpty())
    }

    @Test fun explicitClearAllCannotRemainSelectedDeletionAndCancelledClearDoesNotReplayIt() = runTest {
        val h = Harness(this); h.controller.open(R2CScreen.NOTIFICATIONS); runCurrent()
        h.controller.onSelectionChanged(setOf("rule-1")); h.controller.onClearAllRequested(true)
        val clear = (h.controller.liveCommand() as R2Command.Notification).write as R2NotificationWrite.Delete
        assertTrue(clear.deletion is R2NotificationDeletion.All)
        assertTrue(h.controller.state.value.selectedRuleIds.isEmpty())
        h.controller.onClearAllRequested(false)
        assertNull(h.controller.liveCommand(), "cancelled clear must not fall back to an old selected delete")
    }

    @Test fun choosingBetweenSeveralQueuesBindsTheActualQueueAndClearsForeignRuleSelection() = runTest {
        val h = Harness(this); h.queueCount = 2; h.controller.open(R2CScreen.NOTIFICATIONS); runCurrent()
        h.controller.onNotificationDraftChanged(R2CNotificationDraft(actions = setOf("PutObject")))
        assertNull(h.controller.liveCommand(), "multiple queues require an explicit choice")
        h.controller.onQueueSelected("queue-2")
        val command = assertNotNull(h.controller.liveCommand()) as R2Command.Notification
        assertEquals("queue-2", command.write.queueId)
        h.controller.onSelectionChanged(setOf("rule-2")); h.controller.onQueueSelected("queue-1")
        assertTrue(h.controller.state.value.selectedRuleIds.isEmpty())
    }

    @Test fun allJurisdictionsBindTheReadAndLiveDraftToTheSameExplicitTarget() = runTest {
        for (region in R2_JURISDICTIONS) {
            val h = Harness(this); h.controller.open(R2CScreen.NOTIFICATIONS); runCurrent()
            h.controller.onJurisdictionChanged(region); runCurrent()
            h.controller.onNotificationDraftChanged(R2CNotificationDraft(actions = setOf("PutObject")))
            val command = assertNotNull(h.controller.liveCommand()) as R2Command.Notification
            assertEquals(region, command.write.target.jurisdiction)
            assertEquals(region, h.targets.last().jurisdiction)
            assertEquals("my-bucket", h.targets.last().bucket)
        }
    }

    @Test fun aProviderJobIdUsesTheReadPortNotTheLocalTaskStore() = runTest {
        val h = Harness(this); h.controller.open(R2CScreen.JOBS); runCurrent()
        h.controller.refreshJobTask("provider-job"); runCurrent()
        assertEquals(listOf("provider-job"), h.observedJobs)
        assertEquals("COMPLETED", h.controller.state.value.jobs.single().statusLabel)
    }
}
