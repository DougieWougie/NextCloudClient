package com.nextcloud.sync.ui.screens.twofactornotification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.nextcloud.sync.controllers.auth.TwoFactorController
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.network.NextcloudAuthenticator
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.ui.navigation.Screen
import com.nextcloud.sync.utils.AuthRateLimiter

/**
 * Screen for notification-based two-factor authentication.
 *
 * Displays a waiting state while polling for user approval/denial
 * on another logged-in device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFactorNotificationScreen(
    accountId: Long,
    navController: NavHostController,
    context: android.content.Context
) {
    val db = AppDatabase.getInstance(context)
    val accountRepository = AccountRepository(db.accountDao())
    val authenticator = NextcloudAuthenticator(context)
    val rateLimiter = com.nextcloud.sync.utils.AuthRateLimiter.getInstance(context)
    val twoFactorController = TwoFactorController(accountRepository, authenticator, rateLimiter)

    val viewModel: TwoFactorNotificationViewModel = viewModel(
        factory = TwoFactorNotificationViewModel.Factory(twoFactorController, accountId)
    )

    val uiState by viewModel.uiState.collectAsState()

    // Navigate to main screen on success
    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            navController.navigate(Screen.MainContainer.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify Login") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                uiState.isLoading -> {
                    // Waiting state
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Check your other logged-in devices and approve the login request.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    OutlinedButton(
                        onClick = {
                            viewModel.onCancelClicked()
                            navController.popBackStack()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                }

                uiState.isSuccess -> {
                    // Success state
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Redirecting to your account...",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                uiState.errorMessage != null -> {
                    // Error state
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.error
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = uiState.errorMessage ?: "An error occurred",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    if (uiState.hasTimedOut) {
                        // Show options for timeout: retry or use TOTP
                        Button(
                            onClick = { viewModel.onRetryClicked() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Retry Notification")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                // Navigate to TOTP screen instead
                                navController.navigate(Screen.TwoFactor.createRoute(accountId)) {
                                    popUpTo(Screen.TwoFactorProvider.createRoute(accountId)) { inclusive = true }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use Authenticator Code Instead")
                        }
                    } else {
                        Button(
                            onClick = {
                                navController.popBackStack()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Go Back")
                        }
                    }
                }
            }
        }
    }
}
