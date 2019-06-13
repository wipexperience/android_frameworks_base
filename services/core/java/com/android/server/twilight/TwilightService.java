/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.twilight;

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;

import java.util.Objects;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Figures out whether it's twilight time based on the user's location.
 * <p>
 * Used by the UI mode manager and other components to adjust night mode
 * effects based on sunrise and sunset.
 */
public final class TwilightService extends SystemService
        implements AlarmManager.OnAlarmListener, LocationListener {

    private static final String TAG = "TwilightService";
    private static final boolean DEBUG = false;

    @GuardedBy("mListeners")
    private final ArrayMap<TwilightListener, Handler> mListeners = new ArrayMap<>();

    private final Handler mHandler;

    protected AlarmManager mAlarmManager;
    private LocationManager mLocationManager;

    private BroadcastReceiver mTimeChangedReceiver;
    protected Location mLastLocation;

    @GuardedBy("mListeners")
    protected TwilightState mLastTwilightState;

    public TwilightService(Context context) {
        super(context);
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onStart() {
        publishLocalService(TwilightManager.class, new TwilightManager() {
            @Override
            public void registerListener(@NonNull TwilightListener listener,
                    @NonNull Handler handler) {
                synchronized (mListeners) {
                    final boolean wasEmpty = mListeners.isEmpty();
                    mListeners.put(listener, handler);
                }
            }

            @Override
            public void unregisterListener(@NonNull TwilightListener listener) {
                synchronized (mListeners) {
                    final boolean wasEmpty = mListeners.isEmpty();
                    mListeners.remove(listener);
                }
            }

            @Override
            public TwilightState getLastTwilightState() {
                synchronized (mListeners) {
                    return mLastTwilightState;
                }
            }
        });
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            final Context c = getContext();
            mAlarmManager = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            mLocationManager = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            startListening();
        }
    }

    private void startListening() {
        Slog.d(TAG, "startListening");

        // Start listening for location updates (default: low power, max 1h, min 10m).
        mLocationManager.requestLocationUpdates(
                null /* default */, this, Looper.getMainLooper());

        // Request the device's location immediately if a previous location isn't available.
        if (mLocationManager.getLastLocation() == null) {
            if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocationManager.requestSingleUpdate(
                        LocationManager.NETWORK_PROVIDER, this, Looper.getMainLooper());
            } else if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocationManager.requestSingleUpdate(
                        LocationManager.GPS_PROVIDER, this, Looper.getMainLooper());
            }
        }

        // Update whenever the system clock is changed.
        if (mTimeChangedReceiver == null) {
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Slog.d(TAG, "onReceive: " + intent);
                    updateTwilightState();
                }
            };

            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);
        }

        // Force an update now that we have listeners registered.
        updateTwilightState();
    }

    private void updateTwilightState() {
        // Calculate the twilight state based on the current time and location.
        final long currentTimeMillis = System.currentTimeMillis();
        final Location location = mLastLocation != null ? mLastLocation
                : mLocationManager.getLastLocation();
        final TwilightState state = calculateTwilightState(location, currentTimeMillis);
        if (DEBUG) {
            Slog.d(TAG, "updateTwilightState: " + state);
        }

        // Notify listeners if the state has changed.
        synchronized (mListeners) {
            if (!Objects.equals(mLastTwilightState, state)) {
                mLastTwilightState = state;

                for (int i = mListeners.size() - 1; i >= 0; --i) {
                    final TwilightListener listener = mListeners.keyAt(i);
                    final Handler handler = mListeners.valueAt(i);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onTwilightStateChanged(state);
                        }
                    });
                }
            }
        }

        // Schedule an alarm to update the state at the next sunrise or sunset.
        if (state != null) {
            final long triggerAtMillis = state.isNight()
                    ? state.sunriseTimeMillis() : state.sunsetTimeMillis();
            mAlarmManager.setExact(AlarmManager.RTC, triggerAtMillis, TAG, this, mHandler);
            Settings.System.putIntForUser(getContext().getContentResolver(),
                    Settings.System.THEME_AUTOMATIC_TIME_IS_NIGHT,
                    state.isNight() ? 1 : 0,
                    UserHandle.USER_CURRENT);
        }
    }

    @Override
    public void onAlarm() {
        Slog.d(TAG, "onAlarm");
        updateTwilightState();
    }

    @Override
    public void onLocationChanged(Location location) {
        // Location providers may erroneously return (0.0, 0.0) when they fail to determine the
        // device's location. These location updates can be safely ignored since the chance of a
        // user actually being at these coordinates is quite low.
        if (location != null
                && !(location.getLongitude() == 0.0 && location.getLatitude() == 0.0)) {
            Slog.d(TAG, "onLocationChanged:"
                    + " provider=" + location.getProvider()
                    + " accuracy=" + location.getAccuracy()
                    + " time=" + location.getTime());
            mLastLocation = location;
            updateTwilightState();
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    /**
     * Calculates the twilight state for a specific location and time.
     *
     * @param location the location to use
     * @param timeMillis the reference time to use
     * @return the calculated {@link TwilightState}, or {@code null} if location is {@code null}
     */
    private static TwilightState calculateTwilightState(Location location, long timeMillis) {
        if (location == null) {
            return null;
        }

        final SunriseSunsetCalculator calculator = new SunriseSunsetCalculator(
                location, TimeZone.getDefault().getID());
        final Calendar today = GregorianCalendar.getInstance();

        // calculate today's twilight
        final long sunriseTimeMillis = calculator.getOfficialSunriseCalendarForDate(today).getTimeInMillis();
        final long sunsetTimeMillis = calculator.getOfficialSunsetCalendarForDate(today).getTimeInMillis();

        // calculate yesterday's twilight
        final Calendar yesterday = GregorianCalendar.getInstance();
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        final long yesterdaySunset = calculator.getOfficialSunsetCalendarForDate(yesterday).getTimeInMillis();

        // calculate tomorrow's twilight
        final Calendar tomorrow = GregorianCalendar.getInstance();
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);
        final long tomorrowSunrise = calculator.getOfficialSunriseCalendarForDate(tomorrow).getTimeInMillis();

        return new TwilightState(sunriseTimeMillis, sunsetTimeMillis, yesterdaySunset, tomorrowSunrise);
    }
}
