package io.motohub.android.feature.garage

import io.motohub.android.i18n.motoHubText

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.ui.components.HeroPrimaryAction
import io.motohub.android.ui.components.HeroTile
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.ModeIcon
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.theme.MotoHubDashboard
import io.motohub.android.ui.theme.MotoHubManual

@Composable
fun GarageTabContent(
    profiles: List<MotorcycleProfile>,
    activeProfileId: String?,
    onAddMotorcycle: () -> Unit,
    onAddMotorcycleManually: () -> Unit,
    onSelectMotorcycle: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onOpenDefaultSettings: () -> Unit = {}
) {
    val active = profiles.firstOrNull { it.id == activeProfileId }
    val others = profiles.filterNot { it.id == activeProfileId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoLabel(motoHubText("YOUR GARAGE"))
            Text(motoHubText("Motorcycles"), style = MaterialTheme.typography.displaySmall)
        }

        if (active == null) {
            EmptyGarageHero()
            HeroPrimaryAction(
                title = "Scan motorcycle QR code",
                subtitle = "Point your camera at the T-Box sticker",
                icon = "QrScan",
                color = MaterialTheme.colorScheme.primary,
                onClick = onAddMotorcycle
            )
            HeroTile(
                title = "No QR? Manual setup",
                subtitle = "Type the network in yourself",
                icon = "Manual",
                color = MotoHubManual,
                modifier = Modifier.fillMaxWidth(),
                onClick = onAddMotorcycleManually
            )
        } else {
            ActiveMotorcycleCard(
                profile = active,
                onOpenDetails = { onOpenDetails(active.id) }
            )
            if (others.isNotEmpty()) {
                MonoLabel(motoHubText("SAVED MOTORCYCLES"))
                others.forEach { profile ->
                    SavedMotorcycleCard(
                        profile = profile,
                        onSelect = { onSelectMotorcycle(profile.id) },
                        onOpenDetails = { onOpenDetails(profile.id) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroTile(
                    title = "Add motorcycle",
                    subtitle = "Scan its T-Box QR code",
                    icon = "Bike",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onAddMotorcycle
                )
                HeroTile(
                    title = "No QR? Manual",
                    subtitle = "Type the network in",
                    icon = "Manual",
                    color = MotoHubManual,
                    modifier = Modifier.weight(1f),
                    onClick = onAddMotorcycleManually
                )
            }
        }

        // Phone-only Android Auto/Ride Dashboard (no T-Box) already work with zero motorcycles
        // paired - this is where their shared defaults (AA fit, TFT margins, and - Advanced only -
        // dashboard widgets) live, since none of those have a per-motorcycle profile to attach to.
        // Not gated to Advanced: Core's own phone-only Android Auto needs this too.
        MonoLabel(motoHubText("NO MOTORCYCLE? NO PROBLEM"))
        HeroTile(
            title = "Default settings",
            subtitle = "Used by Android Auto & Ride Dashboard without a T-Box",
            icon = "Customize",
            color = MotoHubDashboard,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenDefaultSettings
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActiveMotorcycleCard(
    profile: MotorcycleProfile,
    onOpenDetails: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onOpenDetails)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box {
                MotorcyclePhoto(
                    path = profile.photoPath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(178.dp)
                )
                Box(
                    modifier = Modifier
                        .padding(10.dp)
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    ModeIcon("Bike", MaterialTheme.colorScheme.onPrimary, iconSize = 22.dp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    LivePill(motoHubText("ACTIVE PROFILE"))
                    Text(
                        profile.displayName ?: "Unnamed motorcycle",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        profile.ssid,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onOpenDetails,
                    shape = RoundedCornerShape(12.dp)
                ) { Text(motoHubText("Manage")) }
            }
        }
    }
}

@Composable
private fun SavedMotorcycleCard(
    profile: MotorcycleProfile,
    onSelect: () -> Unit,
    onOpenDetails: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenDetails)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                MotorcyclePhoto(
                    path = profile.photoPath,
                    modifier = Modifier.size(width = 100.dp, height = 78.dp),
                    shape = RoundedCornerShape(14.dp)
                )
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    ModeIcon("Bike", MaterialTheme.colorScheme.onSurfaceVariant, iconSize = 15.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    profile.displayName ?: "Unnamed motorcycle",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    profile.ssid,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Button(
                    onClick = onSelect,
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) { Text(motoHubText("Use this motorcycle")) }
            }
            TextButton(onClick = onOpenDetails) { Text(motoHubText("Edit")) }
        }
    }
}

@Composable
private fun EmptyGarageHero() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                ModeIcon("Bike", MaterialTheme.colorScheme.onPrimary, iconSize = 38.dp)
            }
            Text(
                motoHubText("Your garage is empty"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                motoHubText("Add your first motorcycle to get rolling."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
