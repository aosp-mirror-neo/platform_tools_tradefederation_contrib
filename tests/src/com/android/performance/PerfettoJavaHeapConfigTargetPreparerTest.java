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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.Pair;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Unit tests for {@link PerfettoJavaHeapConfigTargetPreparer}. */
@RunWith(JUnit4.class)
public class PerfettoJavaHeapConfigTargetPreparerTest {

    private final PerfettoJavaHeapConfigTargetPreparer mPreparer =
            new PerfettoJavaHeapConfigTargetPreparer();
    private ITestDevice mITestDevice = mock(ITestDevice.class);
    private List<Pair<String, String>> mPushedFiles = new ArrayList<>();

    @Before
    public void setUp() {
        try {
            when(mITestDevice.pushFile(any(), any()))
                    .thenAnswer(
                            (Answer<File>)
                                    invocation -> {
                                        final File localFile = (File) invocation.getArguments()[0];
                                        final String deviceFile =
                                                (String) invocation.getArguments()[1];
                                        final String content =
                                                Files.readString(
                                                        localFile.toPath(), StandardCharsets.UTF_8);
                                        mPushedFiles.add(new Pair<>(deviceFile, content));
                                        return null;
                                    });
        } catch (DeviceNotAvailableException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testNoParameters_pushesDefaultConfig() {
        runPreparer();

        assertOneFilePushed(
                "/data/misc/perfetto-traces/trace_config_java_heap.textproto",
                "buffers {\n"
                        + "  size_kb: 256000\n"
                        + "  fill_policy: DISCARD\n"
                        + "}\n"
                        + "\n"
                        + "data_sources {\n"
                        + "  config {\n"
                        + "    name: \"android.java_hprof\"\n"
                        + "    java_hprof_config {\n"
                        + "      process_cmdline: \"com.android.systemui\"\n"
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
                        + "}");
    }

    @Test
    public void testChangeProcessName_pushesConfigWithPassedProcessName()
            throws ConfigurationException {
        new OptionSetter(mPreparer).setOptionValue("process-names-to-profile", "com.other");

        runPreparer();

        assertOneFilePushed(
                "/data/misc/perfetto-traces/trace_config_java_heap.textproto",
                "buffers {\n"
                        + "  size_kb: 256000\n"
                        + "  fill_policy: DISCARD\n"
                        + "}\n"
                        + "\n"
                        + "data_sources {\n"
                        + "  config {\n"
                        + "    name: \"android.java_hprof\"\n"
                        + "    java_hprof_config {\n"
                        + "      process_cmdline: \"com.other\"\n"
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
                        + "}");
    }

    private void runPreparer() {
        final TestInformation testInformation = mock(TestInformation.class);
        when(testInformation.getDevice()).thenReturn(mITestDevice);
        try {
            mPreparer.setUp(testInformation);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void assertOneFilePushed(String pushedPath, String fileContent) {
        assertEquals(1, mPushedFiles.size());
        assertEquals(pushedPath, mPushedFiles.get(0).first);
        assertEquals(fileContent.strip(), mPushedFiles.get(0).second.strip());
    }
}
