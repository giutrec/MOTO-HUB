package io.motohub.android.feature.navigation

import androidx.compose.runtime.Composable

/**
 * Core never shows the Nav tab (see HubBottomNavigation - "Nav and Trips are PRO-only
 * features") and the whole standalone Navigation feature (search/routing/GPX) lives in
 * app/src/pro. This no-op only exists so MainActivity.kt (shared) can reference a single
 * `NavTabContent()` symbol regardless of flavor, without needing a runtime IS_PRO branch.
 */
@Composable
fun NavTabContent() {
}
