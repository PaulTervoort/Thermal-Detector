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

import com.flir.thermalsdk.image.ThermalValue;
import com.flir.thermalsdk.utils.Pair;

import java.util.List;

/**
 * Some static stateless helper function for selecting the optimal temperature range.
 */
public final class RangeMatchHelper {
    /**
     * Select the best gain mode for the target range.
     * @param ranges The ranges from which to choose
     * @param min The target minimum temperature
     * @param max The target maximum temperature
     * @return The index of the selected range within 'ranges'
     */
    public static int matchRange(final List<Pair<ThermalValue, ThermalValue>> ranges, int min, int max) {
        // If only 0 or 1 range is available there is nothing to select between
        final int count = ranges.size();
        if (count <= 1) {
            return 0;
        }

        // Check which ranges have the most complete coverage of the target range
        final int[] coverages = new int[count];
        int selectedIndex = 0;
        int maxCoverage = 0;
        int maxCoverageCount = 0;
        for (int i = 0; i < count; i++) {
            // Calculate coverage
            final int coverage = getRangeCoverage(ranges.get(i), min, max);
            coverages[i] = coverage;

            // If higher coverage than best so far, set current range as best coverage
            if (coverage > maxCoverage) {
                maxCoverage = coverage;
                selectedIndex = i;
                maxCoverageCount = 1;
            }
            // If equal coverage, add current range to the set of ranges with best coverage
            else if (coverage == maxCoverage) {
                maxCoverageCount += 1;
            }
        }

        // If only one range covers the full target range (or more than all others), select it
        if (maxCoverageCount == 1) {
            return selectedIndex;
        }

        // Select the range that is most narrow and centered compared to the target range
        selectedIndex = 0;
        int maxPrecision = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            // Skip ranges that have a low coverage
            if (coverages[i] != maxCoverage) {
                continue;
            }

            // If higher precision (lower value) than best so far, set current range as best precision
            int precision = getRangePrecision(ranges.get(i), min, max);
            if (precision < maxPrecision) {
                maxPrecision = precision;
                selectedIndex = i;
            }
        }

        // Select range with best precision
        return selectedIndex;
    }

    private static int getRangeCoverage(final Pair<ThermalValue, ThermalValue> range, int min, int max) {
        // Assume full coverage
        final int range_min = (int) range.first.asCelsius().value;
        final int range_max = (int) range.second.asCelsius().value;
        int coverage = max - min;

        // Subtract possible low-end part not covered
        if (range_min > min) {
            // Stop if completely not covered
            if (range_min > max) { return 0; }

            coverage -= range_min - min;
        }

        // Subtract possible high-end part not covered
        if (range_max < max) {
            // Stop if completely not covered
            if (range_max < min) { return 0; }

            coverage -= max - range_max;
        }

        return coverage;
    }

    private static int getRangePrecision(final Pair<ThermalValue, ThermalValue> range, int min, int max) {
        // Sum of squares for the differences between both min values and both max values
        final int diff_min = (int) range.first.asCelsius().value - min;
        final int diff_max = (int) range.second.asCelsius().value - max;
        return diff_min * diff_min + diff_max * diff_max;
    }
}
