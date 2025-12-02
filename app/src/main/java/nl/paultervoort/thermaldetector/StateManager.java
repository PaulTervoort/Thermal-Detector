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

import static nl.paultervoort.thermaldetector.StateManager.State.CALIBRATING;
import static nl.paultervoort.thermaldetector.StateManager.State.CLOBBERED;
import static nl.paultervoort.thermaldetector.StateManager.State.COMPROMISED;
import static nl.paultervoort.thermaldetector.StateManager.State.CONNECTED;
import static nl.paultervoort.thermaldetector.StateManager.State.CONNECTING;
import static nl.paultervoort.thermaldetector.StateManager.State.CONNECTING_PAUSED;
import static nl.paultervoort.thermaldetector.StateManager.State.DEVICE_FOUND;
import static nl.paultervoort.thermaldetector.StateManager.State.DISCOVERING;
import static nl.paultervoort.thermaldetector.StateManager.State.IDLE;
import static nl.paultervoort.thermaldetector.StateManager.State.NEED_CALIBRATE;
import static nl.paultervoort.thermaldetector.StateManager.State.NO_PERMISSION;
import static nl.paultervoort.thermaldetector.StateManager.State.STAND_BY;
import static nl.paultervoort.thermaldetector.StateManager.State.STARTING_STREAM;
import static nl.paultervoort.thermaldetector.StateManager.State.START_WITH_CALI;
import static nl.paultervoort.thermaldetector.StateManager.State.STREAMING;

import android.util.Pair;

import androidx.annotation.NonNull;

import com.flir.thermalsdk.log.ThermalLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A state machine to reflect FLIR thermal camera states. Thread safe.
 */
class StateManager {
    /**
     * FLIR camera states.
     */
    public enum State {
        NO_PERMISSION,      // No CAMERA permission, which is needed for the thermal camera
        COMPROMISED,        // The thermal camera is in an invalid state, cannot connect until reboot
        CLOBBERED,          // Another app accessed a camera while this app was connected, reset first now
        IDLE,               // Nothing is supposed to happen until the camera stream is requested
        DISCOVERING,        // Searching for a thermal camera on the phone
        DEVICE_FOUND,       // A thermal camera is found on the device, a reference is stored
        CONNECTING,         // Trying to connect to the thermal camera that was found before
        CONNECTING_PAUSED,  // Connecting, but after finished go to in stand by mode
        CONNECTED,          // Connected to a thermal camera and its control interface
        STAND_BY,           // A thermal camera is connected, but no camera stream is requested
        STARTING_STREAM,    // Trying to set up a camera stream from the connected thermal camera
        START_WITH_CALI,    // Starting stream, but calibration needed before streaming
        STREAMING,          // A valid thermal camera stream is currently active
        NEED_CALIBRATE,     // The camera needs to be calibrated
        CALIBRATING         // The camera is being calibrated
    }

    // Human readable definition of all legal state transitions
    private final static int STATE_COUNT = State.values().length;
    private final static boolean[][] STATE_TRANSITIONS = generateTransitions(new StatePreReq[]{
            new StatePreReq(NO_PERMISSION,      new State[] { IDLE }),
            new StatePreReq(COMPROMISED,        new State[] { CONNECTING, CONNECTING_PAUSED, STREAMING }),
            new StatePreReq(CLOBBERED,          State.values()), // Reachable from all states
            new StatePreReq(IDLE,               State.values()), // Reachable from all states
            new StatePreReq(DISCOVERING,        new State[] { IDLE }),
            new StatePreReq(DEVICE_FOUND,       new State[] { DISCOVERING, CONNECTING, CONNECTING_PAUSED,
                                                    CONNECTED, STARTING_STREAM, START_WITH_CALI, STREAMING,
                                                    NEED_CALIBRATE, CALIBRATING }),
            new StatePreReq(CONNECTING,         new State[] { DEVICE_FOUND, CONNECTING_PAUSED }),
            new StatePreReq(CONNECTING_PAUSED,  new State[] { DEVICE_FOUND, CONNECTING }),
            new StatePreReq(CONNECTED,          new State[] { STAND_BY, CONNECTING, STARTING_STREAM,
                                                    START_WITH_CALI }),
            new StatePreReq(STAND_BY,           new State[] { CONNECTING_PAUSED, CONNECTED, STARTING_STREAM,
                                                    START_WITH_CALI, STREAMING, NEED_CALIBRATE, CALIBRATING }),
            new StatePreReq(STARTING_STREAM,    new State[] { CONNECTED, START_WITH_CALI }),
            new StatePreReq(START_WITH_CALI,    new State[] { STARTING_STREAM }),
            new StatePreReq(STREAMING,          new State[] { STARTING_STREAM, CALIBRATING, NEED_CALIBRATE }),
            new StatePreReq(NEED_CALIBRATE,     new State[] { STREAMING, CALIBRATING }),
            new StatePreReq(CALIBRATING,        new State[] { START_WITH_CALI, NEED_CALIBRATE, STREAMING })
    });

    // Logging tag
    private final static String TAG = StateManager.class.getSimpleName();

    // State flow variables
    private final Executor stateExecutor = Executors.newSingleThreadExecutor();
    private State state = IDLE;

    // Determines the behaviour of the states
    private final Runnable[] preAction = new Runnable[STATE_COUNT]; // All null
    private final Runnable[] postAction = new Runnable[STATE_COUNT]; // All null
    private final int[] cleanupActionLevel = new int[STATE_COUNT]; // All 0
    private final List<Pair<Runnable, Boolean>> cleanupActions = new ArrayList<>();

    // State change callback
    private final Consumer<State> onStateChangeHandler;

    // Only accessible by builder class
    private StateManager(Consumer<State> onStateChangeHandler) {
        this.onStateChangeHandler = onStateChangeHandler;
    }

    /**
     * Getter.
     * @return The current camera state
     */
    public State getState() {
        return this.state;
    }

    /**
     * Check if the camera is currently in a specific state.
     * @param state The required state
     * @return True if the current state is 'state', false otherwise
     */
    public boolean isState(State state) {
        return this.state == state;
    }

    /**
     * Execute a runnable asynchronous from the state logic. Useful for transition side-effect callbacks.
     * @param runnable The runnable to execute
     */
    public void runOnStateThread(Runnable runnable) {
        this.stateExecutor.execute(runnable);
    }

    /**
     * Transition to a new state. Only succeeds when the transition is allowed.
     * @param state The new state
     * @return True if the transition was successful, false otherwise
     */
    public boolean setState(State state) {
        synchronized (this) {
            // Do nothing if identical state
            if (this.state == state) {
                return false;
            }

            // Only perform allowed transitions
            if (!STATE_TRANSITIONS[this.state.ordinal()][state.ordinal()]) {
                ThermalLog.d(TAG, "Illegal state transition: " + this.state + " -> " + state);
                return false;
            }

            // Run something before the state change
            Runnable preAction;
            if ((preAction = this.preAction[this.state.ordinal()]) != null) {
                preAction.run();
            }

            // Clean up old state
            final int oldCleanLevel = this.cleanupActionLevel[this.state.ordinal()];
            final int newCleanLevel = this.cleanupActionLevel[state.ordinal()];
            this.stateExecutor.execute(() -> {
                // Clean all levels from old level down to the level of the new state
                for (int i = oldCleanLevel - 1; i >= newCleanLevel; i--) {
                    // Run the clean function synchronized if needed
                    Pair<Runnable, Boolean> action = this.cleanupActions.get(i);
                    if (action.second) {
                        synchronized (this) { action.first.run(); }
                    } else {
                        action.first.run();
                    }
                }
            });

            // Update the state
            this.state = state;
        }

        // Run state specific action
        Runnable postAction;
        if ((postAction = this.postAction[state.ordinal()]) != null) {
            this.stateExecutor.execute(postAction);
        }

        // Notify callback of state change
        ThermalLog.d(TAG, state.toString());
        onStateChangeHandler.accept(state);

        return true;
    }

    /**
     * Builder for a StateManager. Allows for customizable StateManager with immutable behaviour after construction.
     */
    static class StateManagerBuilder {
        private final StateManager stateManager;

        /**
         * Create a new StateManager builder.
         * @param onStateChangeHandler The state change handler assigned to the built StateManager
         */
        public StateManagerBuilder(Consumer<State> onStateChangeHandler) {
            this.stateManager = new StateManager(onStateChangeHandler);
        }

        /**
         * Add a custom action to be executed when leaving a specific state.
         * @param state The state after which to execute the action
         * @param action The action that is executed synchronously before state change.
         */
        public void setPreAction(State state, Runnable action) {
            this.stateManager.preAction[state.ordinal()] = action;
        }

        /**
         * Add a custom action to be executed after a specific state is set.
         * @param state The state before which to execute the action
         * @param action The action that is executed asynchronously after state change.
         */
        public void setPostAction(State state, Runnable action) {
            this.stateManager.postAction[state.ordinal()] = action;
        }

        /**
         * Add a custom action that cleans resources after leaving a state.
         * @param level The cleanup level associated with this action. Must be 1, 2, etc. in sequence of builder calls
         * @param synchronize If true, block state changes during cleanup
         * @param action The clean up action
         * @param states All states on this cleanup level
         */
        public void setCleanupAction(int level, boolean synchronize, @NonNull Runnable action, State[] states) {
            // Currently not supporting arbitrary order of level assignment
            int index = this.stateManager.cleanupActions.size() + 1;
            assert index == level;

            this.stateManager.cleanupActions.add(new Pair<>(action, synchronize));
            for (State state : states) {
                this.stateManager.cleanupActionLevel[state.ordinal()] = index;
            }
        }

        /**
         * Combine all applied modifications to a final StateManager.
         * @return A StateManager object
         */
        public StateManager build() {
            return this.stateManager;
        }
    }

    private static boolean[][] generateTransitions(StatePreReq[] statePrerequisites) {
        final boolean[][] transitions = new boolean[STATE_COUNT][STATE_COUNT]; // Start with all false
        for (StatePreReq transition : statePrerequisites) {
            for (State from : transition.sources) {
                transitions[from.ordinal()][transition.dest.ordinal()] = true;
            }
        }
        return transitions;
    }

    private static class StatePreReq {
        public final State dest;
        public final State[] sources;

        public StatePreReq(State dest, State[] sources) {
            this.dest = dest;
            this.sources = sources;
        }
    }
}
