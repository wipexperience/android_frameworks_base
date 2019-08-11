/**
 * Copyright (C) 2019 The PixelExperience Project
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

package com.android.internal.custom.longshot;

/** @hide */
interface ILongScreenshotManager {

    boolean isLongshotHandleState();

    boolean isLongshotMode();

    boolean isLongshotMoveState();

    void notifyLongshotScroll(boolean z);

    void notifyScrollViewTop(int i);

    void onUnscrollableView();

    void registerLongshotListener(in ILongScreenshotListener iLongScreenshotListener);

    void stopLongshot();

    void takeLongshot(boolean z, boolean z2);

    void unregisterLongshotListener(in ILongScreenshotListener iLongScreenshotListener);
}
