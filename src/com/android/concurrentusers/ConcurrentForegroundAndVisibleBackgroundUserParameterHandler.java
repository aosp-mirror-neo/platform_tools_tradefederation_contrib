/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.concurrentusers;

import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.testtype.suite.params.IModuleParameterHandler;

/**
 * A {@link IModuleParameterHandler} that configures a module to run tests concurrently on a primary
 * (foreground) user and a visible background secondary user.
 *
 * <p>This is designed for the Android Automotive OS driver/passenger scenario, where the driver is
 * the foreground user on the main display and the passenger is a visible background user on a
 * secondary display.
 */
public class ConcurrentForegroundAndVisibleBackgroundUserParameterHandler
        extends BaseConcurrentUsersParameterHandler {

    @Override
    public String getParameterIdentifier() {
        return "concurrent_foreground_and_visible_background_user";
    }

    @Override
    protected ITargetPreparer getTargetPreparer() {
        return new RunOnConcurrentUsersTargetPreparer();
    }
}
