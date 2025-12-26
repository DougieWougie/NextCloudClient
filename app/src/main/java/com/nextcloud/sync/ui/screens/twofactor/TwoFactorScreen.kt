package com.nextcloud.sync.ui.screens.twofactor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextcloud.sync.ui.components.ErrorDialog
import com.nextcloud.sync.ui.components.InlineLoadingIndicator

@Composable
fun TwoFactorScreen(
    viewModel: TwoFactorViewModel,
    onNavigateToMain: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Handle navigation on success
    LaunchedEffect(uiState.verificationSuccess) {
        if (uiState.verificationSuccess) {
            onNavigateToMain()
        }
    }

    // Show error dialog
    if (uiState.verificationError != null) {
        ErrorDialog(
            title = "Verification Failed",
            message = uiState.verificationError!!,
            onDismiss = { viewModel.onEvent(TwoFactorEvent.ErrorDismissed) }
        )
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Two-Factor Authentication",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Enter the 6-digit code from your authenticator app",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = uiState.otpCode,
                onValueChange = {
                    if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                        viewModel.onEvent(TwoFactorEvent.OtpCodeChanged(it))
                    }
                },
                label = { Text("6-digit code") },
                isError = uiState.otpError != null,
                supportingText = uiState.otpError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.onEvent(TwoFactorEvent.VerifyClicked) },
                enabled = !uiState.isLoading && uiState.otpCode.length == 6,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isLoading) {
                    InlineLoadingIndicator()
                } else {
                    Text("Verify")
                }
            }
        }
    }
}
