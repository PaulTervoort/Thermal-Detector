/*
    This file is part of Thermal Detector: an Android app for detecting warm objects in dark
    environments using an integrated FLIR® camera.
    Copyright (C) 2025  Paul Tervoort

    Thermal Detector is free software: you can redistribute it and/or modify it under the terms of
    the GNU General Public License as published by the Free Software Foundation, either version 3 of
    the License, or (at your option) any later version.

    Thermal Detector is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
    the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with Thermal Detector.
    If not, see <https://www.gnu.org/licenses/>.
 */

package nl.paultervoort.thermaldetector;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;

import java.util.Locale;

/**
 * Helper to ensure a specific permission is granted, handling the required user interactions.
 */
class PermissionHelper {
    private final static int PERMISSION_TIMEOUT_MS = 30 * 1000; // 30s
    private final static String SETTINGS_TOAST_TEXT = "This app requires the %s permission to work";
    private final static String SCHEME_APP_INFO = "package";

    private final ComponentActivity activity;
    private final String permission;

    private final Toast permissionSettingsToast;
    private final Intent permissionSettingsIntent;

    private final Object waitLock;
    private final ActivityResultLauncher<String> permissionLauncher;
    private final ActivityResultLauncher<Intent> settingsLauncher;

    private boolean permissionGranted = false;

    /**
     * Constructor.
     * @param activity An activity on which event listeners and intents can be registered
     * @param permission The permission to be granted
     */
    public PermissionHelper(ComponentActivity activity, String permission) {
        this.activity = activity;
        this.permission = permission;

        // Construct the Toast that explains the user that this permission is required
        final String settingsToastText = String.format(SETTINGS_TOAST_TEXT, getPermissionName(permission));
        this.permissionSettingsToast = Toast.makeText(activity, settingsToastText, Toast.LENGTH_LONG);

        // construct the Intent that launches the App Info settings page
        final Uri uri = Uri.fromParts(SCHEME_APP_INFO, activity.getPackageName(), null);
        this.permissionSettingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri);

        // Register launchers for the permission dialog and settings page
        this.waitLock = new Object();
        this.permissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::permissionRequestCallback
        );
        this.settingsLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::settingsRequestCallback
        );
    }

    /**
     * Synchronously try different approaches to have the permission granted.
     * @return True when the permission was successfully granted, false on repeated user denial.
     */
    public boolean ensurePermission() {
        // Return if the permission is already granted
        if (ActivityCompat.checkSelfPermission(this.activity, this.permission) == PERMISSION_GRANTED) {
            return true;
        }

        // Try to obtain the permission
        try {
            synchronized (waitLock) {
                // Launch the permission dialog
                this.permissionGranted = false;
                this.permissionLauncher.launch(permission);

                // Wait for the dialog to close, return if granted
                this.waitLock.wait(PERMISSION_TIMEOUT_MS);
                if (this.permissionGranted) {
                    return true;
                }

                // Show an explaining toast and start settings page
                this.activity.runOnUiThread(this.permissionSettingsToast::show);
                this.settingsLauncher.launch(this.permissionSettingsIntent);

                // Wait for the settings page to close, then return whether it was granted
                this.waitLock.wait(PERMISSION_TIMEOUT_MS);
                return this.permissionGranted;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String getPermissionName(final String permission) {
        // Take the last part of the permission and capitalize it properly
        final String[] permissionParts = permission.split("\\.");
        final String name = permissionParts[permissionParts.length - 1].toLowerCase(Locale.ROOT);
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private void permissionRequestCallback(boolean permissionGranted) {
        // Update the granted state and release the lock
        synchronized (waitLock) {
            this.permissionGranted = permissionGranted;
            waitLock.notifyAll();
        }
    }

    private void settingsRequestCallback(ActivityResult ignored) {
        // Update the granted state and release the lock
        synchronized (waitLock) {
            int status = ActivityCompat.checkSelfPermission(this.activity, this.permission);
            this.permissionGranted = status == PERMISSION_GRANTED;
            this.waitLock.notifyAll();
        }
    }
}
