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

import com.android.tradefed.testtype.IRemoteTest;

/**
 * An interface for a test that wraps another {@link IRemoteTest}. This allows a test runner to be a
 * generic wrapper around another test instance.
 */
public interface ITestWrapper extends IRemoteTest {
    /**
     * Sets the subtest to be run.
     *
     * @param subtest The {@link IRemoteTest} to be wrapped.
     */
    public void setSubtest(IRemoteTest subtest);
}
