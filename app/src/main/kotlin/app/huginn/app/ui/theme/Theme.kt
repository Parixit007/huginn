package app.huginn.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import app.huginn.app.R

/**
 * Material 3 in Huginn's lime on every phone (D93, replaces D83's wallpaper colours); light/dark (D29).
 * The colours live in `res/values` and `res/values-night`, shared with the window theme.
 */
@Composable
fun HuginnTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = huginnColors(), content = content)
}

/** Every role comes from resources, so the builder's own defaults are never used and night mode picks the dark set. */
@Composable
private fun huginnColors(): ColorScheme =
    lightColorScheme(
        primary = colorResource(R.color.theme_primary),
        onPrimary = colorResource(R.color.theme_on_primary),
        primaryContainer = colorResource(R.color.theme_primary_container),
        onPrimaryContainer = colorResource(R.color.theme_on_primary_container),
        inversePrimary = colorResource(R.color.theme_inverse_primary),
        secondary = colorResource(R.color.theme_secondary),
        onSecondary = colorResource(R.color.theme_on_secondary),
        secondaryContainer = colorResource(R.color.theme_secondary_container),
        onSecondaryContainer = colorResource(R.color.theme_on_secondary_container),
        tertiary = colorResource(R.color.theme_tertiary),
        onTertiary = colorResource(R.color.theme_on_tertiary),
        tertiaryContainer = colorResource(R.color.theme_tertiary_container),
        onTertiaryContainer = colorResource(R.color.theme_on_tertiary_container),
        background = colorResource(R.color.theme_background),
        onBackground = colorResource(R.color.theme_on_background),
        surface = colorResource(R.color.theme_surface),
        onSurface = colorResource(R.color.theme_on_surface),
        surfaceVariant = colorResource(R.color.theme_surface_variant),
        onSurfaceVariant = colorResource(R.color.theme_on_surface_variant),
        surfaceTint = colorResource(R.color.theme_primary),
        inverseSurface = colorResource(R.color.theme_inverse_surface),
        inverseOnSurface = colorResource(R.color.theme_inverse_on_surface),
        error = colorResource(R.color.theme_error),
        onError = colorResource(R.color.theme_on_error),
        errorContainer = colorResource(R.color.theme_error_container),
        onErrorContainer = colorResource(R.color.theme_on_error_container),
        outline = colorResource(R.color.theme_outline),
        outlineVariant = colorResource(R.color.theme_outline_variant),
        scrim = colorResource(R.color.theme_scrim),
        surfaceBright = colorResource(R.color.theme_surface_bright),
        surfaceDim = colorResource(R.color.theme_surface_dim),
        surfaceContainerLowest = colorResource(R.color.theme_surface_container_lowest),
        surfaceContainerLow = colorResource(R.color.theme_surface_container_low),
        surfaceContainer = colorResource(R.color.theme_surface_container),
        surfaceContainerHigh = colorResource(R.color.theme_surface_container_high),
        surfaceContainerHighest = colorResource(R.color.theme_surface_container_highest),
        primaryFixed = colorResource(R.color.theme_primary_fixed),
        primaryFixedDim = colorResource(R.color.theme_primary_fixed_dim),
        onPrimaryFixed = colorResource(R.color.theme_on_primary_fixed),
        onPrimaryFixedVariant = colorResource(R.color.theme_on_primary_fixed_variant),
        secondaryFixed = colorResource(R.color.theme_secondary_fixed),
        secondaryFixedDim = colorResource(R.color.theme_secondary_fixed_dim),
        onSecondaryFixed = colorResource(R.color.theme_on_secondary_fixed),
        onSecondaryFixedVariant = colorResource(R.color.theme_on_secondary_fixed_variant),
        tertiaryFixed = colorResource(R.color.theme_tertiary_fixed),
        tertiaryFixedDim = colorResource(R.color.theme_tertiary_fixed_dim),
        onTertiaryFixed = colorResource(R.color.theme_on_tertiary_fixed),
        onTertiaryFixedVariant = colorResource(R.color.theme_on_tertiary_fixed_variant),
    )
