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

import static android.content.Context.SENSOR_SERVICE;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.core.util.Consumer;

/**
 * Helper to obtain the current screen rotation independent of activity rotation. Only 0, 90, 180, 270 degrees.
 */
public class RotationHelper implements SensorEventListener {
    private final static float MARGIN = 0.1f; // between 0 and 1
    private final static int SAMPLING_PERIOD_US = 100 * 1000; // 100ms

    // Constants for interpreting the state
    private final static int FLAG_UP = 1;
    private final static int FLAG_LEFT = 1 << 1;
    private final static int FLAG_VERTICAL = 1 << 2;

    // Rotation state
    private int state = FLAG_UP | FLAG_VERTICAL;
    private int rotation = 0;

    private final Consumer<Integer> callback;
    private final Sensor sensor;

    /**
     * Constructor.
     * @param activity An activity from which system services can be queried
     * @param callback A callback for when the rotation has changed
     */
    public RotationHelper(Activity activity, Consumer<Integer> callback) {
        this.callback = callback;

        // Obtain a rotation sensor
        SensorManager sensorManager = (SensorManager)activity.getSystemService(SENSOR_SERVICE);
        this.sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        // Register this as listener for the sensor
        sensorManager.registerListener(this, this.sensor, SAMPLING_PERIOD_US);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor == this.sensor) {
            // Read sensor values
            float[] rotationMatrix = new float[9];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            float pitch = rotationMatrix[7]; // y(out of screen)-component of x(top)-axis vector
            float roll = rotationMatrix[6]; // y(out of screen)-component of z(left)-axis vector

            // Flip directions only after some margin to prevent oscillation
            if (pitch > MARGIN) {
                this.state |= FLAG_UP;
            } else if (pitch < -MARGIN) {
                this.state &= ~FLAG_UP;
            }
            if (roll > MARGIN) {
                this.state |= FLAG_LEFT;
            } else if (roll < -MARGIN) {
                this.state &= ~FLAG_LEFT;
            }

            // Set the dominant axis only after some margin to prevent oscillation
            float diff = Math.abs(pitch) - Math.abs(roll);
            if (diff > MARGIN) {
                this.state |= FLAG_VERTICAL;
            } else if (diff < -MARGIN) {
                this.state &= ~FLAG_VERTICAL;
            }

            // Get rotation for state
            int newRotation;
            switch (this.state) {
                case 0:
                case FLAG_UP:
                    // Horizontal, Right side (Not vertical, Not left, Ignore up/down)
                    newRotation = 270;
                    break;
                case FLAG_VERTICAL:
                case FLAG_VERTICAL | FLAG_LEFT:
                    // Vertical, Down side (Vertical, Not up, Ignore left/right)
                    newRotation = 180;
                    break;
                case FLAG_LEFT:
                case FLAG_LEFT | FLAG_UP:
                    // Horizontal, Left side (Not vertical, Left, Ignore up/down)
                    newRotation = 90;
                    break;
                case FLAG_VERTICAL | FLAG_UP:
                case FLAG_VERTICAL | FLAG_UP | FLAG_LEFT:
                default:
                    // Vertical, Up side (Vertical, Up, Ignore left/right)
                    newRotation = 0;
                    break;
            }

            // Only update if value changed
            if (this.rotation == newRotation) {
                return;
            }
            this.rotation = newRotation;

            // Execute callback if not null
            if (this.callback != null) {
                this.callback.accept(this.rotation);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        // Ignore, but required for interface
    }
}
