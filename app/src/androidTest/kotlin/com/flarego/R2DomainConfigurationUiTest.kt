package com.flarego

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flarego.ui.r2.*
import org.junit.Rule
import org.junit.Test

/**
 * Batch B2 domain panels as pure visuals: the screen receives state and emits intents, and never
 * holds a plan id, a command or a session. These assertions are behaviour assertions on the real
 * Compose tree (a missing control or a missing click result fails), not source greps.
 */
class R2DomainConfigurationUiTest {
    @get:Rule val compose = createComposeRule()

    private fun screen(state: R2ConfigurationUiState, intents: MutableList<R2ConfigurationIntent>) {
        compose.setContent { R2ConfigurationScreen(state = state, onIntent = { intents += it }) }
    }

    private fun base(panel: R2ConfigurationPanel) =
        R2ConfigurationUiState(
            open = true,
            panel = panel,
            target = R2ConfigurationDraftTarget("cfg-bucket", "default"),
            editable = true,
            domainWritesAvailable = true,
            managedDomainHost = "pub-0123.r2.dev",
            managedDomainEnabled = true,
        )

    /** A control that exists semantically but has no touch area is not usable at a large font. */
    private fun assertTouchable(tag: String) {
        // Scroll first: at a large font the control may start outside the viewport, and the point of
        // this check is the *visible* touch area a user can really hit.
        compose.onNodeWithTag(tag).performScrollTo()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assert(bounds.width > 24f && bounds.height > 16f) { "$tag has no usable touch area: $bounds" }
    }

    private fun domainRow(domain: String, enabled: Boolean = true, minTls: String? = "1.2") =
        R2CustomDomainDraft(
            domain = domain,
            enabled = enabled,
            minTls = minTls,
            ciphers = listOf("ECDHE-ECDSA-AES128-GCM-SHA256"),
            zoneId = "0123456789abcdef0123456789abcdef",
            zoneName = "example.com",
            ownership = "pending",
            ssl = "initializing",
        )

    @Test
    fun theManagedPanelShowsTheHostAnExplicitSwitchAndOnlyTheR2DevImpact() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        screen(base(R2ConfigurationPanel.MANAGED_DOMAIN), intents)

        compose.onNodeWithTag("rc-managed-host").performScrollTo().assertTextContains("pub-0123.r2.dev", substring = true)
        compose.onNodeWithTag("rc-managed-switch").performScrollTo().assertIsOn()
        // Closing the r2.dev endpoint is explained as exactly that, not as privacy or custom-domain state.
        compose.onNodeWithTag("rc-managed-notice").assertTextContains("r2.dev", substring = true)
        compose.onNodeWithTag("rc-managed-notice").assertTextContains("不代表", substring = true)

        compose.onNodeWithTag("rc-managed-switch").performClick()
        assert(intents.any { it is R2ConfigurationIntent.ManagedDomainEnabledChanged && !it.value })
        // Public exposure is an independent acknowledgement of its own.
        compose.onNodeWithTag("rc-managed-public-risk").performScrollTo().performClick()
        assert(
            intents.any {
                it is R2ConfigurationIntent.RisksChanged && it.risks.publicExposureAcknowledged
            },
        )
    }

    @Test
    fun theCustomPanelListsEveryDomainAndSelectsOneByAClick() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = base(R2ConfigurationPanel.CUSTOM_DOMAINS).copy(
            customDomains = listOf(domainRow("cdn.example.com"), domainRow("static.example.com", enabled = false)),
        )
        screen(state, intents)

        compose.onNodeWithTag("rc-domain-row-cdn.example.com").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-domain-row-static.example.com").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-domain-select-cdn.example.com").performScrollTo().performClick()
        assert(intents.contains(R2ConfigurationIntent.SelectCustomDomain("cdn.example.com")))
        // Create mode owns the create form; the editor belongs to the selected mode.
        compose.onNodeWithTag("rc-domain-create-domain").performScrollTo().assertIsDisplayed()
        assert(compose.onAllNodesWithTag("rc-domain-edit-enabled").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theSelectedModeOffersTheExplicitWayBackToCreate() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = base(R2ConfigurationPanel.CUSTOM_DOMAINS).copy(
            customDomains = listOf(domainRow("cdn.example.com")),
            selectedDomain = "cdn.example.com",
        )
        screen(state, intents)
        compose.onNodeWithTag("rc-domain-new").performScrollTo().assertIsDisplayed().performClick()
        assert(intents.contains(R2ConfigurationIntent.SelectCustomDomain(null)))
        // In the selected mode the create form is not shown at all, so a draft can never be mistaken
        // for an edit of the selected domain.
        compose.onNodeWithTag("rc-domain-create-domain").assertDoesNotExist()
        compose.onNodeWithTag("rc-domain-edit-enabled").performScrollTo().assertIsDisplayed()
        assert(intents.none { it is R2ConfigurationIntent.CustomDomainCreateDomainChanged })
    }

    @Test
    fun turningPublicAccessBackOnNeedsThePublicAcknowledgementAgain() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        // The provider currently has public access on; the draft first disables, then re-enables it.
        val draft = androidx.compose.runtime.mutableStateOf<R2CustomDomainEditDraft>(R2CustomDomainEditDraft(enabled = false))
        val state = base(R2ConfigurationPanel.CUSTOM_DOMAINS).copy(
            customDomains = listOf(domainRow("cdn.example.com", enabled = true)),
            selectedDomain = "cdn.example.com",
        )
        compose.setContent {
            R2ConfigurationScreen(state = state.copy(customDomainEdit = draft.value), onIntent = { intents += it })
        }
        compose.onNodeWithTag("rc-domain-edit-public-risk").assertDoesNotExist()
        compose.runOnIdle { draft.value = R2CustomDomainEditDraft(enabled = true) }
        compose.waitForIdle()
        compose.onNodeWithTag("rc-domain-edit-public-risk").performScrollTo().assertIsOff()
        compose.onNodeWithTag("rc-domain-edit-public-risk").performClick()
        assert(intents.any { it is R2ConfigurationIntent.RisksChanged && it.risks.publicExposureAcknowledged })
    }

    @Test
    fun theEditorShowsOwnershipSslSeparatelyAndCiphersAsReadOnly() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = base(R2ConfigurationPanel.CUSTOM_DOMAINS).copy(
            customDomains = listOf(domainRow("cdn.example.com")),
            selectedDomain = "cdn.example.com",
            customDomainEdit = R2CustomDomainEditDraft(enabled = false, minTls = "1.2"),
        )
        screen(state, intents)

        compose.onNodeWithTag("rc-domain-ownership").performScrollTo().assertTextContains("pending", substring = true)
        compose.onNodeWithTag("rc-domain-ssl").performScrollTo().assertTextContains("initializing", substring = true)
        // Saved configuration is not a certificate/ownership/public-ready claim.
        compose.onNodeWithTag("rc-domain-provider-states-notice").assertTextContains("保存成功", substring = true)
        // Ciphers are display only: the current provider value, with no input and no clear action.
        compose.onNodeWithTag("rc-domain-ciphers").performScrollTo()
            .assertTextContains("ECDHE-ECDSA-AES128-GCM-SHA256", substring = true)
        compose.onNodeWithTag("rc-domain-ciphers-edit").assertDoesNotExist()
        compose.onNodeWithTag("rc-domain-ciphers-clear").assertDoesNotExist()

        compose.onAllNodesWithTag("rc-typed-domain-name").assertCountEquals(1)
        compose.onAllNodesWithTag("rc-typed-target-name").assertCountEquals(1)
        assertTouchable("rc-domain-edit-enabled")
        compose.onNodeWithTag("rc-domain-edit-enabled").performScrollTo().performClick()
        assert(intents.any { it is R2ConfigurationIntent.CustomDomainEditEnabledChanged })
    }

    @Test
    fun theCreateFormDefaultsToDisabledWithTls12AndReadsTheZoneId() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = base(R2ConfigurationPanel.CUSTOM_DOMAINS).copy(customDomains = emptyList())
        screen(state, intents)

        compose.onAllNodesWithTag("rc-domain-create-domain").assertCountEquals(1)
        compose.onAllNodesWithTag("rc-typed-domain-name").assertCountEquals(1)
        compose.onAllNodesWithTag("rc-typed-target-name").assertCountEquals(1)
        compose.onNodeWithTag("rc-domain-create-domain").performScrollTo().performTextInput("new.example.com")
        assert(
            intents.any {
                it is R2ConfigurationIntent.CustomDomainCreateDomainChanged && it.value == "new.example.com"
            },
        )
        compose.onNodeWithTag("rc-domain-create-zone").performScrollTo().performTextInput("0123456789abcdef")
        assert(intents.any { it is R2ConfigurationIntent.CustomDomainCreateZoneIdChanged })
        // A new domain is never published and never starts below TLS 1.2.
        compose.onNodeWithTag("rc-domain-create-enabled").performScrollTo().assertIsOff()
        compose.onNodeWithTag("rc-domain-create-tls-1.2").performScrollTo().assertIsSelected()
        assertTouchable("rc-domain-create-enabled")
        compose.onNodeWithTag("rc-domain-create-enabled").performScrollTo().performClick()
        assert(
            intents.any {
                it is R2ConfigurationIntent.CustomDomainCreateEnabledChanged && it.value
            },
        )
    }

    @Test
    fun loweringTlsRequiresItsOwnExplicitAcknowledgement() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val editTls = androidx.compose.runtime.mutableStateOf<String?>("1.2")
        val state = base(R2ConfigurationPanel.CUSTOM_DOMAINS).copy(
            customDomains = listOf(domainRow("cdn.example.com", minTls = "1.3")),
            selectedDomain = "cdn.example.com",
        )
        compose.setContent {
            R2ConfigurationScreen(
                state = state.copy(customDomainEdit = R2CustomDomainEditDraft(minTls = editTls.value)),
                onIntent = { intents += it },
            )
        }

        compose.onNodeWithTag("rc-domain-tls-downgrade-risk").performScrollTo().assertIsOff()
        compose.onNodeWithTag("rc-domain-tls-downgrade-risk").performClick()
        assert(
            intents.any {
                it is R2ConfigurationIntent.RisksChanged && it.risks.tlsDowngradeAcknowledged
            },
        )
        // Raising the pending TLS to the current value removes the downgrade acknowledgement.
        compose.runOnIdle {
            editTls.value = "1.3"
        }
        compose.waitForIdle()
        compose.onNodeWithTag("rc-domain-tls-downgrade-risk").assertDoesNotExist()
    }

    @Test
    fun theDeleteModeNeedsTheExactDomainAndHidesTheEditor() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = base(R2ConfigurationPanel.CUSTOM_DOMAINS).copy(
            customDomains = listOf(domainRow("cdn.example.com")),
            selectedDomain = "cdn.example.com",
            domainDeleteRequested = true,
        )
        screen(state, intents)

        compose.onNodeWithTag("rc-domain-delete-card").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("rc-typed-domain-name").assertCountEquals(1)
        compose.onAllNodesWithTag("rc-typed-target-name").assertCountEquals(1)
        compose.onNodeWithTag("rc-domain-tls-downgrade-risk").assertDoesNotExist()
        compose.onNodeWithTag("rc-domain-create-domain").assertDoesNotExist()
        compose.onNodeWithTag("rc-domain-delete-risk").performScrollTo().assertIsOff()
        compose.onNodeWithTag("rc-domain-delete-risk").performClick()
        assert(intents.any { it is R2ConfigurationIntent.RisksChanged && it.risks.destructiveImpactAcknowledged })
        compose.onNodeWithTag("rc-typed-domain-name").performScrollTo().performTextInput("cdn.example.com")
        assert(
            intents.any {
                it is R2ConfigurationIntent.TypedDomainNameChanged && it.value == "cdn.example.com"
            },
        )
        compose.onNodeWithTag("rc-domain-cancel-delete").performScrollTo().performClick()
        assert(intents.contains(R2ConfigurationIntent.CancelDeleteCustomDomain))
        // DELETE is its own request, never a PUT with an empty list.
        compose.onNodeWithTag("rc-domain-delete-submit").assertIsNotEnabled()
    }

    @Test
    fun theReadOnlyCreateModeDisablesEveryWriteControl() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = base(R2ConfigurationPanel.CUSTOM_DOMAINS).copy(
            customDomains = listOf(domainRow("cdn.example.com")),
            selectedDomain = null,
            domainWritesAvailable = false,
            readOnlyReason = "该域名配置包含应用尚未支持的字段",
            editable = false,
        )
        screen(state, intents)
        compose.onNodeWithTag("rc-domain-readonly-reason").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-domain-create-domain").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rc-domain-create-zone").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rc-domain-create-enabled").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rc-preview-button").performScrollTo().assertIsNotEnabled()
        assert(intents.isEmpty())
    }

    @Test
    fun theReadOnlyEditorModeKeepsTheDetailsVisibleButDisablesWrites() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = base(R2ConfigurationPanel.CUSTOM_DOMAINS).copy(
            customDomains = listOf(domainRow("cdn.example.com")),
            selectedDomain = "cdn.example.com",
            domainWritesAvailable = false,
            readOnlyReason = "该域名配置包含应用尚未支持的字段",
            editable = false,
        )
        screen(state, intents)
        // Reading the current state stays possible: only the writes are disabled.
        compose.onNodeWithTag("rc-domain-ownership").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-domain-ssl").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-domain-ciphers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-domain-edit-enabled").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rc-domain-request-delete").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rc-typed-domain-name").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rc-preview-button").performScrollTo().assertIsNotEnabled()
        assert(intents.isEmpty())
    }
}
