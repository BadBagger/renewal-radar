package com.renewalradar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.plaid.link.FastOpenPlaidLink
import com.plaid.link.Plaid
import com.plaid.link.linkTokenConfiguration
import com.plaid.link.result.LinkExit
import com.plaid.link.result.LinkSuccess
import com.renewalradar.app.data.PlaidAccountMetadata
import com.renewalradar.app.data.PlaidInstitutionMetadata
import com.renewalradar.app.data.PlaidLinkMetadata
import com.renewalradar.app.ui.RenewalRadarRoot
import com.renewalradar.app.ui.RenewalViewModel
import com.renewalradar.app.ui.theme.RenewalRadarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RenewalRadarApp
        setContent {
            val viewModel: RenewalViewModel = viewModel(
                factory = RenewalViewModel.Factory(
                    app.repository,
                    app.settingsStore,
                    app.bankConnectionRepository,
                    app.bankSyncRepository,
                    app.renewalCandidateRepository
                )
            )
            val state by viewModel.uiState.collectAsState()

            RenewalRadarTheme(darkTheme = state.settings.darkModeEnabled) {
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {}
                val plaidLauncher = rememberLauncherForActivityResult(FastOpenPlaidLink()) { result ->
                    when (result) {
                        is LinkSuccess -> {
                            val publicToken = result.publicToken
                            if (publicToken.isNullOrBlank()) {
                                viewModel.handlePlaidExit("Plaid Link completed without a public token. Try connecting again.")
                            } else {
                                viewModel.handlePlaidSuccess(
                                    publicToken = publicToken,
                                    metadata = result.toRenewalMetadata()
                                )
                            }
                        }
                        is LinkExit -> {
                            val errorCode = result.error?.errorCode?.toString().orEmpty()
                            viewModel.handlePlaidExit(
                                message = result.toUserMessage(),
                                permissionRevoked = errorCode.contains("REVOKED", ignoreCase = true) ||
                                    errorCode.contains("RELINK", ignoreCase = true)
                            )
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    Plaid.setLinkEventListener { }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                LaunchedEffect(state.pendingPlaidLinkToken) {
                    val token = state.pendingPlaidLinkToken ?: return@LaunchedEffect
                    val handler = Plaid.create(
                        application,
                        linkTokenConfiguration {
                            this.token = token
                        }
                    )
                    plaidLauncher.launch(handler)
                    viewModel.markPlaidLinkLaunched()
                }

                RenewalRadarRoot(
                    state = state,
                    onSave = viewModel::save,
                    onDelete = viewModel::delete,
                    onSettingsChange = viewModel::updateSettings,
                    onConnectAccount = viewModel::startPlaidLink,
                    onSyncBankAccounts = viewModel::syncBankAccounts,
                    onDisconnectAccount = viewModel::disconnectAccount,
                    onUpdateCandidate = viewModel::updateCandidate,
                    onConfirmCandidate = viewModel::confirmCandidate,
                    onConfirmCandidates = viewModel::confirmCandidates,
                    onIgnoreCandidate = viewModel::ignoreCandidate,
                    onIgnoreCandidates = viewModel::ignoreCandidates,
                    onDismissCandidate = viewModel::dismissCandidate,
                    onDeleteCandidate = viewModel::deleteCandidate
                )
            }
        }
    }
}

private fun LinkSuccess.toRenewalMetadata(): PlaidLinkMetadata =
    PlaidLinkMetadata(
        institution = metadata.institution?.let {
            PlaidInstitutionMetadata(
                id = it.id,
                name = it.name
            )
        },
        accounts = metadata.accounts.map {
            PlaidAccountMetadata(
                id = it.id,
                name = it.name ?: "Account",
                mask = it.mask,
                type = it.subtype.accountType.json,
                subtype = it.subtype.json
            )
        }
    )

private fun LinkExit.toUserMessage(): String {
    val error = error
    if (error != null) {
        val display = error.displayMessage
        if (!display.isNullOrBlank()) return display
        val message = error.errorMessage
        if (!message.isNullOrBlank()) return message
    }
    return when (metadata.status?.jsonValue) {
        "institution_not_supported" -> "Institution connection failed or is not supported."
        "institution_not_found" -> "Institution not found. Try searching another bank or card issuer."
        "requires_credentials" -> "Plaid Link was canceled before connection finished."
        else -> "Plaid Link was canceled. Renewal tracking still works."
    }
}
