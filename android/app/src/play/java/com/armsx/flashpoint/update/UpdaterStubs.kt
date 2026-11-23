package com.armsx.flashpoint.update

import androidx.compose.runtime.Composable

/**
 * Empty stand-ins for the in-app updater, for the Play build (as in ARMSX2).
 *
 * Google Play forbids an app that updates itself from outside the store, and it rejects the
 * REQUEST_INSTALL_PACKAGES permission being in the bundle, not merely code that uses it. So the
 * real updater, the permission and its FileProvider live in src/github, and these keep the play
 * source set compiling. Both call sites are behind BuildConfig.IN_APP_UPDATER, false here.
 */
@Composable
fun UpdaterRows() = Unit

@Composable
fun AutoUpdateGate() = Unit
