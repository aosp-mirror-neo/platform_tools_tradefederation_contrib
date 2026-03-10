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

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestAnnotationFilterReceiver;
import com.android.tradefed.testtype.suite.params.IModuleParameterHandler;

import java.util.HashSet;
import java.util.Set;

/**
 * An abstract {@link IModuleParameterHandler} that provides the generic logic for configuring a
 * module to run tests concurrently on multiple users.
 *
 * <p>This handler works by wrapping the module's original {@link IRemoteTest} in a {@code
 * ConcurrentUsersTestRunner}. Subclasses must provide the specific {@link ITargetPreparer} needed
 * for their concurrency scenario (e.g., creating a secondary user, starting a background user).
 */
public abstract class BaseConcurrentUsersParameterHandler implements IModuleParameterHandler {

    @Override
    public void applySetup(IConfiguration moduleConfiguration) {
        // Exclude tests that are meant to run only on the system user, as concurrent tests
        // run on secondary users.
        for (IRemoteTest test : moduleConfiguration.getTests()) {
            if (test instanceof ITestAnnotationFilterReceiver) {
                ITestAnnotationFilterReceiver filterTest = (ITestAnnotationFilterReceiver) test;
                // Retrieve the current set of excludeAnnotations to preserve them.
                Set<String> excludeAnnotations = new HashSet<>(filterTest.getExcludeAnnotations());
                excludeAnnotations.add("android.platform.test.annotations.SystemUserOnly");
                filterTest.clearExcludeAnnotations();
                filterTest.addAllExcludeAnnotation(excludeAnnotations);
            }
        }
    }

    @Override
    public void addParameterSpecificConfig(IConfiguration moduleConfiguration) {
        try {
            IRemoteTest originalTest = moduleConfiguration.getTests().get(0);
            ITestWrapper concurrentRunner = new ConcurrentUsersTestRunner();
            OptionCopier.copyOptions(originalTest, (IRemoteTest) concurrentRunner);
            // Inject the original test into the wrapper.
            concurrentRunner.setSubtest(originalTest);
            moduleConfiguration.setTest(concurrentRunner);

            ITargetPreparer preparer = getTargetPreparer();
            // Add the preparer to the end of the list to ensure it runs after the module's
            // standard installers (e.g., SuiteApkInstaller). This allows it to identify
            // installed packages and make them available for the visible background user.
            moduleConfiguration.getTargetPreparers().add(preparer);

            CLog.i(
                    "Applied %s to module '%s'.",
                    this.getClass().getSimpleName(), moduleConfiguration.getName());
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply concurrent setup", e);
        }
    }

    /**
     * Gets the specific {@link ITargetPreparer} required for the concurrency scenario.
     *
     * @return The {@link ITargetPreparer} to be added to the configuration.
     */
    protected abstract ITargetPreparer getTargetPreparer();
}
