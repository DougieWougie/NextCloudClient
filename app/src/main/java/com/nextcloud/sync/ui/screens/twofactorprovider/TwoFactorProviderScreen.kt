package com.nextcloud.sync.ui.screens.twofactorprovider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.nextcloud.sync.models.data.TwoFactorProvider
import com.nextcloud.sync.models.data.TwoFactorProviderType
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.ui.navigation.Screen

/**
 * Screen for selecting a two-factor authentication provider.
 *
 * Displays the list of available 2FA providers (e.g., TOTP, Notification)
 * and allows the user to select one to proceed with verification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFactorProviderScreen(
    accountId: Long,
    navController: NavHostController,
    context: android.content.Context
) {
    val db = AppDatabase.getInstance(context)
    val accountRepository = AccountRepository(db.accountDao())

    val viewModel: TwoFactorProviderViewModel = viewModel(
        factory = TwoFactorProviderViewModel.Factory(accountRepository, accountId)
    )

    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose Verification Method") }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                uiState.errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "An error occurred",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                uiState.providers.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No 2FA providers available",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = "Select how you want to verify your identity:",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        items(uiState.providers) { provider ->
                            ProviderCard(
                                provider = provider,
                                isSelected = uiState.selectedProvider == provider,
                                onClick = {
                                    viewModel.onProviderSelected(provider)
                                    // Navigate to appropriate verification screen
                                    when (provider.type) {
                                        TwoFactorProviderType.TOTP -> {
                                            navController.navigate(Screen.TwoFactor.createRoute(accountId))
                                        }
                                        TwoFactorProviderType.NOTIFICATION -> {
                                            navController.navigate(Screen.TwoFactorNotification.createRoute(accountId))
                                        }
                                        else -> {
                                            // Not yet implemented
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProviderCard(
    provider: TwoFactorProvider,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 8.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getProviderIcon(provider.type),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.primary
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = getProviderDescription(provider.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun getProviderIcon(type: TwoFactorProviderType): ImageVector {
    return when (type) {
        TwoFactorProviderType.TOTP -> Icons.Default.Code
        TwoFactorProviderType.NOTIFICATION -> Icons.Default.Notifications
        else -> Icons.Default.Code
    }
}

private fun getProviderDescription(type: TwoFactorProviderType): String {
    return when (type) {
        TwoFactorProviderType.TOTP -> "Enter a 6-digit code from your authenticator app"
        TwoFactorProviderType.NOTIFICATION -> "Approve the login request on your other device"
        TwoFactorProviderType.WEBAUTHN -> "Use your security key or biometrics"
        TwoFactorProviderType.U2F -> "Use your U2F security key"
        TwoFactorProviderType.BACKUP_CODES -> "Use one of your backup recovery codes"
        TwoFactorProviderType.UNKNOWN -> "Unknown authentication method"
    }
}
