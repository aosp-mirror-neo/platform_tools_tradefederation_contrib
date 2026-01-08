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

import static com.android.tradefed.targetprep.TestAppInstallSetup.INSTALLED_PACKAGES_KEY;

import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.targetprep.RunOnSecondaryUserTargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;

/**
 * A preparer for running tests concurrently on multiple users.
 *
 * <p>This preparer extends {@link RunOnSecondaryUserTargetPreparer} to set up a primary and a
 * secondary user for concurrent test execution. It is designed to be used with a test runner that
 * supports concurrent execution, such as {@code ConcurrentUsersTestRunner}.
 *
 * <p>It configures two users (e.g., a driver and a passenger) and passes their IDs to the test
 * runner via the {@link #CONCURRENT_RUN_TESTS_AS_USER_KEY} property.
 */
@OptionClass(alias = "run-on-concurrent-users")
public class RunOnConcurrentUsersTargetPreparer extends RunOnSecondaryUserTargetPreparer {

    /**
     * The key for a {@link TestInformation} property that contains a comma-separated list of user
     * IDs to run tests on concurrently.
     */
    static final String CONCURRENT_RUN_TESTS_AS_USER_KEY = "concurrent-run-on-users";

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        mStartBackgroundUser = true;
        // Add packages from properties to mTestPackages so that super.setUp() will install them.
        String packagesFromProperty = testInfo.properties().get(INSTALLED_PACKAGES_KEY);
        if (packagesFromProperty != null) {
            for (String pkgName : packagesFromProperty.split(",")) {
                if (!pkgName.isEmpty()) {
                    mTestPackages.add(pkgName);
                }
            }
        }

        super.setUp(testInfo);

        testInfo.properties()
                .put(
                        CONCURRENT_RUN_TESTS_AS_USER_KEY,
                        String.format("%d,%d", originalUserId, secondaryUserId));
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        super.tearDown(testInfo, e);
        testInfo.properties().remove(CONCURRENT_RUN_TESTS_AS_USER_KEY);
    }
}
