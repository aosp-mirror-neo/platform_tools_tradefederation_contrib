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

package com.android.tradefed.targetprep;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link SdvAvbLockPreparer}. */
@RunWith(JUnit4.class)
public class SdvAvbLockPreparerTest {

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    private SdvAvbLockPreparer mPreparer;
    @Mock private ITestDevice mMockDevice;
    @Mock private TestInformation mMockTestInfo;

    private static final String DEVICE_SERIAL = "SERIAL";
    private static final long FASTBOOT_TIMEOUT_MS = 2 * 60 * 1000;

    @Before
    public void setUp() throws Exception {
        // No more MockitoAnnotations.initMocks(this);
        mPreparer = new SdvAvbLockPreparer();
        when(mMockTestInfo.getDevice()).thenReturn(mMockDevice);
        when(mMockDevice.getSerialNumber()).thenReturn(DEVICE_SERIAL);
        when(mMockDevice.getProperty("ro.boot.vbmeta.device_state")).thenReturn("unlocked");
    }

    /** Test that the preparer skips if the device is already locked. */
    @Test
    public void testSetUp_alreadyLocked() throws Exception {
        when(mMockDevice.getProperty("ro.boot.vbmeta.device_state")).thenReturn("locked");

        // Run the preparer
        mPreparer.setUp(mMockTestInfo);

        // Verify that no other methods were called on the device besides serial and property check
        InOrder inOrder = inOrder(mMockDevice);
        inOrder.verify(mMockDevice).getSerialNumber();
        inOrder.verify(mMockDevice).getProperty("ro.boot.vbmeta.device_state");
        inOrder.verifyNoMoreInteractions();
    }

    /** Test the happy path where all commands succeed. */
    @Test
    public void testSetUp_success() throws Exception {
        // Mock device behaviors
        when(mMockDevice.waitForDeviceAvailable(anyLong())).thenReturn(true);
        doNothing().when(mMockDevice).rebootIntoBootloader();

        CommandResult successResult = new CommandResult(CommandStatus.SUCCESS);
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("erase"), eq("userdata")))
                .thenReturn(successResult);
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("erase"), eq("metadata")))
                .thenReturn(successResult);
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("flashing"), eq("lock")))
                .thenReturn(successResult);

        doNothing().when(mMockDevice).reboot();

        // Run the preparer
        mPreparer.setUp(mMockTestInfo);

        // Verify the correct methods were called in order
        InOrder inOrder = inOrder(mMockDevice);
        inOrder.verify(mMockDevice).waitForDeviceAvailable(anyLong());
        inOrder.verify(mMockDevice).rebootIntoBootloader();
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "userdata");
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "metadata");
        inOrder.verify(mMockDevice).executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "flashing", "lock");
        inOrder.verify(mMockDevice).waitForDeviceAvailable(anyLong());
    }

    /** Test the case where erasing userdata fails. */
    @Test
    public void testSetUp_eraseUserdataFails() throws Exception {
        // Mock device behaviors
        when(mMockDevice.waitForDeviceAvailable(anyLong())).thenReturn(true);
        doNothing().when(mMockDevice).rebootIntoBootloader();

        CommandResult failureResult = new CommandResult(CommandStatus.FAILED);
        failureResult.setStderr("Erase failed");
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("erase"), eq("userdata")))
                .thenReturn(failureResult);

        // Run and expect TargetSetupError
        try {
            mPreparer.setUp(mMockTestInfo);
            fail("TargetSetupError should have been thrown.");
        } catch (TargetSetupError e) {
            // Expected
        }

        // Verify up to the point of failure
        InOrder inOrder = inOrder(mMockDevice);
        inOrder.verify(mMockDevice).waitForDeviceAvailable(anyLong());
        inOrder.verify(mMockDevice).rebootIntoBootloader();
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "userdata");
    }

    /** Test the case where erasing metadata fails. */
    @Test
    public void testSetUp_eraseMetadataFails() throws Exception {
        // Mock device behaviors
        when(mMockDevice.waitForDeviceAvailable(anyLong())).thenReturn(true);
        doNothing().when(mMockDevice).rebootIntoBootloader();

        CommandResult successResult = new CommandResult(CommandStatus.SUCCESS);
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("erase"), eq("userdata")))
                .thenReturn(successResult);
        CommandResult failureResult = new CommandResult(CommandStatus.FAILED);
        failureResult.setStderr("Erase metadata failed");
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("erase"), eq("metadata")))
                .thenReturn(failureResult);

        // Run and expect TargetSetupError
        try {
            mPreparer.setUp(mMockTestInfo);
            fail("TargetSetupError should have been thrown.");
        } catch (TargetSetupError e) {
            // Expected
        }

        // Verify up to the point of failure
        InOrder inOrder = inOrder(mMockDevice);
        inOrder.verify(mMockDevice).waitForDeviceAvailable(anyLong());
        inOrder.verify(mMockDevice).rebootIntoBootloader();
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "userdata");
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "metadata");
    }

    /** Test the case where flashing lock fails. */
    @Test
    public void testSetUp_flashingLockFails() throws Exception {
        // Mock device behaviors
        when(mMockDevice.waitForDeviceAvailable(anyLong())).thenReturn(true);
        doNothing().when(mMockDevice).rebootIntoBootloader();

        CommandResult successResult = new CommandResult(CommandStatus.SUCCESS);
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("erase"), eq("userdata")))
                .thenReturn(successResult);
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("erase"), eq("metadata")))
                .thenReturn(successResult);
        CommandResult failureResult = new CommandResult(CommandStatus.FAILED);
        failureResult.setStderr("Flashing lock failed");
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("flashing"), eq("lock")))
                .thenReturn(failureResult);

        // Run and expect TargetSetupError
        try {
            mPreparer.setUp(mMockTestInfo);
            fail("TargetSetupError should have been thrown.");
        } catch (TargetSetupError e) {
            // Expected
        }

        // Verify up to the point of failure
        InOrder inOrder = inOrder(mMockDevice);
        inOrder.verify(mMockDevice).waitForDeviceAvailable(anyLong());
        inOrder.verify(mMockDevice).rebootIntoBootloader();
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "userdata");
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "metadata");
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "flashing", "lock");
    }

    /** Test the case where the device is not available after reboot. */
    @Test
    public void testSetUp_deviceNotAvailableAfterReboot() throws Exception {
        // Mock device behaviors
        doNothing().when(mMockDevice).rebootIntoBootloader();
        when(mMockDevice.waitForDeviceAvailable(anyLong()))
                .thenReturn(true)
                .thenThrow(new DeviceNotAvailableException("Device not available", DEVICE_SERIAL));

        CommandResult successResult = new CommandResult(CommandStatus.SUCCESS);
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("erase"), eq("userdata")))
                .thenReturn(successResult);
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("erase"), eq("metadata")))
                .thenReturn(successResult);
        when(mMockDevice.executeFastbootCommand(anyLong(), eq("flashing"), eq("lock")))
                .thenReturn(successResult);

        // Run and expect DeviceNotAvailableException
        try {
            mPreparer.setUp(mMockTestInfo);
            fail("DeviceNotAvailableException should have been thrown.");
        } catch (DeviceNotAvailableException e) {
            // Expected
        }

        // Verify up to the point of failure
        InOrder inOrder = inOrder(mMockDevice);
        inOrder.verify(mMockDevice).waitForDeviceAvailable(anyLong());
        inOrder.verify(mMockDevice).rebootIntoBootloader();
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "userdata");
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "metadata");
        inOrder.verify(mMockDevice)
                .executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "flashing", "lock");
        inOrder.verify(mMockDevice).waitForDeviceAvailable(anyLong());
    }
}
