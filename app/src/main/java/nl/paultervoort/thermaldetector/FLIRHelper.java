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

import static com.flir.thermalsdk.image.TemperatureUnit.CELSIUS;

import com.flir.thermalsdk.image.PaletteManager;
import com.flir.thermalsdk.image.TemperatureLinearSettings;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.image.ThermalValue;
import com.flir.thermalsdk.image.fusion.Fusion;
import com.flir.thermalsdk.image.fusion.FusionMode;
import com.flir.thermalsdk.live.remote.RemoteControl;
import com.flir.thermalsdk.live.remote.TemperatureRange;
import com.flir.thermalsdk.utils.Pair;

import java.util.List;

/**
 * Some static stateless helper functions for FLIRManager.
 */
class FLIRHelper {
    // The maximum temperature range for the intended use case
    public final static int TEMP_MIN_C = -5; // Celsius
    public final static int TEMP_MAX_C = 45; // Celsius

    // The classic thermal camera color scheme
    private final static String THERMAL_COLOR_SCHEME = "iron";

    /**
     * Run the runnable, and force stop if exceeding the timeout.
     * @param runnable The runnable to execute
     * @param timeout The time limit in which the runnable should finish
     * @return Whether the runnable finished before the timeout
     */
    public static boolean runWithTimeout(Runnable runnable, long timeout) {
        // Run the runnable in a new thread
        Thread t = new Thread(runnable);
        t.start();

        // Wait for the thread to finish with a maximum of timeOut milliseconds
        try {
            t.join(timeout);
        } catch (InterruptedException ignored) {
            // Something went wrong, assume no success
            return false;
        }

        // Return if the runnable finished in time
        if (!t.isAlive()) {
            return true;
        }

        // Not finished in time, try to interrupt the thread
        t.interrupt();
        return false;
    }

    /**
     * Select the optimal gain mode based on constants TEMP_MIN_C and TEMP_MAX_C.
     * @param control The camera control on which to select the gain mode
     */
    public static void selectGainMode(RemoteControl control) {
        // Stop if the camera has no ranges available
        final TemperatureRange temperatureRangeList;
        if (control == null || (temperatureRangeList = control.getTemperatureRange()) == null) {
            return;
        }

        // If only 0 or 1 range is available there is nothing to select between
        final List<Pair<ThermalValue, ThermalValue>> ranges = temperatureRangeList.ranges().getSync();
        if (ranges.size() <= 1) {
            return;
        }

        // Get the index of the best matching range and select the corresponding gain mode
        int selectedIndex = RangeMatchHelper.matchRange(ranges, TEMP_MIN_C, TEMP_MAX_C);
        temperatureRangeList.selectedIndex().setSync(selectedIndex);
    }

    /**
     * Apply a thermal range coloring based on minimum and maximum temperature limits.
     * @param image The thermal image settings object on which to apply the coloring
     * @param tempMin The minimum temperature to color
     * @param tempMax The maximum temperature to color
     */
    public static void setupRangeColors(ThermalImage image, double tempMin, double tempMax) {
        image.getScale().setRange(new ThermalValue(tempMin, CELSIUS), new ThermalValue(tempMax, CELSIUS));
        image.setColorDistributionSettings(new TemperatureLinearSettings());
    }

    /**
     * Initialize thermal image settings. Apply the color palette and thermal/visible fusion mode.
     * @param image The thermal image settings object to initialize
     */
    public static void initThermalImage(ThermalImage image) {
        image.setPalette(PaletteManager.getDefaultPalettes().stream()
                .filter(palette -> palette.name.equalsIgnoreCase(THERMAL_COLOR_SCHEME))
                .findFirst().orElseGet(() -> PaletteManager.getDefaultPalettes().get(0)));
        Fusion fusion;
        if ((fusion = image.getFusion()) != null) {
            fusion.setFusionMode(FusionMode.MSX); // MSX adds visual edges to the thermal image
        }
    }
}
