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

import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import java.util.Objects;

/**
 * A Tradefed {@link BaseTargetPreparer} designed to prepare a Software Defined Vehicle (SDV)
 * Cuttlefish device for testing by ensuring Android Verified Boot (AVB) is in a locked state.
 *
 * <p>This preparer replicates the steps in the sdv-avb-lock.sh script, which involves:
 *
 * <ol>
 *   <li>Rebooting the device to the bootloader.
 *   <li>Erasing userdata and metadata partitions.
 *   <li>Locking the flashing state using fastboot.
 *   <li>Rebooting the device back to the Android system.
 * </ol>
 *
 * <p>This is often required for testing SDV user builds in an environment that mimics the
 * production device state.
 *
 * <p>This preparer is based on the sdv-avb-lock.sh script, which is used to prepare SDV Cuttlefish
 * devices for testing.
 */
@OptionClass(alias = "sdv-avb-lock-preparer")
public class SdvAvbLockPreparer extends BaseTargetPreparer {

    private static final long FASTBOOT_TIMEOUT_MS = 2 * 60 * 1000; // 2 minutes
    private static final long DEVICE_WAIT_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        if (isDisabled()) {
            CLog.i("SdvAvbLockPreparer is disabled.");
            return;
        }

        ITestDevice device = testInfo.getDevice();
        String serial = device.getSerialNumber();

        if (Objects.equals(device.getProperty("ro.boot.vbmeta.device_state"), "locked")) {
            CLog.i("Device %s is already locked. Skipping SdvAvbLockPreparer.", serial);
            return;
        }

        CLog.i("Starting SDV AVB Lock process on device %s", serial);

        // adb wait-for-device
        device.waitForDeviceAvailable(DEVICE_WAIT_TIMEOUT_MS);
        CLog.d("Device %s is available.", serial);

        // adb reboot bootloader
        CLog.i("Rebooting %s to bootloader...", serial);
        device.rebootIntoBootloader();
        CLog.d("Device %s is in fastboot.", serial);

        // fastboot erase userdata
        // TODO: b/376256325 - the userdata and metadata partitions should be wiped automatically
        //       when the bootloader transitions between AVB locked and unlocked modes. Thus, these
        //       manual steps to wipe them should not be necessary, but they are with SDV Core
        //       Cuttlefish. (They are not necessary with the IVI Cuttlefish target.)
        CLog.i("Erasing userdata on %s...", serial);
        CommandResult resultUserdata =
                device.executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "userdata");
        if (!Objects.equals(resultUserdata.getStatus(), CommandStatus.SUCCESS)) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to erase userdata on %s: %s",
                            serial, resultUserdata.getStderr()),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }

        // fastboot erase metadata
        CLog.i("Erasing metadata on %s...", serial);
        CommandResult resultMetadata =
                device.executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "erase", "metadata");
        if (!Objects.equals(resultMetadata.getStatus(), CommandStatus.SUCCESS)) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to erase metadata on %s: %s",
                            serial, resultMetadata.getStderr()),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }

        // fastboot flashing lock
        CLog.i("Locking flashing on %s...", serial);
        CommandResult resultLock =
                device.executeFastbootCommand(FASTBOOT_TIMEOUT_MS, "flashing", "lock");
        if (!Objects.equals(resultLock.getStatus(), CommandStatus.SUCCESS)) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to lock flashing on %s: %s", serial, resultLock.getStderr()),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }

        // The Cuttlefish bootloader automatically reboots after a `flashing lock` command.

        // adb wait-for-device
        CLog.i("Waiting for device %s to be fully available after reboot...", serial);
        device.waitForDeviceAvailable(DEVICE_WAIT_TIMEOUT_MS);
        CLog.i("Device %s is online.", serial);

        CLog.i("SDV AVB Lock process completed on %s", serial);
    }
  }
