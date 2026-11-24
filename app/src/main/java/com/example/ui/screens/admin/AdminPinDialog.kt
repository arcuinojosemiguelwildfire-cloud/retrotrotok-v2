package com.example.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.RetroOnyx
import com.example.ui.theme.RetroSurface
import com.example.ui.theme.RetroSurfaceBorder
import com.example.ui.theme.RetroSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AdminPinDialog(
    enteredPin: String,
    pinError: String?,
    isLockedOut: Boolean,
    lockoutSeconds: Int,
    onDigitClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RetroSurface,
        modifier = modifier.testTag("admin_pin_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = NeonMagenta,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ADMIN ACCESS",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        ),
                        color = TextPrimary
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Enter 4-digit Master PIN to configure kiosk settings and manage event content.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // PIN Masked Circles
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    repeat(4) { index ->
                        val isFilled = index < enteredPin.length
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (isFilled) NeonMagenta else RetroSurfaceElevated)
                                .border(
                                    2.dp,
                                    if (isFilled) NeonMagenta else RetroSurfaceBorder,
                                    CircleShape
                                )
                        )
                    }
                }

                // Error / Lockout Banner
                if (pinError != null) {
                    Text(
                        text = pinError,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLockedOut) Color.Red else NeonMagenta,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Numeric Keypad
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("CLEAR", "0", "DEL")
                    )

                    rows.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { key ->
                                KeypadButton(
                                    label = key,
                                    enabled = !isLockedOut,
                                    onClick = {
                                        when (key) {
                                            "CLEAR" -> onClear()
                                            "DEL" -> onBackspace()
                                            else -> onDigitClick(key)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Authorized event staff only. Locked out for 60s after 5 incorrect attempts.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun KeypadButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 68.dp, height = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RetroSurfaceElevated)
            .border(1.dp, RetroSurfaceBorder, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("keypad_button_$label"),
        contentAlignment = Alignment.Center
    ) {
        if (label == "DEL") {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Backspace",
                tint = if (enabled) TextPrimary else TextMuted,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = label,
                fontSize = if (label == "CLEAR") 11.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) TextPrimary else TextMuted
            )
        }
    }
}
