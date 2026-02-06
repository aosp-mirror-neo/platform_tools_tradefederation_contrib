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

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.metric.IMetricCollector;
import com.android.tradefed.device.metric.IMetricCollectorReceiver;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.CollectingTestListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestResult;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.result.TestStatus;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IAbiReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestAnnotationFilterReceiver;
import com.android.tradefed.testtype.ITestCollector;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.testtype.InstrumentationTest;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A test runner that wraps another {@link IRemoteTest} to run its tests concurrently on multiple
 * users.
 *
 * <p>This runner is designed to be used with a {@link
 * com.android.tradefed.targetprep.ITargetPreparer} (such as {@link
 * RunOnConcurrentUsersTargetPreparer}) that sets up the necessary users on the device and passes
 * their IDs via the {@link RunOnConcurrentUsersTargetPreparer#CONCURRENT_RUN_TESTS_AS_USER_KEY}
 * property in the {@link TestInformation}.
 *
 * <p>For each test method, this runner executes the sub-test in parallel for each specified user.
 * It then merges the results from all users into a single, consolidated result for the final
 * report. The merged status is determined by a priority order (e.g., a failure in any user's run
 * results in an overall failure for the test).
 *
 * <p>This runner implements {@link ITestWrapper} and is typically injected by a {@link
 * com.android.tradefed.testtype.suite.params.IModuleParameterHandler}.
 */
public class ConcurrentUsersTestRunner
        implements ITestWrapper,
                IDeviceTest,
                IConfigurationReceiver,
                ITestCollector,
                IAbiReceiver,
                IMetricCollectorReceiver,
                ITestFilterReceiver,
                ITestAnnotationFilterReceiver {

    private IRemoteTest mSubtest;

    @Option(name = "include-filter", description = "The include filter of the test names to run.")
    private Set<String> mIncludeFilters = new LinkedHashSet<>();

    @Option(name = "exclude-filter", description = "The exclude filter of the test names to run.")
    private Set<String> mExcludeFilters = new LinkedHashSet<>();

    @Option(name = "include-annotation", description = "The annotation to include tests with.")
    private Set<String> mIncludeAnnotation = new LinkedHashSet<>();

    @Option(name = "exclude-annotation", description = "The annotation to exclude tests with.")
    private Set<String> mExcludeAnnotation = new LinkedHashSet<>();

    private ITestDevice mDevice;
    private IConfiguration mConfiguration;
    private boolean mCollectTestsOnly = false;
    private IAbi mAbi;
    private List<IMetricCollector> mMetricCollectors;

    private static final String TEST_RUN_NAME = "ConcurrentUsersTest";
    private static final String MODULE_PARAMETER_KEY = "module-parameter";

    @Override
    public void setSubtest(IRemoteTest subtest) {
        mSubtest = subtest;
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    @Override
    public void setCollectTestsOnly(boolean shouldCollect) {
        mCollectTestsOnly = shouldCollect;
    }

    @Override
    public void setAbi(IAbi abi) {
        mAbi = abi;
    }

    @Override
    public IAbi getAbi() {
        return mAbi;
    }

    @Override
    public void setMetricCollectors(List<IMetricCollector> collectors) {
        mMetricCollectors = collectors;
    }

    @Override
    public void addIncludeFilter(String filter) {
        mIncludeFilters.add(filter);
    }

    @Override
    public void addAllIncludeFilters(Set<String> filters) {
        mIncludeFilters.addAll(filters);
    }

    @Override
    public void addExcludeFilter(String filter) {
        mExcludeFilters.add(filter);
    }

    @Override
    public void addAllExcludeFilters(Set<String> filters) {
        mExcludeFilters.addAll(filters);
    }

    @Override
    public Set<String> getIncludeFilters() {
        return mIncludeFilters;
    }

    @Override
    public Set<String> getExcludeFilters() {
        return mExcludeFilters;
    }

    @Override
    public void clearIncludeFilters() {
        mIncludeFilters.clear();
    }

    @Override
    public void clearExcludeFilters() {
        mExcludeFilters.clear();
    }

    @Override
    public void addIncludeAnnotation(String annotation) {
        mIncludeAnnotation.add(annotation);
    }

    @Override
    public void addAllIncludeAnnotation(Set<String> annotations) {
        mIncludeAnnotation.addAll(annotations);
    }

    @Override
    public void addExcludeAnnotation(String annotation) {
        mExcludeAnnotation.add(annotation);
    }

    @Override
    public void addAllExcludeAnnotation(Set<String> annotations) {
        mExcludeAnnotation.addAll(annotations);
    }

    @Override
    public Set<String> getIncludeAnnotations() {
        return mIncludeAnnotation;
    }

    @Override
    public Set<String> getExcludeAnnotations() {
        return mExcludeAnnotation;
    }

    @Override
    public void clearIncludeAnnotations() {
        mIncludeAnnotation.clear();
    }

    @Override
    public void clearExcludeAnnotations() {
        mExcludeAnnotation.clear();
    }

    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        String userIds =
                testInfo.properties()
                        .get(RunOnConcurrentUsersTargetPreparer.CONCURRENT_RUN_TESTS_AS_USER_KEY);
        if (!shouldRunConcurrently(userIds)) {
            throw new IllegalArgumentException(
                    "ConcurrentUsersTestRunner is configured but the test is not a multi-user run. "
                            + "Ensure a preparer like RunOnSecondaryUserTargetPreparer is "
                            + "configured to set the '"
                            + RunOnConcurrentUsersTargetPreparer.CONCURRENT_RUN_TESTS_AS_USER_KEY
                            + "' property with multiple users.");
        }

        List<TestDescription> allTests = collectTests(testInfo, userIds.split(",")[0]);

        if (mCollectTestsOnly) {
            forwardCollectedTests(listener, allTests);
            return;
        }

        runTestsConcurrently(testInfo, listener, allTests, userIds.split(","));
    }

    @VisibleForTesting
    protected IRemoteTest cloneSubTest() {
        try {
            IRemoteTest clone = mSubtest.getClass().getDeclaredConstructor().newInstance();
            OptionCopier.copyOptions(mSubtest, clone);
            // Propagate injections
            if (clone instanceof IDeviceTest) {
                ((IDeviceTest) clone).setDevice(getDevice());
            }
            if (clone instanceof IConfigurationReceiver) {
                ((IConfigurationReceiver) clone).setConfiguration(mConfiguration);
            }
            if (clone instanceof IAbiReceiver) {
                ((IAbiReceiver) clone).setAbi(mAbi);
            }
            if (clone instanceof IMetricCollectorReceiver) {
                ((IMetricCollectorReceiver) clone).setMetricCollectors(mMetricCollectors);
            }
            return clone;
        } catch (ReflectiveOperationException | ConfigurationException e) {
            throw new RuntimeException("Failed to clone sub-test runner.", e);
        }
    }

    @VisibleForTesting
    protected List<TestDescription> collectTests(TestInformation testInfo, String collectionUser)
            throws DeviceNotAvailableException {
        IRemoteTest collector = cloneSubTest();
        if (!(collector instanceof ITestCollector)) {
            throw new HarnessRuntimeException(
                    String.format(
                            "Sub-test '%s' does not implement ITestCollector.",
                            mSubtest.getClass().getName()),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        CLog.d(
                "Collecting tests for user %s. Include filters: %s, Exclude filters: %s, "
                        + "Include annotations: %s, Exclude annotations: %s",
                collectionUser,
                mIncludeFilters,
                mExcludeFilters,
                mIncludeAnnotation,
                mExcludeAnnotation);
        if (collector instanceof ITestFilterReceiver) {
            ((ITestFilterReceiver) collector).addAllIncludeFilters(mIncludeFilters);
            ((ITestFilterReceiver) collector).addAllExcludeFilters(mExcludeFilters);
        }
        if (collector instanceof ITestAnnotationFilterReceiver) {
            ((ITestAnnotationFilterReceiver) collector).addAllIncludeAnnotation(mIncludeAnnotation);
            ((ITestAnnotationFilterReceiver) collector).addAllExcludeAnnotation(mExcludeAnnotation);
        }

        CollectingTestListener collectionListener = new CollectingTestListener();
        ((ITestCollector) collector).setCollectTestsOnly(true);

        TestInformation collectionInfo =
                TestInformation.newBuilder().setInvocationContext(testInfo.getContext()).build();
        collectionInfo.properties().putAll(testInfo.properties().getAll());
        collectionInfo.properties().put(RUN_TESTS_AS_USER_KEY, collectionUser);

        collector.run(collectionInfo, collectionListener);
        List<TestDescription> allTests = new ArrayList<>();
        for (TestRunResult runResult : collectionListener.getRunResults()) {
            allTests.addAll(runResult.getCompletedTests());
        }
        return allTests;
    }

    private void runTestsConcurrently(
            TestInformation testInfo,
            ITestInvocationListener listener,
            List<TestDescription> allTests,
            String[] users)
            throws DeviceNotAvailableException {
        long totalElapsedTime = 0;
        HashMap<String, Metric> totalMetrics = new HashMap<>();
        CLog.d(
                "Running %d tests concurrently on users %s",
                allTests.size(), Arrays.toString(users));
        listener.testRunStarted(TEST_RUN_NAME, allTests.size());

        for (TestDescription test : allTests) {
            CLog.d("Running test concurrently: %s", test);
            ExecutorService executor = Executors.newFixedThreadPool(users.length);
            List<CollectingTestListener> userListeners = new ArrayList<>();
            long testStartTime = System.currentTimeMillis();
            List<Future<Void>> futures = new ArrayList<>();

            for (String userId : users) {
                IRemoteTest testForUser = createTestForUser(test);
                TestInformation userInfo = createTestInfoForUser(testInfo, userId);
                CollectingTestListener perUserListener = new CollectingTestListener();
                userListeners.add(perUserListener);

                Callable<Void> testCallable =
                        () -> {
                            testForUser.run(userInfo, perUserListener);
                            return null;
                        };
                futures.add(executor.submit(testCallable));
            }

            try {
                for (Future<Void> future : futures) {
                    future.get(); // Wait for each test to complete
                }
            } catch (InterruptedException | ExecutionException e) {
                if (e.getCause() instanceof DeviceNotAvailableException) {
                    throw (DeviceNotAvailableException) e.getCause();
                }
                throw new RuntimeException(e);
            } finally {
                executor.shutdownNow(); // Interrupt running tasks
            }

            long testEndTime = System.currentTimeMillis();
            totalElapsedTime += (testEndTime - testStartTime);

            replayResults(listener, userListeners, users, test, totalMetrics);
        }

        listener.testRunEnded(totalElapsedTime, totalMetrics);
    }

    private IRemoteTest createTestForUser(TestDescription test) {
        IRemoteTest testForUser = cloneSubTest();
        if (testForUser instanceof ITestFilterReceiver) {
            ((ITestFilterReceiver) testForUser)
                    .addIncludeFilter(test.getClassName() + "#" + test.getTestName());
        }
        // Pass the test to run directly to the InstrumentationTest to avoid it re-collecting
        // the tests. This is a performance optimization to avoid an extra 'am instrument' call.
        if (testForUser instanceof InstrumentationTest) {
            List<TestDescription> testToRun = new ArrayList<>();
            testToRun.add(test);
            ((InstrumentationTest) testForUser).setTestsToRun(testToRun);
        }
        return testForUser;
    }

    private static boolean shouldRunConcurrently(String userIds) {
        return userIds != null && userIds.contains(",");
    }

    private static void forwardCollectedTests(
            ITestInvocationListener listener, List<TestDescription> allTests) {
        listener.testRunStarted(TEST_RUN_NAME, allTests.size());
        for (TestDescription test : allTests) {
            listener.testStarted(test);
            listener.testEnded(test, new HashMap<String, Metric>());
        }
        listener.testRunEnded(0, new HashMap<String, Metric>());
    }

    private static TestInformation createTestInfoForUser(TestInformation testInfo, String userId) {
        TestInformation userInfo =
                TestInformation.newBuilder().setInvocationContext(testInfo.getContext()).build();
        for (Map.Entry<String, String> entry : testInfo.properties().getAll().entrySet()) {
            userInfo.properties().put(entry.getKey(), entry.getValue());
        }
        userInfo.properties().remove(RUN_TESTS_AS_USER_KEY);
        userInfo.properties()
                .remove(RunOnConcurrentUsersTargetPreparer.CONCURRENT_RUN_TESTS_AS_USER_KEY);
        userInfo.properties().remove(MODULE_PARAMETER_KEY);
        userInfo.properties().put(RUN_TESTS_AS_USER_KEY, userId);
        return userInfo;
    }

    private static int getStatusRank(TestStatus status) {
        switch (status) {
            case FAILURE:
                return 6;
            case INCOMPLETE:
                return 5;
            case ASSUMPTION_FAILURE:
                return 4;
            case SKIPPED:
                return 3;
            case IGNORED:
                return 2;
            case PASSED:
                return 1;
            default:
                return 0;
        }
    }

    /**
     * Merges the results from multiple user runs for a single test and replays them to the primary
     * listener.
     *
     * <p>The status of the merged result is determined by the highest priority status from any of
     * the user runs. The priority order is: {@code FAILURE > INCOMPLETE > ASSUMPTION_FAILURE >
     * SKIPPED > IGNORED > PASSED}.
     *
     * <p>Metrics from each user run are aggregated and prefixed with "user-&lt;id&gt;:" to avoid
     * collisions.
     *
     * @param listener The main {@link ITestInvocationListener} to report the merged result to.
     * @param userListeners A list of {@link CollectingTestListener}s, one for each user run.
     * @param users An array of user IDs corresponding to the listeners.
     * @param originalTest The original {@link TestDescription} of the test method.
     * @param totalMetrics A map to which the run-level metrics will be added.
     */
    private static void replayResults(
            ITestInvocationListener listener,
            List<CollectingTestListener> userListeners,
            String[] users,
            TestDescription originalTest,
            HashMap<String, Metric> totalMetrics) {
        TestStatus mergedStatus = TestStatus.PASSED;
        TestResult representativeResult = null;
        long minStartTime = Long.MAX_VALUE;
        long maxEndTime = 0L;
        HashMap<String, Metric> mergedMetrics = new HashMap<>();
        List<TestResult> allResults = new ArrayList<>();

        // Step 1: Populate allResults and check for catastrophic run failures.
        for (int i = 0; i < userListeners.size(); i++) {
            TestRunResult runResult = userListeners.get(i).getCurrentRunResults();
            if (runResult.isRunFailure()) {
                mergedStatus = TestStatus.FAILURE;
                representativeResult = new TestResult();
                representativeResult.setStatus(TestStatus.FAILURE);
                representativeResult.setStackTrace(runResult.getRunFailureMessage());
                CLog.d("%s: RUN_FAILURE [user %s]", originalTest.getTestName(), users[i]);
                break; // A run failure is the highest priority failure.
            }
            totalMetrics.putAll(runResult.getRunProtoMetrics());

            // Iterate over all results, including incomplete ones
            for (Map.Entry<TestDescription, TestResult> entry :
                    runResult.getTestResults().entrySet()) {
                TestResult result = entry.getValue();
                allResults.add(result);

                CLog.d(
                        "%s: %s [user %s]",
                        originalTest.getTestName(), result.getResultStatus(), users[i]);

                for (Map.Entry<String, Metric> metricEntry : result.getProtoMetrics().entrySet()) {
                    mergedMetrics.put(
                            "user-" + users[i] + ":" + metricEntry.getKey(),
                            metricEntry.getValue());
                }
            }
        }

        // Step 2: If no run failures, determine status from completed test results.
        if (mergedStatus == TestStatus.PASSED) {
            for (TestResult result : allResults) {
                if (getStatusRank(result.getResultStatus()) > getStatusRank(mergedStatus)) {
                    mergedStatus = result.getResultStatus();
                    representativeResult = result;
                }
            }
        }

        // Step 3: Determine final timestamps and report results.
        if (!allResults.isEmpty()) {
            for (TestResult result : allResults) {
                minStartTime = Math.min(minStartTime, result.getStartTime());
                maxEndTime = Math.max(maxEndTime, result.getEndTime());
            }
        } else if (representativeResult != null) { // This handles the run failure case
            minStartTime = 0;
            maxEndTime = 0;
        } else {
            return; // Nothing to report.
        }

        listener.testStarted(originalTest, minStartTime);

        switch (mergedStatus) {
            case FAILURE:
                listener.testFailed(originalTest, representativeResult.getStackTrace());
                break;
            case INCOMPLETE:
                listener.testFailed(originalTest, "Test was incomplete.");
                break;
            case ASSUMPTION_FAILURE:
                listener.testAssumptionFailure(originalTest, representativeResult.getStackTrace());
                break;
            case SKIPPED:
                listener.testSkipped(originalTest, representativeResult.getSkipReason());
                break;
            case IGNORED:
                listener.testIgnored(originalTest);
                break;
            case PASSED:
                // Do nothing
                break;
        }
        listener.testEnded(originalTest, maxEndTime, mergedMetrics);
    }
}
