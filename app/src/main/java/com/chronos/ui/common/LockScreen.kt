package com.chronos.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What shows while the app is locked: nothing of your plan or log, only the way in (31 Oct 2026). */
@Composable
fun LockScreen(unlock: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Chronos is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Hint("Use your fingerprint or the phone's screen lock to open it.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = unlock) { Text("Unlock") }
    }
}
