/*
    This file is part of Thermal Detector.

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

import static nl.paultervoort.thermaldetector.FLIRHelper.TEMP_MAX_C;
import static nl.paultervoort.thermaldetector.FLIRHelper.TEMP_MIN_C;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.flir.thermalsdk.androidsdk.ThermalSdkAndroid;
import com.flir.thermalsdk.log.ThermalLog;

/**
 * Thermal detector activity.
 */
public class MainActivity extends AppCompatActivity {
    // Attributes that determine the camera icon
    private final static int[] CAMERA_ICON_ENABLE = new int[] { -R.attr.camera_icon_disable };
    private final static int[] CAMERA_ICON_DISABLE = new int[] { R.attr.camera_icon_disable };

    // Constants for the rotation animation
    private final static int FULL_ROTATION_DEGREES = 360;
    private final static int HALF_ROTATION_DEGREES = 180;
    private final static int ROTATE_DURATION_MS = 200;

    // State for UI rotation
    private int lastRotation = 0;

    // Interface for thermal camera stream
    private FLIRManager flirManager;
    private ImageView previewImage;

    // UI elements
    private SeekBarMod minBar;
    private SeekBarMod maxBar;
    private TextView minText;
    private TextView maxText;
    private TextView statusText;
    private ImageButton buttonCalibrate;
    private ImageButton buttonEnableCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Use full screen
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Initialize FLIR
        ThermalSdkAndroid.init(getApplicationContext(), ThermalLog.LogLevel.DEBUG);
        this.flirManager = new FLIRManager(this::onFrameUpdated, this::onStatusUpdated, this);

        // Get rotation events without the activity rotating too
        new RotationHelper(this, this::handleRotation);

        // Get views
        this.previewImage = findViewById(R.id.cameraView);
        this.minBar = findViewById(R.id.seekBarMin);
        this.maxBar = findViewById(R.id.seekBarMax);
        this.minText = findViewById(R.id.textViewMin);
        this.maxText = findViewById(R.id.textViewMax);
        this.statusText = findViewById(R.id.textViewStatus);
        this.buttonCalibrate = findViewById(R.id.buttonCalibrate);
        this.buttonEnableCamera = findViewById(R.id.buttonEnableCamera);

        // Seekbar limits
        this.minBar.setMin(TEMP_MIN_C);
        this.minBar.setMax(TEMP_MAX_C - 1);
        this.maxBar.setMin(TEMP_MAX_C);
        this.maxBar.setMax(TEMP_MIN_C + 1);

        // Seekbar initial values
        int initialMinTemp = (int) (flirManager.getMinTemp() + 0.5); // Rounding
        int initialMaxTemp = (int) (flirManager.getMaxTemp() + 0.5); // Rounding
        this.minBar.setProgress(initialMinTemp);
        this.maxBar.setProgress(initialMaxTemp);
        this.minText.setText(String.valueOf(initialMinTemp));
        this.maxText.setText(String.valueOf(initialMaxTemp));

        // Seekbar event handlers
        this.minBar.setProgressChangedHandler(this::handleProgressChangedMin);
        this.maxBar.setProgressChangedHandler(this::handleProgressChangedMax);

        // Disable the UI elements until the first FLIR state change
        updateUI(R.string.empty, 0xFF888888, false, false, CAMERA_ICON_ENABLE);
    }

    @Override
    protected void onPause() {
        super.onPause();

        this.flirManager.pauseStream();
    }

    @Override
    protected void onResume() {
        super.onResume();

        this.flirManager.startCamera();
    }

    /**
     * Callback for the FLIRManager to notify a new frame is ready to be displayed.
     */
    public void onFrameUpdated() {
        // Render the new camera frame
        runOnUiThread(() -> {
            // Get the latest thermal image if ready, otherwise return
            final Bitmap bitmap = this.flirManager.getMostRecentBitmap();
            if (bitmap == null) {
                return;
            }

            // Assign the bitmap to the preview ImageView
            if (this.previewImage != null) {
                this.previewImage.setImageBitmap(bitmap);
            }
        });
    }

    /**
     * Callback for the FLIRManager to notify the camera state has changed.
     * @param state The new state of the camera
     */
    public void onStatusUpdated(StateManager.State state) {
        // For each state, define and apply the parameters for the UI
        switch (state) {
            case NO_PERMISSION:
                updateUI(R.string.state_no_permission, 0xFFFF0000, false, false, CAMERA_ICON_ENABLE);
                break;
            case COMPROMISED:
                updateUI(R.string.state_compromised, 0xFFFF0000, false, false, CAMERA_ICON_ENABLE);
                break;
            case IDLE:
                updateUI(R.string.state_idle, 0xFF666666, false, true, CAMERA_ICON_ENABLE);
                break;
            case DISCOVERING:
                updateUI(R.string.state_discovering, 0xFFBB8800, false, false, CAMERA_ICON_ENABLE);
                break;
            case DEVICE_FOUND:
                updateUI(R.string.state_device_found, 0xFFBBAA00, false, false, CAMERA_ICON_ENABLE);
                break;
            case CONNECTING:
            case CONNECTING_PAUSED:
                updateUI(R.string.state_connecting, 0xFFBBAA00, false, false, CAMERA_ICON_ENABLE);
                break;
            case CONNECTED:
                updateUI(R.string.state_connected, 0xFF77BB00, true, false, CAMERA_ICON_DISABLE);
                break;
            case STAND_BY:
                updateUI(R.string.state_stand_by, 0xFF77BB00, true, false, CAMERA_ICON_DISABLE);
                break;
            case STARTING_STREAM:
                updateUI(R.string.state_starting_stream, 0xFF77BB00, true, false, CAMERA_ICON_DISABLE);
                break;
            case STREAMING:
                updateUI(R.string.state_streaming, 0xFF00BB00, true, true, CAMERA_ICON_DISABLE);
                break;
            case START_WITH_CALI:
            case NEED_CALIBRATE:
                updateUI(R.string.state_need_calibrate, 0xFFBB00BB, true, true, CAMERA_ICON_DISABLE);
                break;
            case CALIBRATING:
                updateUI(R.string.state_calibrating, 0xFF0000FF, false, true, CAMERA_ICON_DISABLE);
                break;
            case CLOBBERED:
                updateUI(R.string.state_clobbered, 0xFF995555, false, false, CAMERA_ICON_ENABLE);
                break;
            default:
                // If new states added without updating this switch statement, show unknown state
                updateUI(R.string.state_unknown, 0xFF888888, false, false, CAMERA_ICON_ENABLE);
                break;
        }
    }

    /**
     * Action for the calibrate button.
     * @param ignoredView Required for button click listener
     */
    public void calibrate_click(View ignoredView) {
        this.flirManager.ffcCalibration();
    }

    /**
     * Action for the start/stop button.
     * @param button The drawable state of the button determines start or stop action
     */
    public void startStop_click(View button) {
        // The button icon state determines whether to connect or disconnect
        if (hasAttr(button.getDrawableState(), R.attr.camera_icon_disable)) {
            this.flirManager.disconnect();
        } else {
            this.flirManager.startCamera();
        }
    }

    private void handleProgressChangedMin(SeekBar ignoredSeekBar, int progress, boolean fromUser) {
        if (!fromUser) { return; }

        // Make sure that min < max
        int max = this.maxBar.getProgress();
        if (progress >= max) {
            progress = max - 1;
            this.minBar.setProgress(progress);
        }

        // Update label
        this.minText.setText(String.valueOf(progress));

        // Apply limit to the camera
        this.flirManager.setMinTemp(progress);
    }

    private void handleProgressChangedMax(SeekBar ignoredSeekBar, int progress, boolean fromUser) {
        if (!fromUser) { return; }

        // Make sure that max > min
        int min = this.minBar.getProgress();
        if (progress <= min) {
            progress = min + 1;
            this.maxBar.setProgress(progress);
        }

        // Update label
        this.maxText.setText(String.valueOf(progress));

        // Apply limit to the camera
        this.flirManager.setMaxTemp(progress);
    }

    private void handleRotation(int rotation) {
        // Do not animate if the same
        if (this.lastRotation == rotation) {
            return;
        }

        // Save the rotation for next time
        int startRotation = this.lastRotation;
        this.lastRotation = rotation;

        // Modify starting rotation to ensure shortest direction
        int rotationDiff = rotation - startRotation;
        if (rotationDiff > HALF_ROTATION_DEGREES) {
            startRotation += FULL_ROTATION_DEGREES;
        } else if (rotationDiff < -HALF_ROTATION_DEGREES) {
            startRotation -= FULL_ROTATION_DEGREES;
        }

        // Construct the animation for the seekbar numbers
        Animation textAnim = getRotateAnimation(startRotation, rotation);
        this.minText.startAnimation(textAnim);
        this.maxText.startAnimation(textAnim);

        // Construct the animation for the buttons (different size than for seekbar numbers)
        Animation buttonAnim = getRotateAnimation(startRotation, rotation);
        this.buttonCalibrate.startAnimation(buttonAnim);
        this.buttonEnableCamera.startAnimation(buttonAnim);
    }

    private static Animation getRotateAnimation(float from, float to) {
        Animation anim = new RotateAnimation(from, to,
                Animation.RELATIVE_TO_SELF, .5f, // Midpoint
                Animation.RELATIVE_TO_SELF, .5f // Midpoint
        );
        anim.setFillAfter(true); // Keep position after end
        anim.setDuration(ROTATE_DURATION_MS);
        return anim;
    }

    private void updateUI(int text, int color, boolean calEn, boolean camEn, int[] icon) {
        runOnUiThread(() -> {
            this.statusText.setText(text);
            this.statusText.setTextColor(color);
            this.buttonCalibrate.setEnabled(calEn);
            this.buttonEnableCamera.setEnabled(camEn);
            this.buttonEnableCamera.setImageState(icon, true);
        });
    }

    private boolean hasAttr(int[] attrs, int attr) {
        for (int a : attrs) {
            if (a == attr) {
                return true;
            }
        }
        return false;
    }
}
