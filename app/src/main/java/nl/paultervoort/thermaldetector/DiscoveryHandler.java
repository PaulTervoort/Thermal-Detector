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

import androidx.core.util.Consumer;

import com.flir.thermalsdk.ErrorCode;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.discovery.DiscoveredCamera;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.live.discovery.DiscoveryFactory;
import com.flir.thermalsdk.log.ThermalLog;

/**
 * Handler for thermal camera discovery events.
 */
class DiscoveryHandler implements DiscoveryEventListener {
    public final static CommunicationInterface INTEGRATED_LEPTON = CommunicationInterface.INTEGRATED;

    private final static String TAG = DiscoveryHandler.class.getSimpleName();

    private final Consumer<Identity> callback;

    /**
     * Constructor.
     * @param callback A function to call when a camera identity is found (null on error)
     */
    public DiscoveryHandler(Consumer<Identity> callback) {
        this.callback = callback;
    }

    @Override
    public void onCameraFound(DiscoveredCamera discoveredCamera) {
        // Extract the discovered camera identity, abort if it is 'null'
        Identity foundIdentity = discoveredCamera.getIdentity();
        if (foundIdentity == null) {
            ThermalLog.d(TAG, "Found null device");
            return;
        }

        // Stop discovering
        DiscoveryFactory.getInstance().stop(INTEGRATED_LEPTON);

        // Give the device that was found to the consumer
        ThermalLog.d(TAG, "Found device: " + discoveredCamera.getIdentity().deviceId);
        feedConsumer(foundIdentity);
    }

    @Override
    public void onDiscoveryError(CommunicationInterface
    communicationInterface, ErrorCode error) {
        ThermalLog.e(TAG, "Error during discovery: " + error);

        // Indicate an error by giving 'null' to the consumer
        ThermalLog.d(TAG, "Restarting discovery");
        feedConsumer(null);
    }

    private void feedConsumer(Identity identity) {
        // The callback might throw an exception, but that is not relevant for this handler
        try {
            callback.accept(identity);
        } catch (Exception e) {
            ThermalLog.d(TAG, "Consumer threw an exception: " + e);
        }
    }
}
