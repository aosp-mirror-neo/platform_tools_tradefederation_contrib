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

import static com.android.tradefed.targetprep.UserHelper.RUN_TESTS_AS_USER_KEY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestStatus;
import com.android.tradefed.result.skipped.SkipReason;
import com.android.tradefed.testtype.AndroidJUnitTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Unit tests for {@link ConcurrentUsersTestRunner}. */
@RunWith(JUnit4.class)
public class ConcurrentUsersTestRunnerTest {

    private static final String TEST_CLASS_NAME = "TestClass";
    private static final String TEST_METHOD_1 = "test1";
    private static final String CONCURRENT_USERS_TEST_NAME = "ConcurrentUsersTest";
    private static final int USER10 = 10;
    private static final int USER11 = 11;

    @Mock private ITestDevice mMockDevice;
    @Mock private ITestInvocationListener mMockListener;
    @Mock private AndroidJUnitTest mMockSubTest;
    @Mock private AndroidJUnitTest mMockInstallerTest;

    @Captor private ArgumentCaptor<HashMap<String, Metric>> mMetricCaptor;
    @Captor private ArgumentCaptor<TestInformation> mTestInfoCaptor;

    private IConfiguration mConfiguration;
    private ConcurrentUsersTestRunner mRunner;
    private TestInformation mTestInfo;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mConfiguration = new Configuration(/* name= */ "test", /* description= */ "test");
        mRunner = spy(new ConcurrentUsersTestRunner());
        mRunner.setConfiguration(mConfiguration);
        mRunner.setDevice(mMockDevice);
        mTestInfo = TestInformation.newBuilder().build();
    }

    private void setupTest(TestStatus user10Status, TestStatus user11Status) throws Exception {
        mTestInfo
                .properties()
                .put(
                        RunOnConcurrentUsersTargetPreparer.CONCURRENT_RUN_TESTS_AS_USER_KEY,
                        String.format("%d,%d", USER10, USER11));

        List<TestDescription> collectedTests = new ArrayList<>();
        TestDescription test1 = new TestDescription(TEST_CLASS_NAME, TEST_METHOD_1);
        collectedTests.add(test1);

        doReturn(collectedTests).when(mRunner).collectTests(any(), any());
        // First clone is for installer, subsequent are for execution
        doReturn(mMockInstallerTest).doReturn(mMockSubTest).when(mRunner).cloneSubTest();

        doAnswer(
                        invocation -> {
                            ITestInvocationListener listener = invocation.getArgument(1);
                            TestInformation testInfo = invocation.getArgument(0);
                            int userId =
                                    Integer.parseInt(
                                            testInfo.properties().get(RUN_TESTS_AS_USER_KEY));

                            TestStatus status = (userId == USER10) ? user10Status : user11Status;

                            listener.testRunStarted("sub-run", 1);
                            listener.testStarted(test1);

                            if (status != TestStatus.INCOMPLETE) {
                                switch (status) {
                                    case FAILURE:
                                        listener.testFailed(test1, "stacktrace");
                                        break;
                                    case ASSUMPTION_FAILURE:
                                        listener.testAssumptionFailure(test1, "stacktrace");
                                        break;
                                    case SKIPPED:
                                        listener.testSkipped(
                                                test1, new SkipReason("mocked skip", "UNKNOWN"));
                                        break;
                                    case IGNORED:
                                        listener.testIgnored(test1);
                                        break;
                                    default:
                                        break;
                                }
                                listener.testEnded(test1, new HashMap<String, Metric>());
                            }
                            // For INCOMPLETE, we don't call testEnded

                            listener.testRunEnded(0, new HashMap<String, Metric>());
                            return null;
                        })
                .when(mMockSubTest)
                .run(any(), any());
    }

    @Test
    public void testRun_installsServiceWithSingleUser() throws Exception {
        setupTest(TestStatus.PASSED, TestStatus.PASSED);

        mRunner.run(mTestInfo, mMockListener);

        verify(mMockInstallerTest).run(mTestInfoCaptor.capture(), any());
        TestInformation installerInfo = mTestInfoCaptor.getValue();
        // Verify the installer run is for a single user
        assertThat(installerInfo.properties().get(RUN_TESTS_AS_USER_KEY))
                .isEqualTo(String.valueOf(USER10));
    }

    @Test
    public void testRun_oneFailure_onePass_reportsFailure() throws Exception {
        setupTest(TestStatus.PASSED, TestStatus.FAILURE);

        mRunner.run(mTestInfo, mMockListener);

        InOrder inOrder = inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq(CONCURRENT_USERS_TEST_NAME), eq(1));
        inOrder.verify(mMockListener).testStarted(any(), anyLong());
        inOrder.verify(mMockListener).testFailed(any(), anyString());
        inOrder.verify(mMockListener).testEnded(any(), anyLong(), mMetricCaptor.capture());
        inOrder.verify(mMockListener).testRunEnded(anyLong(), mMetricCaptor.capture());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testRun_oneIgnored_onePass_reportsIgnored() throws Exception {
        setupTest(TestStatus.PASSED, TestStatus.IGNORED);

        mRunner.run(mTestInfo, mMockListener);

        InOrder inOrder = inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq(CONCURRENT_USERS_TEST_NAME), eq(1));
        inOrder.verify(mMockListener).testStarted(any(), anyLong());
        inOrder.verify(mMockListener).testIgnored(any());
        inOrder.verify(mMockListener).testEnded(any(), anyLong(), mMetricCaptor.capture());
        inOrder.verify(mMockListener).testRunEnded(anyLong(), mMetricCaptor.capture());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testRun_oneFailure_oneIgnored_reportsFailure() throws Exception {
        setupTest(TestStatus.IGNORED, TestStatus.FAILURE);

        mRunner.run(mTestInfo, mMockListener);

        InOrder inOrder = inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq(CONCURRENT_USERS_TEST_NAME), eq(1));
        inOrder.verify(mMockListener).testStarted(any(), anyLong());
        inOrder.verify(mMockListener).testFailed(any(), anyString());
        inOrder.verify(mMockListener).testEnded(any(), anyLong(), mMetricCaptor.capture());
        inOrder.verify(mMockListener).testRunEnded(anyLong(), mMetricCaptor.capture());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testRun_oneSkipped_oneIgnored_reportsSkipped() throws Exception {
        setupTest(TestStatus.IGNORED, TestStatus.SKIPPED);

        mRunner.run(mTestInfo, mMockListener);

        InOrder inOrder = inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq(CONCURRENT_USERS_TEST_NAME), eq(1));
        inOrder.verify(mMockListener).testStarted(any(), anyLong());
        inOrder.verify(mMockListener).testSkipped(any(), any(SkipReason.class));
        inOrder.verify(mMockListener, never()).testIgnored(any());
        inOrder.verify(mMockListener).testEnded(any(), anyLong(), mMetricCaptor.capture());
        inOrder.verify(mMockListener).testRunEnded(anyLong(), mMetricCaptor.capture());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testRun_oneIncomplete_onePass_reportsFailure() throws Exception {
        setupTest(TestStatus.PASSED, TestStatus.INCOMPLETE);

        mRunner.run(mTestInfo, mMockListener);

        InOrder inOrder = inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq(CONCURRENT_USERS_TEST_NAME), eq(1));
        inOrder.verify(mMockListener).testStarted(any(), anyLong());
        inOrder.verify(mMockListener).testFailed(any(), anyString());
        inOrder.verify(mMockListener).testEnded(any(), anyLong(), mMetricCaptor.capture());
        inOrder.verify(mMockListener).testRunEnded(anyLong(), mMetricCaptor.capture());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testRun_oneAssumptionFailure_oneIgnored_reportsAssumptionFailure()
            throws Exception {
        setupTest(TestStatus.IGNORED, TestStatus.ASSUMPTION_FAILURE);

        mRunner.run(mTestInfo, mMockListener);

        InOrder inOrder = inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq(CONCURRENT_USERS_TEST_NAME), eq(1));
        inOrder.verify(mMockListener).testStarted(any(), anyLong());
        inOrder.verify(mMockListener).testAssumptionFailure(any(), anyString());
        inOrder.verify(mMockListener, never()).testIgnored(any());
        inOrder.verify(mMockListener).testEnded(any(), anyLong(), mMetricCaptor.capture());
        inOrder.verify(mMockListener).testRunEnded(anyLong(), mMetricCaptor.capture());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testRun_oneFailure_oneIncomplete_reportsFailure() throws Exception {
        setupTest(TestStatus.INCOMPLETE, TestStatus.FAILURE);

        mRunner.run(mTestInfo, mMockListener);

        InOrder inOrder = inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq(CONCURRENT_USERS_TEST_NAME), eq(1));
        inOrder.verify(mMockListener).testStarted(any(), anyLong());
        inOrder.verify(mMockListener).testFailed(any(), anyString());
        inOrder.verify(mMockListener, never()).testAssumptionFailure(any(), anyString());
        inOrder.verify(mMockListener, never()).testIgnored(any());
        inOrder.verify(mMockListener, never()).testSkipped(any(), any(SkipReason.class));
        inOrder.verify(mMockListener).testEnded(any(), anyLong(), mMetricCaptor.capture());
        inOrder.verify(mMockListener).testRunEnded(anyLong(), mMetricCaptor.capture());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testRun_oneAssumptionFailure_oneSkipped_reportsAssumptionFailure()
            throws Exception {
        setupTest(TestStatus.SKIPPED, TestStatus.ASSUMPTION_FAILURE);

        mRunner.run(mTestInfo, mMockListener);

        InOrder inOrder = inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq(CONCURRENT_USERS_TEST_NAME), eq(1));
        inOrder.verify(mMockListener).testStarted(any(), anyLong());
        inOrder.verify(mMockListener).testAssumptionFailure(any(), anyString());
        inOrder.verify(mMockListener, never()).testSkipped(any(), any(SkipReason.class));
        inOrder.verify(mMockListener).testEnded(any(), anyLong(), mMetricCaptor.capture());
        inOrder.verify(mMockListener).testRunEnded(anyLong(), mMetricCaptor.capture());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testRun_bothPass_reportsPassed() throws Exception {
        setupTest(TestStatus.PASSED, TestStatus.PASSED);

        mRunner.run(mTestInfo, mMockListener);

        InOrder inOrder = inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq(CONCURRENT_USERS_TEST_NAME), eq(1));
        inOrder.verify(mMockListener).testStarted(any(), anyLong());
        inOrder.verify(mMockListener, never()).testFailed(any(), anyString());
        inOrder.verify(mMockListener, never()).testIgnored(any());
        inOrder.verify(mMockListener).testEnded(any(), anyLong(), mMetricCaptor.capture());
        inOrder.verify(mMockListener).testRunEnded(anyLong(), mMetricCaptor.capture());
    }
}
