/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.car.app.navigation;

import static androidx.annotation.RestrictTo.Scope.LIBRARY;
import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static androidx.car.app.utils.ThreadUtils.checkMainThread;

import static java.util.Objects.requireNonNull;

import android.annotation.SuppressLint;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.car.app.CarContext;
import androidx.car.app.HostDispatcher;
import androidx.car.app.HostException;
import androidx.car.app.IOnDoneCallback;
import androidx.car.app.navigation.model.TravelEstimate;
import androidx.car.app.navigation.model.Trip;
import androidx.car.app.serialization.Bundleable;
import androidx.car.app.serialization.BundlerException;
import androidx.car.app.utils.RemoteUtils;

/**
 * Manager for communicating navigation related events with the host.
 *
 * <p>Navigation apps must use this interface to coordinate with the car system for navigation
 * specific resources such as vehicle cluster and heads-up displays.
 *
 * <p>When a navigation app receives a user action to start navigating, it should call {@link
 * #navigationStarted()} to indicate it is currently navigating. When the app receives a user action
 * to end navigation or when the destination is reached, {@link #navigationEnded()} should be
 * called.
 *
 * <p>Navigation apps must also register a {@link NavigationManagerListener} to handle callbacks to
 * {@link NavigationManagerListener#stopNavigation()} issued by the host.
 */
public class NavigationManager {
    private final INavigationManager.Stub mNavigationmanager;
    private final HostDispatcher mHostDispatcher;

    // Guarded by main thread access.
    @Nullable
    private NavigationManagerListener mListener;
    private boolean mIsNavigating;
    private boolean mIsAutoDriveEnabled;

    /**
     * Sends the destinations, steps, and trip estimates to the host.
     *
     * <p>The data <b>may</b> be rendered at different places in the car such as the instrument
     * cluster screen or the heads-up display.
     *
     * <p>This method should only be invoked once the navigation app has called {@link
     * #navigationStarted()}, or else the updates will be dropped by the host. Once the app has
     * called {@link #navigationEnded()} or received
     * {@link NavigationManagerListener#stopNavigation()} it should stop sending updates.
     *
     * <p>As the location changes, and in accordance with speed and rounded distance changes, the
     * {@link TravelEstimate}s in the provided {@link Trip} should be rebuilt and this method called
     * again. For example, when the next step is greater than 10 kilometers away and the display
     * unit is kilometers, updates should occur roughly every kilometer.
     *
     * <p>Data provided to the cluster display depends on the vehicle capabilities. In some
     * instances the information may not be shown at all. On some vehicles {@link
     * androidx.car.app.navigation.model.Maneuver}s of unknown type may be skipped while on other
     * displays the associated icon may be shown.
     *
     * @throws HostException            if the call is invoked by an app that is not declared as
     *                                  a navigation app in the manifest.
     * @throws IllegalStateException    if the call occurs when navigation is not started. See
     *                                  {@link #navigationStarted()} for more info.
     * @throws IllegalArgumentException if any of the destinations, steps, or trip position is
     *                                  not well formed.
     * @throws IllegalStateException    if the current thread is not the main thread.
     */
    @MainThread
    public void updateTrip(@NonNull Trip trip) {
        checkMainThread();
        if (!mIsNavigating) {
            throw new IllegalStateException("Navigation is not started");
        }

        Bundleable bundle;
        try {
            bundle = Bundleable.create(trip);
        } catch (BundlerException e) {
            throw new IllegalArgumentException("Serialization failure", e);
        }

        mHostDispatcher.dispatch(
                CarContext.NAVIGATION_SERVICE,
                (INavigationHost service) -> {
                    service.updateTrip(bundle);
                    return null;
                },
                "updateTrip");
    }

    /**
     * Sets a listener to start receiving navigation manager events, or {@code null} to clear the
     * listener.
     *
     * @throws IllegalStateException if {@code null} is passed in while navigation is started. See
     *                               {@link #navigationStarted()} for more info.
     * @throws IllegalStateException if the current thread is not the main thread.
     */
    // TODO(rampara): Add Executor parameter.
    @SuppressLint("ExecutorRegistration")
    @MainThread
    public void setListener(@Nullable NavigationManagerListener listener) {
        checkMainThread();
        if (mIsNavigating && listener == null) {
            throw new IllegalStateException("Removing listener while navigating");
        }
        this.mListener = listener;
        if (mIsAutoDriveEnabled && listener != null) {
            listener.onAutoDriveEnabled();
        }
    }

    /**
     * Notifies the host that the app has started active navigation.
     *
     * <p>Only one app may be actively navigating in the car at any time and ownership is managed by
     * the host. The app must call this method to inform the system that it has started
     * navigation in response to user action.
     *
     * <p>This function can only called if {@link #setListener(NavigationManagerListener)} has been
     * called with a non-{@code null} value. The listener is required so that a signal to stop
     * navigation from the host can be handled using
     * {@link NavigationManagerListener#stopNavigation()}.
     *
     * <p>This method is idempotent.
     *
     * @throws IllegalStateException if no navigation manager listener has been set.
     * @throws IllegalStateException if the current thread is not the main thread.
     */
    @MainThread
    public void navigationStarted() {
        checkMainThread();
        if (mIsNavigating) {
            return;
        }
        if (mListener == null) {
            throw new IllegalStateException("No listener has been set");
        }
        mIsNavigating = true;
        mHostDispatcher.dispatch(
                CarContext.NAVIGATION_SERVICE,
                (INavigationHost service) -> {
                    service.navigationStarted();
                    return null;
                },
                "navigationStarted");
    }

    /**
     * Notifies the host that the app has ended active navigation.
     *
     * <p>Only one app may be actively navigating in the car at any time and ownership is managed by
     * the host. The app must call this method to inform the system that it has ended navigation,
     * for example, in response to the user cancelling navigation or upon reaching the destination.
     *
     * <p>This method is idempotent.
     *
     * @throws IllegalStateException if the current thread is not the main thread.
     */
    @MainThread
    public void navigationEnded() {
        checkMainThread();
        if (!mIsNavigating) {
            return;
        }
        mIsNavigating = false;
        mHostDispatcher.dispatch(
                CarContext.NAVIGATION_SERVICE,
                (INavigationHost service) -> {
                    service.navigationEnded();
                    return null;
                },
                "navigationEnded");
    }

    /**
     * Creates an instance of {@link NavigationManager}.
     *
     * @hide
     */
    @RestrictTo(LIBRARY)
    @NonNull
    public static NavigationManager create(@NonNull HostDispatcher hostDispatcher) {
        return new NavigationManager(hostDispatcher);
    }

    /**
     * Returns the {@code INavigationManager.Stub} binder object.
     *
     * @hide
     */
    @RestrictTo(LIBRARY)
    @NonNull
    public INavigationManager.Stub getIInterface() {
        return mNavigationmanager;
    }

    /**
     * Tells the app to stop navigating.
     *
     * @hide
     */
    @RestrictTo(LIBRARY)
    @MainThread
    public void stopNavigation() {
        checkMainThread();
        if (!mIsNavigating) {
            return;
        }
        mIsNavigating = false;
        requireNonNull(mListener).stopNavigation();
    }

    /**
     * Signifies that from this point, until {@link
     * androidx.car.app.CarAppService#onCarAppFinished} is called, any navigation
     * should automatically start driving to the destination as if the user was moving.
     *
     * <p>This is used in a testing environment, allowing testing the navigation app's navigation
     * capabilities without being in a car.
     *
     * @hide
     */
    @RestrictTo(LIBRARY)
    @MainThread
    public void onAutoDriveEnabled() {
        checkMainThread();
        mIsAutoDriveEnabled = true;
        if (mListener != null) {
            mListener.onAutoDriveEnabled();
        }
    }

    /** @hide */
    @RestrictTo(LIBRARY_GROUP) // Restrict to testing library
    @SuppressWarnings({"methodref.receiver.bound.invalid"})
    protected NavigationManager(@NonNull HostDispatcher hostDispatcher) {
        this.mHostDispatcher = requireNonNull(hostDispatcher);
        mNavigationmanager =
                new INavigationManager.Stub() {
                    @Override
                    public void stopNavigation(IOnDoneCallback callback) {
                        RemoteUtils.dispatchHostCall(
                                NavigationManager.this::stopNavigation, callback, "stopNavigation");
                    }
                };
    }
}
