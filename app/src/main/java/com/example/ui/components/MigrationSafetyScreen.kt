package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MigrationSafetyScreen(
    onBackupRequested: () -> Unit,
    onTestMigrationRequested: () -> Unit,
    onApplyMigrationRequested: () -> Unit,
    onCancelRequested: () -> Unit = {},
    backupCompleted: Boolean = false,
    testCompleted: Boolean = false,
    migrationStatus: String = ""
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "Finalizing Setup",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "⚠️ Major Update Required",
                color = Color(0xFFD32F2F),
                fontWeight = FontWeight.SemiBold
            )
            
            Text(
                text = "We are optimizing your data for a faster experience.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            
            // Step 1: Backup
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(checked = backupCompleted, onCheckedChange = null)
                Text("Step 1: Backup Data", fontWeight = FontWeight.Medium)
            }
            
            // Step 2: Test
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(checked = testCompleted, onCheckedChange = null)
                Text("Step 2: Dry Run (Safe)", fontWeight = FontWeight.Medium)
            }
            
            // Step 3: Apply
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(checked = false, onCheckedChange = null)
                Text("Step 3: Finalize", fontWeight = FontWeight.Medium)
            }
            
            if (migrationStatus.isNotEmpty()) {
                Text(
                    text = migrationStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = onBackupRequested,
                    colors = if (backupCompleted) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)) else ButtonDefaults.buttonColors()
                ) {
                    Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (backupCompleted) "Redo Backup" else "Backup Now")
                }
                
                Button(
                    onClick = onTestMigrationRequested,
                    enabled = backupCompleted,
                    colors = if (testCompleted) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)) else ButtonDefaults.buttonColors()
                ) {
                    Text(if (testCompleted) "Redo Test" else "Test")
                }
                
                Button(
                    onClick = onApplyMigrationRequested,
                    enabled = testCompleted && backupCompleted,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Finish")
                }
            }

            TextButton(
                onClick = onCancelRequested,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Not Now / Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
