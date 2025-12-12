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

import static android.Manifest.permission.CAMERA;
import static android.content.Context.CAMERA_SERVICE;

import android.graphics.Bitmap;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flir.thermalsdk.androidsdk.ThermalSdkAndroid;
import com.flir.thermalsdk.androidsdk.image.BitmapAndroid;
import com.flir.thermalsdk.image.ImageBuffer;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.discovery.DiscoveryFactory;
import com.flir.thermalsdk.live.remote.Calibration;
import com.flir.thermalsdk.live.remote.RemoteControl;
import com.flir.thermalsdk.live.streaming.Stream;
import com.flir.thermalsdk.live.streaming.ThermalStreamer;
import com.flir.thermalsdk.log.ThermalLog;

import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static nl.paultervoort.thermaldetector.DiscoveryHandler.INTEGRATED_LEPTON;
import static nl.paultervoort.thermaldetector.FLIRHelper.runWithTimeout;
import static nl.paultervoort.thermaldetector.FLIRHelper.selectGainMode;
import static nl.paultervoort.thermaldetector.FLIRHelper.setupRangeColors;
import static nl.paultervoort.thermaldetector.StateManager.State.CALIBRATING;
import static nl.paultervoort.thermaldetector.StateManager.State.CLOBBERED;
import static nl.paultervoort.thermaldetector.StateManager.State.CONNECTED;
import static nl.paultervoort.thermaldetector.StateManager.State.CONNECTING;
import static nl.paultervoort.thermaldetector.StateManager.State.CONNECTING_PAUSED;
import static nl.paultervoort.thermaldetector.StateManager.State.DEVICE_FOUND;
import static nl.paultervoort.thermaldetector.StateManager.State.DISCOVERING;
import static nl.paultervoort.thermaldetector.StateManager.State.IDLE;
import static nl.paultervoort.thermaldetector.StateManager.State.NEED_CALIBRATE;
import static nl.paultervoort.thermaldetector.StateManager.State.NOT_SUPPORTED;
import static nl.paultervoort.thermaldetector.StateManager.State.NO_PERMISSION;
import static nl.paultervoort.thermaldetector.StateManager.State.STARTING_STREAM;
import static nl.paultervoort.thermaldetector.StateManager.State.START_WITH_CALI;
import static nl.paultervoort.thermaldetector.StateManager.State.STREAMING;
import static nl.paultervoort.thermaldetector.StateManager.State.STAND_BY;
import static nl.paultervoort.thermaldetector.StateManager.State.COMPROMISED;

/**
 * An interface for the FLIR camera. Keeps track of the state of the thermal camera.
 */
class FLIRManager extends CameraManager.AvailabilityCallback implements AutoCloseable {
    // Logging tag
    private final static String TAG = FLIRManager.class.getSimpleName();

    // Relevant error code for discovery
    private final static int ERROR_CODE_INTERFACE_NOT_SUPPORTED = 1;

    // Colorizer settings
    private final static double INITIAL_LOW_TEMP_C = 10.0; // Celsius
    private final static double INITIAL_HIGH_TEMP_C = 20.0; // Celsius

    // Delays
    private final static int DISCOVER_RETRY_MS = 1000; // 1s
    private final static int CONNECT_TIMEOUT_MS = 10 * 1000; // 10s
    private final static int FRAME_TIMEOUT_MS = 10 * 1000; // 10s
    private final static int AUTO_CLOSE_DELAY_MS = 10 * 60 * 1000; // 10min

    // Handler for checks that must be performed after a time period
    private final Handler delayHandler = new Handler(Looper.getMainLooper());

    // The current temperature range
    private double minTemp = INITIAL_LOW_TEMP_C;
    private double maxTemp = INITIAL_HIGH_TEMP_C;
    private boolean thermalRangeNeedSetup = true;

    // Provided on construction
    private final StateManager stateManager;
    private final Runnable onBitmapReady;
    private final PermissionHelper permissionHelper;

    // The objects from different stages in the connection process
    private Camera camera = null;
    private Identity foundIdentity = null;
    private RemoteControl remoteControl = null;
    private Stream stream = null;
    private ThermalStreamer streamer = null;
    private Bitmap mostRecentBitmap = null;

    /**
     * Constructor.
     * @param onBitmapReady A callback for when a thermal camera frame is ready for display
     * @param onStatusChanged A callback for when the status of the thermal camera has changed
     * @param activity An activity on which event listeners can be registered
     */
    public FLIRManager(Runnable onBitmapReady, Consumer<StateManager.State> onStatusChanged, ComponentActivity activity) {
        // Interfaces for the UI
        this.onBitmapReady = onBitmapReady;
        this.permissionHelper = new PermissionHelper(activity, CAMERA);

        // Initialize the FLIR thermal SDK
        try {
            ThermalSdkAndroid.init(activity.getApplicationContext(), ThermalLog.LogLevel.INFO);
        }
        // If the SDK initialization throws any error or exception, very likely the device has no FLIR camera
        catch (Throwable e) {
            LogHelper.e(TAG, "Cannot load FLIR SDK. Exception: " + e);

            // Construct dummy state manager to allow the app to still run and inform the user
            this.stateManager = StateManager.getUnsupportedStateManager();
            return;
        }
        LogHelper.enableThermalLog();

        // Create the interface to the FLIR backend
        camera = new Camera();

        // Build the state manager behaviour in multiple steps
        StateManager.StateManagerBuilder builder = new StateManager.StateManagerBuilder(onStatusChanged);

        // Set state switch actions for before a state transition, for specific states
        builder.setPreAction(CLOBBERED, () -> {
            this.camera.close();
            this.camera = new Camera();
        });
        builder.setPreAction(STAND_BY, () -> this.delayHandler.removeCallbacksAndMessages(null));
        builder.setPreAction(STREAMING, () -> this.delayHandler.removeCallbacksAndMessages(null));

        // Set state switch actions for after a state transition, for specific states
        builder.setPostAction(NO_PERMISSION, FLIRManager.this::askPermission);
        builder.setPostAction(DEVICE_FOUND, FLIRManager.this::connect);
        builder.setPostAction(CONNECTED, FLIRManager.this::startStreaming);
        builder.setPostAction(STAND_BY, () -> this.delayHandler.postDelayed(() -> {
            LogHelper.i(TAG, "Disconnect because of long stand by");
            disconnect();
        }, AUTO_CLOSE_DELAY_MS));
        builder.setPostAction(STREAMING, () -> this.delayHandler.postDelayed(() -> {
            LogHelper.i(TAG, "Camera stream not providing frames");
            compromise();
        }, FRAME_TIMEOUT_MS));

        // Define the incremental cleanup action for different sets of states
        // level 0 implicit (needs no action): NO_PERMISSION, COMPROMISED, CLOBBERED, IDLE
        builder.setCleanupAction(1, false, this::cleanDiscovery,
                new StateManager.State[] { DISCOVERING, DEVICE_FOUND });
        builder.setCleanupAction(2, true, this::cleanConnection,
                new StateManager.State[] { CONNECTING, CONNECTING_PAUSED, CONNECTED, STAND_BY });
        builder.setCleanupAction(3, true, this::cleanStream,
                new StateManager.State[] { STARTING_STREAM, START_WITH_CALI, STREAMING, NEED_CALIBRATE, CALIBRATING });

        // Build the state manager
        this.stateManager = builder.build();

        // Register callback to detect when other apps use the camera, to prevent hard crashes
        CameraManager manager = (CameraManager) activity.getSystemService(CAMERA_SERVICE);
        manager.registerAvailabilityCallback(Executors.newSingleThreadExecutor(), this);
    }

    //region UI communication

    /**
     * Start the thermal camera asynchronously. The camera stream has started when state is STREAMING.
     */
    public void startCamera() {
        switch (this.stateManager.getState()) {
            case CLOBBERED:
                // Try to recover from clobber state
                LogHelper.i(TAG, "Recovering from external camera clobber");
                this.stateManager.setState(IDLE);
                // State is now IDLE, so no break yet
            case IDLE:
                // Trigger camera stream setup when not connected
                this.stateManager.runOnStateThread(this::startDiscovering);
                break;
            case CONNECTING_PAUSED:
                // Allow streaming after the camera is connected
                this.stateManager.setState(CONNECTING);
                break;
            case STAND_BY:
                // Start the camera stream if already connected but on standby
                this.stateManager.setState(CONNECTED);
                break;
            default:
                // In other states the camera is unavailable or already starting
                break;
        }
    }

    /**
     * Stop the thermal stream asynchronously if it is active. If the camera is connected it will enter stand by.
     */
    public void pauseStream() {
        switch (this.stateManager.getState()) {
            case DISCOVERING:
            case DEVICE_FOUND:
                // Currently in the process of connecting, abort this
                LogHelper.i(TAG, "Connect aborted by pause");
                this.stateManager.setState(IDLE);
                break;
            case CONNECTING:
                // Do not start streaming after the camera is connected
                LogHelper.i(TAG, "Pause after connecting");
                this.stateManager.setState(CONNECTING_PAUSED);
                break;
            case CONNECTED:
            case STARTING_STREAM:
            case START_WITH_CALI:
            case STREAMING:
            case NEED_CALIBRATE:
            case CALIBRATING:
                // Camera is connected, so keep it connected but on standby
                LogHelper.i(TAG, "Entering stand by");
                this.stateManager.setState(STAND_BY);
                break;
            default:
                // In other states the camera is already not doing anything
                break;
        }
    }

    /**
     * Completely disconnect from the thermal camera asynchronously, releasing all its resources.
     */
    public void disconnect() {
        // In IDLE state there is no connection to the camera
        this.stateManager.setState(IDLE);
    }

    /**
     * Request a Flat Field Correction calibration of the thermal camera asynchronously.
     */
    public void ffcCalibration() {
        LogHelper.i(TAG, "User calibration request");

        // force a flat field correction (non-uniformity correction) to happen
        Calibration calibration;
        if (this.remoteControl == null || (calibration = this.remoteControl.getCalibration()) == null) {
            return;
        }

        // Only trigger calibration requests from the user when camera is treaming
        if (this.stateManager.isState(STREAMING)) {
            this.stateManager.runOnStateThread(() -> calibration.nuc().executeSync());
        }
    }

    /**
     * Get the current state of the thermal camera.
     * @return The current state
     */
    public StateManager.State getCameraState() {
        return this.stateManager.getState();
    }

    /**
     * Get the most recent thermal camera frame in bitmap format.
     * @return The most recent camera frame
     */
    public @Nullable Bitmap getMostRecentBitmap() {
        if (mostRecentBitmap == null) {
            LogHelper.i(TAG, "Providing 'null' bitmap");
        }

        return mostRecentBitmap;
    }

    /**
     * Getter.
     * @return The minimum temperature for which to color the thermal image
     */
    public double getMinTemp() {
        return this.minTemp;
    }

    /**
     * Set the minimum temperature for which to color the thermal image. Must be greater than getMaxTemp().
     */
    public void setMinTemp(double minTemp) {
        // Prevent a reversed or zero range
        if (this.maxTemp - minTemp < 0.5) {
            return;
        }

        // Set the value and trigger range update
        this.minTemp = minTemp;
        this.thermalRangeNeedSetup = true;
    }

    /**
     * Getter.
     * @return The maximum temperature for which to color the thermal image
     */
    public double getMaxTemp() {
        return this.maxTemp;
    }

    /**
     * Set the maximum temperature for which to color the thermal image. Must be greater than getMinTemp().
     */
    public void setMaxTemp(double maxTemp) {
        // Prevent a reversed or zero range
        if (maxTemp - this.minTemp < 0.5) {
            return;
        }

        // Set the value and trigger range update
        this.maxTemp = maxTemp;
        this.thermalRangeNeedSetup = true;
    }

    //endregion

    //region Connection state actions

    private void compromise() {
        this.stateManager.setState(COMPROMISED);
    }

    private void askPermission() {
        if (this.permissionHelper.ensurePermission()) {
            // Make sure the state is IDLE, then trigger the camera setup process
            this.stateManager.setState(IDLE);
            this.stateManager.runOnStateThread(this::startDiscovering);
        } else {
            LogHelper.e(TAG, "Cannot get camera permission");

            // If not able to transition to state NO_PERMISSION, try asking again
            if (!this.stateManager.setState(NO_PERMISSION)) {
                askPermission();
            }
        }
    }

    private void startDiscovering() {
        // Only start discovering if able to transition state to DISCOVERING
        if (!this.stateManager.setState(DISCOVERING)) {
            return;
        }

        // If a valid camera identity is already stored, skip discovering and use the stored camera
        if (this.foundIdentity != null) {
            this.stateManager.setState(DEVICE_FOUND);
            return;
        }

        // Start trying to discover an integrated Lepton camera
        DiscoveryFactory.getInstance().scan(
                new DiscoveryHandler(this::onIdentityDiscover, this::onDiscoverError),
                INTEGRATED_LEPTON
        );
    }

    private void connect() {
        // Only start connecting if able to transition state to CONNECTING
        if (!this.stateManager.setState(CONNECTING)) {
            return;
        }

        // Make sure that the Camera permission is granted (blocking)
        if (!this.permissionHelper.ensurePermission()) {
            this.stateManager.setState(NO_PERMISSION);
            return;
        }

        // Try to connect to the camera: Synchronized to prevent disconnecting simultaneously
        synchronized (this.stateManager) {
            // Connect to camera: when 'success' is false, connection failed or timeout
            boolean success = runWithTimeout(() -> {
                try {
                    this.camera.connect(this.foundIdentity, ignored -> {}, null);
                    this.remoteControl = this.camera.getRemoteControl();
                }
                catch (Exception ignored) { }
            }, FLIRManager.CONNECT_TIMEOUT_MS);

            if (!success) {
                LogHelper.e(TAG, "Camera backend not working, usually fixed by phone restart");
                this.stateManager.setState(COMPROMISED);
                return;
            }
        }

        // Get the calibration interface for the connected camera
        Calibration calibration;
        if ((this.remoteControl == null || (calibration = this.remoteControl.getCalibration()) == null)) {
            LogHelper.e(TAG, "Connecting to camera went wrong");
            this.stateManager.setState(DEVICE_FOUND);
            return;
        }

        // Apply the desired calibration settings
        selectGainMode(this.remoteControl);                         // Apply the most suitable gain mode
        calibration.nucState().subscribe(this::onNUCStateChange);   // Subscribe to the calibration state change event
        calibration.nucInterval().setSync(Integer.MAX_VALUE);       // Disable automatic calibration (except for on initial calibration)

        // Indicate that the camera is connected
        synchronized (this.stateManager) {
            if (this.stateManager.isState(CONNECTING_PAUSED)) {
                this.stateManager.setState(STAND_BY);
            } else {
                this.stateManager.setState(CONNECTED);
            }
        }
    }

    private void startStreaming() {
        // Only start streaming if able to transition state to STARTING_STREAM
        if (!this.stateManager.setState(STARTING_STREAM)) {
            return;
        }

        // Make sure the camera is connected
        if (!this.camera.isConnected()) {
            LogHelper.i(TAG, "Streaming expected camera to be connected, but it was not");
            this.stateManager.setState(DEVICE_FOUND);
            return;
        }

        // Make sure the thermal camera stream is not already in use
        this.stream = this.camera.getStreams().get(0);
        if (this.stream.isStreaming()) {
            LogHelper.i(TAG, "Stream was in use before starting it");
            this.stateManager.setState(CONNECTED);
            return;
        }

        // Apply desired streaming settings
        this.streamer = new ThermalStreamer(this.stream);
        this.streamer.withThermalImage(FLIRHelper::initThermalImage);
        this.streamer.setAutoScale(false);
        this.thermalRangeNeedSetup = true;

        // Block state changes while starting the stream
        synchronized (this.stateManager) {
            this.stream.start(arg -> this.stateManager.runOnStateThread(this::processFrame), error -> {
                LogHelper.e(TAG, "Error starting stream: " + error);
                this.stateManager.setState(CONNECTED);
            });

            // Notify done with starting stream, possibly while already calibrating
            if (this.stateManager.isState(START_WITH_CALI)) {
                this.stateManager.setState(CALIBRATING);
            } else {
                this.stateManager.setState(STREAMING);
            }
        }
    }

    private void processFrame() {
        // Full block must be synchronized to prevent disconnect during update
        synchronized (this.stateManager) {
            // Verify if the frame should be processed
            StateManager.State state = this.stateManager.getState();
            if (state != STREAMING && state != NEED_CALIBRATE && state != CALIBRATING) {
                LogHelper.i(TAG, "Ignoring camera frame because state is: " + state);
                return;
            } else if (!this.camera.isConnected()) {
                LogHelper.i(TAG, "Streaming expected camera to be connected, but it was not");
                this.stateManager.setState(DEVICE_FOUND);
                return;
            }

            // Grab the latest frame from the stream and render it
            this.streamer.update();

            // Setup the mapping between temperature and image color (must be after streamer.update())
            if (this.thermalRangeNeedSetup) {
                this.streamer.withThermalImage(img -> setupRangeColors(img, this.minTemp, this.maxTemp));
                this.thermalRangeNeedSetup = false;

                // A frame has been received, so camera is not compromised
                this.delayHandler.removeCallbacksAndMessages(null);
            }

            // Save the rendered frame as Android bitmap
            ImageBuffer renderedImage = this.streamer.getImage();
            this.mostRecentBitmap = BitmapAndroid.createBitmap(renderedImage).getBitMap();

            // Notify the UI to display most recent image
            this.onBitmapReady.run();
        }
    }

    private void cleanStream() {
        if (this.stream != null && this.stream.isStreaming()) {
            this.stream.stop();
        }
        this.stream = null;
        this.streamer = null;
    }

    private void cleanConnection() {
        if (this.camera.isConnected()) {
            this.camera.disconnect();
        }
        this.remoteControl = null;
    }

    private void cleanDiscovery() {
        DiscoveryFactory.getInstance().stop(INTEGRATED_LEPTON);
    }

    //endregion

    //region Event handlers

    private void onIdentityDiscover(Identity identity) {
        synchronized (this.stateManager) {
            // If not discovering, ignore this identity
            if (!this.stateManager.isState(DISCOVERING)) {
                return;
            }

            // If valid identity, save and update state
            else if (identity != null) {
                this.foundIdentity = identity;
                this.stateManager.setState(DEVICE_FOUND);
                return;
            }
        }

        // An error occurred during discovering
        LogHelper.e(TAG, "Camera discovery went wrong");
        try {
            // Try to stop discovering (if not already stopped)
            this.stateManager.setState(IDLE);

            // wait one second before retry
            Thread.sleep(DISCOVER_RETRY_MS);
        }
        catch (InterruptedException ignored) { }
        finally {
            // Make sure to restart discovering
            startDiscovering();
        }
    }

    private void onDiscoverError(int errorCode) {
        // If the integrated camera interface is not supported, the device is not supported
        if (errorCode == ERROR_CODE_INTERFACE_NOT_SUPPORTED) {
            this.stateManager.setState(NOT_SUPPORTED);
        }
    }

    private void onNUCStateChange(Calibration.NucState nucState) {
        LogHelper.i(TAG, "NUC State: " + nucState);

        // Synchronized to keep the state valid
        synchronized (this.stateManager) {
            StateManager.State state = this.stateManager.getState();
            boolean isStarting = state == STARTING_STREAM || state == START_WITH_CALI;

            // If the state is CONNECTED, this callback is already registered but relevant
            if (state == CONNECTED) {
                LogHelper.i(TAG, "Calibration before starting stream");
                return;
            }

            // Update the FLIR state based on the new NUC state
            switch (nucState) {
                case UNKNOWN: case INVALID: case BAD: case DESIRED:
                    this.stateManager.setState(isStarting ? START_WITH_CALI : NEED_CALIBRATE);
                    break;
                case PROGRESS: case RAD_APPROX: case VALID_IMG:
                    this.stateManager.setState(isStarting ? START_WITH_CALI : CALIBRATING);
                    break;
                case VALID_RAD:
                    this.stateManager.setState(isStarting ? STARTING_STREAM : STREAMING);
                    this.thermalRangeNeedSetup = true;
                    break;
                default:
                    // Should never happen, unless the FLIR SDK changes
                    LogHelper.e(TAG, "Unexpected NUC state, switch statement incomplete");
                    break;
            }
        }
    }

    @Override
    public void close() {
        // In idle state all resources will be released except for the camera object
        this.stateManager.setState(IDLE);

        // The side-effect of state change is asynchronous, so wait for it to be really disconnected before closing
        while (this.camera.isConnected()) {
            LogHelper.i(TAG, "Closing - waiting for camera to disconnect ...");
        }
        camera.close();
    }

    @Override
    public void onCameraUnavailable(@NonNull String cameraId) {
        if (stateManager.isState(STARTING_STREAM) || stateManager.isState(START_WITH_CALI)) {
            // If currently starting a stream, it is expected that a camera becomes unavailable
            LogHelper.i(TAG, "Intended camera access");
        } else {
            // A camera is used externally while also used here, so mark camera clobbered
            LogHelper.i(TAG, "External camera access");
            stateManager.setState(CLOBBERED);
        }

        super.onCameraUnavailable(cameraId);
    }

    //endregion
}
