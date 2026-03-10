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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.StubTargetPreparer;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestAnnotationFilterReceiver;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Unit tests for {@link BaseConcurrentUsersParameterHandler}. */
@RunWith(JUnit4.class)
public class BaseConcurrentUsersParameterHandlerTest {

    /**
     * A concrete class that implements both {@link IRemoteTest} and {@link
     * ITestAnnotationFilterReceiver} for testing purposes.
     */
    private static class FilterableTest implements IRemoteTest, ITestAnnotationFilterReceiver {
        private Set<String> mExcludeAnnotations = new HashSet<>();

        @Override
        public void run(TestInformation testInfo, ITestInvocationListener listener)
                throws DeviceNotAvailableException {}

        @Override
        public void addIncludeAnnotation(String annotation) {}

        @Override
        public void addAllIncludeAnnotation(Set<String> annotations) {}

        @Override
        public void addExcludeAnnotation(String annotation) {
            mExcludeAnnotations.add(annotation);
        }

        @Override
        public void addAllExcludeAnnotation(Set<String> annotations) {
            mExcludeAnnotations.addAll(annotations);
        }

        @Override
        public Set<String> getIncludeAnnotations() {
            return new HashSet<>();
        }

        @Override
        public Set<String> getExcludeAnnotations() {
            return new HashSet<>(mExcludeAnnotations);
        }

        @Override
        public void clearIncludeAnnotations() {}

        @Override
        public void clearExcludeAnnotations() {
            mExcludeAnnotations.clear();
        }
    }

    private static final String SYSTEM_USER_ONLY_ANNOTATION =
            "android.platform.test.annotations.SystemUserOnly";
    private static final String EXISTING_ANNOTATION = "existing.annotation";

    private BaseConcurrentUsersParameterHandler mHandler;

    @Mock private IConfiguration mMockModuleConfiguration;
    private FilterableTest mSpyTest;
    @Mock private IRemoteTest mNonFilterableTest;
    @Captor private ArgumentCaptor<Set<String>> mAnnotationCaptor;
    @Captor private ArgumentCaptor<IRemoteTest> mTestCaptor;
    @Captor private ArgumentCaptor<ITargetPreparer> mPreparerCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mSpyTest = spy(new FilterableTest());

        // Default setup for most tests
        List<IRemoteTest> tests = new ArrayList<>();
        tests.add(mSpyTest);
        when(mMockModuleConfiguration.getTests()).thenReturn(tests);
        when(mMockModuleConfiguration.getTargetPreparers()).thenReturn(new ArrayList<>());
    }

    private void setupHandler(ITargetPreparer preparer) {
        mHandler =
                new BaseConcurrentUsersParameterHandler() {
                    @Override
                    public String getParameterIdentifier() {
                        return "test-concurrent";
                    }

                    @Override
                    protected ITargetPreparer getTargetPreparer() {
                        return preparer;
                    }
                };
    }

    @Test
    public void applySetup_addsSystemUserOnlyExclusion() {
        setupHandler(null);
        // Arrange: The test has no pre-existing exclude annotations.
        // The spy starts with an empty set, so no 'when' is needed.

        // Act
        mHandler.applySetup(mMockModuleConfiguration);

        // Assert
        verify(mSpyTest).clearExcludeAnnotations();
        verify(mSpyTest).addAllExcludeAnnotation(mAnnotationCaptor.capture());
        Set<String> capturedAnnotations = mAnnotationCaptor.getValue();
        assertThat(capturedAnnotations).containsExactly(SYSTEM_USER_ONLY_ANNOTATION);
    }

    @Test
    public void applySetup_preservesExistingExclusions() {
        setupHandler(null);
        // Arrange: The test already has an exclude annotation.
        mSpyTest.addExcludeAnnotation(EXISTING_ANNOTATION);

        // Act
        mHandler.applySetup(mMockModuleConfiguration);

        // Assert
        verify(mSpyTest).clearExcludeAnnotations();
        verify(mSpyTest).addAllExcludeAnnotation(mAnnotationCaptor.capture());
        Set<String> capturedAnnotations = mAnnotationCaptor.getValue();
        assertThat(capturedAnnotations)
                .containsExactly(SYSTEM_USER_ONLY_ANNOTATION, EXISTING_ANNOTATION);
    }

    @Test
    public void applySetup_withNonFilterableTest_doesNothing() {
        setupHandler(null);
        // Arrange: The test in the configuration does not support annotation filtering.
        List<IRemoteTest> tests = new ArrayList<>();
        tests.add(mNonFilterableTest);
        when(mMockModuleConfiguration.getTests()).thenReturn(tests);

        // Act
        mHandler.applySetup(mMockModuleConfiguration);

        // Assert: No exception is thrown, and no filtering methods are called on the spy,
        // because it was not in the list of tests for this scenario.
        verify(mSpyTest, never()).getExcludeAnnotations();
    }

    @Test
    public void addParameterSpecificConfig_wrapsTestAndAddsPreparer() {
        // Arrange
        ITargetPreparer preparer = new StubTargetPreparer();
        setupHandler(preparer);

        // Act
        mHandler.addParameterSpecificConfig(mMockModuleConfiguration);

        // Assert
        // Verify the test was replaced with the wrapper
        verify(mMockModuleConfiguration).setTest(mTestCaptor.capture());
        IRemoteTest capturedTest = mTestCaptor.getValue();
        assertThat(capturedTest).isInstanceOf(ITestWrapper.class);

        // Verify the preparer was added
        List<ITargetPreparer> preparers = mMockModuleConfiguration.getTargetPreparers();
        assertThat(preparers).hasSize(1);
        assertThat(preparers.get(0)).isEqualTo(preparer);
    }

    @Test
    public void addParameterSpecificConfig_addsPreparerAtEnd() {
        // Arrange
        ITargetPreparer existingPreparer = new StubTargetPreparer();
        List<ITargetPreparer> preparers = new ArrayList<>();
        preparers.add(existingPreparer);
        when(mMockModuleConfiguration.getTargetPreparers()).thenReturn(preparers);

        ITargetPreparer newPreparer = new StubTargetPreparer();
        setupHandler(newPreparer);

        // Act
        mHandler.addParameterSpecificConfig(mMockModuleConfiguration);

        // Assert
        // Verify both preparers are present and in the correct order
        assertThat(preparers).hasSize(2);
        assertThat(preparers.get(0)).isEqualTo(existingPreparer);
        assertThat(preparers.get(1)).isEqualTo(newPreparer);
    }
}
