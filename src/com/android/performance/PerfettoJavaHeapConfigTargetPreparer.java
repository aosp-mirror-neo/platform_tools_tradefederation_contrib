/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.performance;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/** A {@link ITargetPreparer} that generate Java heap profile for perfetto config */
@OptionClass(alias = "perfetto-java-heap-config")
public class PerfettoJavaHeapConfigTargetPreparer extends BaseTargetPreparer {

    @Option(
            name = "push-trace-config-file",
            description = "Full path to push the trace on the device")
    private String mOutputFile = "/data/misc/perfetto-traces/trace_config_java_heap.textproto";

    @Option(
            name = "process-names-to-profile",
            description = "Comma-separated list of process names to profile.")
    private String mProcessNames = "com.android.systemui";

    @Option(
            name = "buffer-size-kb",
            description = "Buffer size in memory that store the whole java heap graph in kb")
    private int mBufferSizeKb = 256000;

    /** {@inheritDoc} */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("trace_config_java_heap", ".textproto");
            writeTraceConfig(tempFile);
            pushFile(testInfo.getDevice(), tempFile, mOutputFile);
        } catch (IOException e) {
            CLog.e("Error when creating Perfetto config", e);
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    private void writeTraceConfig(File srcFile) {
        CLog.i("Writing perfetto trace config for heap dump collection");
        String result = generateConfig(mProcessNames, mBufferSizeKb);
        CLog.i(String.format("Command result = %s", result));

        FileWriter fileWriter = null;
        try {
            fileWriter = new FileWriter(srcFile, true);
            storeToFile(srcFile.getName(), result, fileWriter);
        } catch (IOException e) {
            CLog.e(String.format("Unable to update file %s ", srcFile.getName()), e);
        } finally {
            if (fileWriter != null) {
                try {
                    fileWriter.close();
                } catch (IOException closeException) {
                    CLog.e(
                            String.format("Unable to close file %s ", srcFile.getName()),
                            closeException);
                }
            }
        }
    }

    private String generateConfig(String processNames, int bufferSizeKb) {
        return "buffers {\n"
                + "  size_kb: "
                + bufferSizeKb
                + "\n"
                + "  fill_policy: DISCARD\n"
                + "}\n"
                + "\n"
                + "data_sources {\n"
                + "  config {\n"
                + "    name: \"android.java_hprof\"\n"
                + "    java_hprof_config {\n"
                + "      process_cmdline: \""
                + processNames
                + "\"\n"
                + "      dump_smaps: true\n"
                + "    }\n"
                + "  }\n"
                + "}\n"
                + "\n"
                + "data_source_stop_timeout_ms: 100000\n"
                + "data_sources {\n"
                + "  config {\n"
                + "    name: \"android.packages_list\"\n"
                + "  }\n"
                + "}\n"
                + "\n"
                + "data_sources: {\n"
                + "  config {\n"
                + "    name: \"linux.process_stats\"\n"
                + "    process_stats_config {\n"
                + "      scan_all_processes_on_start: true\n"
                + "    }\n"
                + "  }\n"
                + "}";
    }

    private void pushFile(ITestDevice device, File src, String remotePath)
            throws DeviceNotAvailableException {
        if (!device.pushFile(src, remotePath)) {
            CLog.e(
                    String.format(
                            "Failed to push local '%s' to remote '%s'", src.getPath(), remotePath));
        }
    }

    private void storeToFile(String targetFileName, String content, FileWriter target)
            throws RuntimeException {
        try {
            target.write('\n');
            target.write(content);
            target.write('\n');
        } catch (IOException e) {
            throw new RuntimeException(
                    String.format("Unable to write file %s ", targetFileName), e);
        }
    }
}
