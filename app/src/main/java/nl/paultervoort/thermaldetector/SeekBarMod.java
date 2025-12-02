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

import android.content.Context;
import android.util.AttributeSet;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;

/**
 * Seekbar modification to allow inverted value range and also provide progress change handler.
 */
public class SeekBarMod extends AppCompatSeekBar implements SeekBar.OnSeekBarChangeListener {
    @FunctionalInterface
    public interface ProgressChangedHandler {
        void accept(SeekBar seekBar, int progress, boolean fromUser);
    }

    private final static int HALF_ROTATION_DEGREES = 180;

    // Callbacks
    private OnSeekBarChangeListener listener = null;
    private ProgressChangedHandler progressHandler = null;

    // To avoid calculating this often
    private boolean inverted = false;
    private int inversionConstant = 0;

    //region Inherited constructors

    public SeekBarMod(@NonNull Context context) {
        super(context);
    }

    public SeekBarMod(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public SeekBarMod(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    //endregion

    /**
     * Assign a handler for the ProgressChanged event.
     * @param handler A ProgressChanged handler
     */
    public void setProgressChangedHandler(ProgressChangedHandler handler) {
        super.setOnSeekBarChangeListener(this);
        this.progressHandler = handler;
    }

    //region Allow the seekbar to have a maximum value smaller than the minimum value

    private void setInverted(int min, int max) {
        // Using THIS.getRotationY() for both is important because it obtains the real intended rotation
        if (min <= max) {
            super.setRotationY(this.getRotationY());
            this.inverted = false;
        } else {
            super.setRotationY(this.getRotationY() + HALF_ROTATION_DEGREES);
            this.inverted = true;
            this.inversionConstant = min + max;
        }
    }

    @Override
    public synchronized void setMin(int min) {
        // Update whether the new min value requires inversion
        int max = this.inverted ? super.getMin() : super.getMax();
        setInverted(min, max);

        // If inverted swap the underlying Min and Max
        if (this.inverted) {
            super.setMin(max);
            super.setMax(min);
        } else {
            super.setMin(min);
            super.setMax(max);
        }
    }

    @Override
    public synchronized void setMax(int max) {
        // Update whether the new max value requires inversion
        int min = this.inverted ? super.getMax() : super.getMin();
        setInverted(min, max);

        // If inverted swap the underlying Min and Max
        if (this.inverted) {
            super.setMax(min);
            super.setMin(max);
        } else {
            super.setMax(max);
            super.setMin(min);
        }
    }

    @Override
    public synchronized int getProgress() {
        if (this.inverted) {
            // Compensate for inversion
            return this.inversionConstant - super.getProgress();
        } else {
            return super.getProgress();
        }
    }

    @Override
    public void setProgress(int progress, boolean animate) {
        if (this.inverted) {
            // Compensate for inversion
            super.setProgress(this.inversionConstant - progress, animate);
        } else {
            super.setProgress(progress, animate);
        }
    }

    @Override
    public synchronized void setProgress(int progress) {
        this.setProgress(progress, false);
    }

    //endregion

    //region Correct for RotationY because it is used to mirror the SeekBar when inverted

    @Override
    public float getRotationY() {
        if (this.inverted) {
            return super.getRotationY() - HALF_ROTATION_DEGREES;
        } else {
            return super.getRotationY();
        }
    }

    @Override
    public void setRotationY(float rotationY) {
        if (this.inverted) {
            super.setRotationY(rotationY + HALF_ROTATION_DEGREES);
        } else {
            super.setRotationY(rotationY);
        }
    }

    //endregion

    //region Intercept the OnSeekBarChange event and introduce a callback specifically for onProgressChanged

    @Override
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener l) {
        super.setOnSeekBarChangeListener(this);
        this.listener = l;
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        // Apply inversion if needed
        if (this.inverted) {
            progress = this.inversionConstant - progress;
        }

        // Process standard OnSeekBarChangeListener if assigned
        if (this.listener != null) {
            this.listener.onProgressChanged(seekBar, progress, fromUser);
        }

        // Call custom handler if assigned
        if (this.progressHandler != null) {
            this.progressHandler.accept(seekBar, progress, fromUser);
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        if (this.listener != null) {
            this.listener.onStartTrackingTouch(seekBar);
        }
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (this.listener != null) {
            this.listener.onStopTrackingTouch(seekBar);
        }
    }

    //endregion
}
