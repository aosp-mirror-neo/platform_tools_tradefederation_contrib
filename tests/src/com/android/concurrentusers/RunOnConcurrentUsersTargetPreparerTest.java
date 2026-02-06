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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.UserInfo;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.targetprep.TestAppInstallSetup;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RunWith(JUnit4.class)
public class RunOnConcurrentUsersTargetPreparerTest {

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private TestInformation mTestInfo;

    private RunOnConcurrentUsersTargetPreparer mPreparer;
    private OptionSetter mOptionSetter;

    @Before
    public void setUp() throws Exception {
        mPreparer = new RunOnConcurrentUsersTargetPreparer();
        mOptionSetter = new OptionSetter(mPreparer);
        when(mTestInfo.getDevice().getApiLevel()).thenReturn(30);
        when(mTestInfo.getDevice().isVisibleBackgroundUsersSupported()).thenReturn(true);
        Map<Integer, UserInfo> userInfos = new HashMap<>();
        userInfos.put(0, new UserInfo(0, "system", /* flag= */ 0, /* isRunning= */ true));
        when(mTestInfo.getDevice().getUserInfos()).thenReturn(userInfos);
        when(mTestInfo.getDevice().getMaxNumberOfUsersSupported()).thenReturn(3);
        ArrayList<Integer> userIds = new ArrayList<>();
        userIds.add(0);
        when(mTestInfo.getDevice().listUsers()).thenReturn(userIds);
    }

    @Test
    public void testSetUp_runTestConcurrently() throws Exception {
        when(mTestInfo.getDevice().isHeadlessSystemUserMode()).thenReturn(true);

        ArrayList<Integer> userIds = new ArrayList<>();
        int currentUser = 100;
        int secondaryUser = 101;
        userIds.add(currentUser);
        when(mTestInfo.getDevice().listUsers()).thenReturn(userIds);
        when(mTestInfo.getDevice().getCurrentUser()).thenReturn(currentUser);
        when(mTestInfo.getDevice().createUser(any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(secondaryUser);
        when(mTestInfo.getDevice().isUserSecondary(currentUser)).thenReturn(true);
        when(mTestInfo.getDevice().isUserSecondary(secondaryUser)).thenReturn(true);

        int secondaryDisplayId = 200;
        Set<Integer> secondaryDisplayIdSet = new HashSet<>();
        secondaryDisplayIdSet.add(secondaryDisplayId);
        when(mTestInfo.getDevice().listDisplayIdsForStartingVisibleBackgroundUsers())
                .thenReturn(secondaryDisplayIdSet);

        Map<Integer, UserInfo> userInfos = new HashMap<>();
        userInfos.put(
                currentUser,
                new UserInfo(currentUser, "current", /* flag= */ 0, /* isRunning= */ true));
        when(mTestInfo.getDevice().getUserInfos()).thenReturn(userInfos);

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.properties())
                .put(
                        RunOnConcurrentUsersTargetPreparer.CONCURRENT_RUN_TESTS_AS_USER_KEY,
                        String.format("%d,%d", currentUser, secondaryUser));
    }

    @Test
    public void setUp_installsPackagesFromTestInfo() throws Exception {
        when(mTestInfo.getDevice().createUser(any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(2);
        when(mTestInfo.getDevice().getCurrentUser()).thenReturn(0);
        when(mTestInfo.properties().get(TestAppInstallSetup.INSTALLED_PACKAGES_KEY))
                .thenReturn("com.pkg1,com.pkg2");

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice()).executeShellCommand("pm install-existing --user 2 com.pkg1");
        verify(mTestInfo.getDevice()).executeShellCommand("pm install-existing --user 2 com.pkg2");
    }

    @Test
    public void setUp_installsPackagesFromOptionAndTestInfo() throws Exception {
        when(mTestInfo.getDevice().createUser(any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(2);
        when(mTestInfo.getDevice().getCurrentUser()).thenReturn(0);
        mOptionSetter.setOptionValue("test-package-name", "com.pkg.option");
        when(mTestInfo.properties().get(TestAppInstallSetup.INSTALLED_PACKAGES_KEY))
                .thenReturn("com.pkg.info");

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice())
                .executeShellCommand("pm install-existing --user 2 com.pkg.option");
        verify(mTestInfo.getDevice())
                .executeShellCommand("pm install-existing --user 2 com.pkg.info");
    }

    @Test
    public void testSetUp_whenAlreadySecondaryUser_startsBackgroundUser() throws Exception {
        // Arrange
        int currentUser = 10;
        int secondaryUser = 11;
        int secondaryDisplayId = 200;

        when(mTestInfo.getDevice().getCurrentUser()).thenReturn(currentUser);
        when(mTestInfo.getDevice().isUserSecondary(currentUser)).thenReturn(true);

        // Mock finding another secondary user
        Map<Integer, UserInfo> userInfos = new HashMap<>();
        userInfos.put(0, new UserInfo(0, "system", 0, true));
        userInfos.put(
                currentUser,
                new UserInfo(
                        currentUser,
                        "current-user",
                        UserInfo.FLAG_FOR_TESTING,
                        /* isRunning= */ true,
                        "android.os.usertype.full.SECONDARY"));
        userInfos.put(
                secondaryUser,
                new UserInfo(
                        secondaryUser,
                        "other-user",
                        UserInfo.FLAG_FOR_TESTING,
                        /* isRunning= */ true,
                        "android.os.usertype.full.SECONDARY"));
        when(mTestInfo.getDevice().getUserInfos()).thenReturn(userInfos);

        Set<Integer> secondaryDisplayIdSet = new HashSet<>();
        secondaryDisplayIdSet.add(secondaryDisplayId);
        when(mTestInfo.getDevice().listDisplayIdsForStartingVisibleBackgroundUsers())
                .thenReturn(secondaryDisplayIdSet);

        // Act
        mPreparer.setUp(mTestInfo);

        // Assert
        verify(mTestInfo.getDevice())
                .startVisibleBackgroundUser(secondaryUser, secondaryDisplayId, true);
    }
}
