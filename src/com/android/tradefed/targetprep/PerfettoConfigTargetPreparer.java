/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A {@link ITargetPreparer} that generate perfetto config */
@OptionClass(alias = "perfetto-config")
public class PerfettoConfigTargetPreparer extends BaseTargetPreparer {

    @Option(
            name = "trace-tool-name",
            description = "Look up the trace config tool from the test or module artifacts")
    private String mTraceToolName = "heap_profile";

    @Option(
            name = "update-push-trace-config-file",
            description =
                    "Key is a original perfetto trace config file name before modification. Value"
                        + " is the complete device path to push the perfetto trace config file.")
    private Map<String, String> mPushFiles = new HashMap<>();

    @Option(
            name = "debug-trace-config-file",
            description = "Path to the perfetto configuration file which is used for debugging.")
    private String mDebugTraceFile = "cpu_trace_config.textproto";

    @Option(
            name = "process-names-to-profile",
            description = "Comma-separated list of process names to profile.")
    private String mProcessNames = "com.android.systemui";

    @Option(name = "no-block-client", description = "When buffer is full, stop the profile early.")
    private boolean mNoBlockClient = true;

    @Option(
            name = "no-startup",
            description =
                    "Do not target processes that start during the profile. Requires Android 11.")
    private boolean mNoStartup = false;

    @Option(
            name = "heaps-to-collect",
            description =
                    "Comma-separated list of heaps to collect, e.g: malloc,art. Requires Android"
                            + " 12.")
    private String mHeapsCollect = "";

    @Option(
            name = "set-shmem-size-bytes",
            description =
                    "Size of buffer between client and heapprofd. Default 8MiB. Needs to be a power"
                            + " of two multiple of 4096, at least 8192.")
    private String mShmemSizeBytes = "16777216";

    @Option(
            name = "set-sampling-interval-bytes",
            description = "Sampling interval. Default 4096 (4KiB)")
    private String mSamplingInterval = "16384";

    private Map<String, File> mTestArtifactFilePathMap = new HashMap<>();
    private String mModuleName = null;
    private IAbi mAbi;

    /** {@inheritDoc} */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        if (mPushFiles.isEmpty()) {
            CLog.i(
                    "update-push-trace-config-file is not set. PerfettoConfigTargetPreparer did"
                            + " nothing.");
            return;
        }
        for (String pushFile : mPushFiles.keySet()) {
            // Get trace config file from artifacts
            final File srcFile = getFileFromTestArtifacts(testInfo.getBuildInfo(), pushFile);
            switch (mTraceToolName) {
                case "heap_profile":
                    final String updatedHeapFileContents = updateHeapTraceConfig(testInfo, srcFile);
                    pushString(
                            testInfo.getDevice(),
                            updatedHeapFileContents,
                            mPushFiles.get(pushFile));
                    break;
                case "cpu_profile":
                    final String updatedCpuFileContents = updateDebugTraceConfig(testInfo, srcFile);
                    pushString(
                            testInfo.getDevice(), updatedCpuFileContents, mPushFiles.get(pushFile));
                    break;
            }
        }
    }

    /**
     * Update the debug trace config file by replacing the PROCESS_NAME with the process names
     * specified by the user, and append the cpu data sources to the original trace config file.
     *
     * @param testInfo the test information.
     * @param srcFile the original trace config file.
     * @return the updated trace config file content.
     * @throws DeviceNotAvailableException if the device is not available.
     */
    private String updateDebugTraceConfig(TestInformation testInfo, File srcFile)
            throws DeviceNotAvailableException {
        final String dataSources;
        final File debugTraceFile =
                getFileFromTestArtifacts(testInfo.getBuildInfo(), mDebugTraceFile);
        if (debugTraceFile == null || !debugTraceFile.exists()) {
            CLog.w("Cannot find CPU trace config file %s.", mDebugTraceFile);
            return null;
        }
        try {
            String debugTraceFileContent = FileUtil.readStringFromFile(debugTraceFile);
            String rawDataSources = debugTraceFileContent.replace("PROCESS_NAME", mProcessNames);
            CLog.i(String.format("CPU data sources (raw): %s", rawDataSources));
            dataSources = filterCommentLines(rawDataSources);
        } catch (IOException e) {
            CLog.e(
                    String.format(
                            "Unable to read CPU trace config file %s",
                            debugTraceFile.getAbsolutePath()),
                    e);
            return null;
        }

        CLog.i(String.format("CPU data sources (filtered): %s", dataSources));
        String originalContent;
        try {
            originalContent = FileUtil.readStringFromFile(srcFile);
            CLog.i(String.format("Original file content: %s", originalContent));
        } catch (IOException e) {
            CLog.e(
                    String.format(
                            "Unable to read original config file %s", srcFile.getAbsolutePath()),
                    e);
            return null;
        }
        if (originalContent.contains("name: \"linux.perf\"")) {
            int start =
                    originalContent.indexOf(
                            "data_sources: {\n        config {\n          name: \"linux.perf\"");
            // int end = originalContent.indexOf("}\n}", start);
            if (start != -1) {
                final String updatedContent = originalContent.substring(0, start) + dataSources;
                // + originalContent.substring(end - 1);
                CLog.i(String.format("Updated file content: %s", updatedContent));
                return updatedContent;
            }
        }
        return null;
    }

    /**
     * Using heap_profile tool to update perfetto trace config. heap_profile cmdline doc:
     * https://perfetto.dev/docs/reference/heap_profile-cli
     */
    private String updateHeapTraceConfig(TestInformation testInfo, File srcFile) {
        // Get heap_profile - record heap profile on Android device
        File traceToolFile = getFileFromTestArtifacts(testInfo.getBuildInfo(), mTraceToolName);

        List<String> commandArgsList = new ArrayList<String>();
        commandArgsList.add(traceToolFile.getAbsolutePath());
        commandArgsList.add("--print-config");
        if (!mProcessNames.isEmpty()) {
            commandArgsList.add("--name");
            commandArgsList.add(mProcessNames);
        }
        if (!mShmemSizeBytes.isEmpty()) {
            commandArgsList.add("--shmem-size");
            commandArgsList.add(mShmemSizeBytes);
        }
        if (!mSamplingInterval.isEmpty()) {
            commandArgsList.add("--interval");
            commandArgsList.add(mSamplingInterval);
        }
        if (!mHeapsCollect.isEmpty()) {
            commandArgsList.add("--heaps");
            commandArgsList.add(mHeapsCollect);
        }
        if (mNoBlockClient) {
            commandArgsList.add("--no-block-client");
        }
        if (mNoStartup) {
            commandArgsList.add("--no-startup");
        }

        final String[] command = commandArgsList.toArray(new String[commandArgsList.size()]);
        CLog.i(
                String.format(
                        "Perfetto data_sources generation command: %s", String.join(" ", command)));

        CommandResult result = runHostCommand(10000, command, null, null);
        CLog.i(String.format("Command result status: %s", result.getStatus()));

        if (CommandStatus.SUCCESS.equals(result.getStatus())) {
            CLog.i(String.format("Original result: %s", result.getStdout()));
            String dataSourcesToAppend = extractDataSources(result.getStdout());
            CLog.i(String.format("Modified result to append: %s", dataSourcesToAppend));

            String originalContent;
            try {
                originalContent = FileUtil.readStringFromFile(srcFile);
            } catch (IOException e) {
                CLog.e(
                        String.format(
                                "Unable to read original config file %s",
                                srcFile.getAbsolutePath()),
                        e);
                return null;
            }
            // Append the modified data_sources to the original config file content.
            return originalContent + "\n" + dataSourcesToAppend;
        } else {
            CLog.e("Fail to run heap_profile command");
        }

        return null;
    }

    /**
     * Push the updated trace config file to the device.
     *
     * @param device the device to push to.
     * @param src the source string.
     * @param remotePath the remote path to push to.
     */
    private void pushString(ITestDevice device, String src, String remotePath)
            throws DeviceNotAvailableException {
        if (!device.pushString(src, remotePath)) {
            CLog.e(String.format("Failed to push a string to remote '%s'", remotePath));
        }
    }

    private static String extractDataSources(String output) {
        StringBuilder result = new StringBuilder();
        String[] lines = output.split("\n");
        boolean inDataSource = false;
        int levels = 0;

        for (String line : lines) {
            if (line.trim().startsWith("data_sources")) {
                inDataSource = true;
            }

            if (inDataSource && line.trim().contains("{")) {
                levels++;
            }

            // Extract data_sources object
            if (inDataSource && levels > 0) {
                result.append(line);
                result.append("\n");
                if (line.trim().contains("android.heapprofd")) {
                    result.append("    target_buffer: 2");
                    result.append("\n");
                }
            }

            if (inDataSource && line.trim().contains("}")) {
                levels--;
                if (levels == 0) {
                    return result.toString();
                }
            }
        }
        return result.toString();
    }

    /**
     * Filters out lines that are comments (start with // after trimming) or are empty.
     *
     * @param input The string containing lines to filter.
     * @return A new string with comment and empty lines removed.
     */
    private String filterCommentLines(String input) {
        StringBuilder sb = new StringBuilder();
        for (String line : input.split("\n")) {
            String trimmedLine = line.trim();
            if (!(trimmedLine.startsWith("//")
                            || trimmedLine.startsWith("#")
                            || trimmedLine.startsWith("--")
                            || trimmedLine.startsWith("/*")
                            || trimmedLine.startsWith("*"))
                    && !trimmedLine.isEmpty()) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString()
                .trim(); // Trim to remove any trailing newline if the last line was kept
    }

    /**
     * Retrieve the file from the test artifacts or module artifacts and cache it in a map for the
     * subsequent calls.
     *
     * @return File from the test artifact or module artifact. Returns null if file is not found.
     */
    public File getFileFromTestArtifacts(IBuildInfo buildInfo, String fileName) {
        if (mTestArtifactFilePathMap.containsKey(fileName)) {
            return mTestArtifactFilePathMap.get(fileName);
        }

        File resolvedFile = resolveRelativeFilePath(buildInfo, fileName);
        if (resolvedFile != null) {
            CLog.i("Using file %s from %s", fileName, resolvedFile.getAbsolutePath());
            mTestArtifactFilePathMap.put(fileName, resolvedFile);
        }
        return resolvedFile;
    }

    /**
     * Resolves the relative path of the file from the test artifacts directory or module directory.
     *
     * @param fileName file name that needs to be resolved.
     * @return File file resolved for the given file name. Returns null if file not found.
     */
    private File resolveRelativeFilePath(IBuildInfo buildInfo, String fileName) {
        File src = null;
        if (buildInfo != null) {
            src = buildInfo.getFile(fileName);
            if (src != null && src.exists()) {
                return src;
            }
        }

        if (buildInfo instanceof IDeviceBuildInfo) {
            IDeviceBuildInfo deviceBuild = (IDeviceBuildInfo) buildInfo;
            File testDir = deviceBuild.getTestsDir();
            if (testDir != null) {
                File f = new File(testDir, fileName);
                if (f.exists()) {
                    return f;
                }
            }
            List<File> scanDirs = new ArrayList<>();
            // If it exists, always look first in the ANDROID_TARGET_OUT_TESTCASES
            File targetTestCases = deviceBuild.getFile(BuildInfoFileKey.TARGET_LINKED_DIR);
            if (targetTestCases != null) {
                scanDirs.add(targetTestCases);
            }
            // If not, look into the test directory.
            if (testDir != null) {
                scanDirs.add(testDir);
            }

            if (mModuleName != null) {
                // Use module name as a discriminant to find some files
                if (testDir != null) {
                    try {
                        File moduleDir =
                                FileUtil.findDirectory(
                                        mModuleName, scanDirs.toArray(new File[] {}));
                        if (moduleDir != null) {
                            // If the spec is pushing the module itself
                            if (mModuleName.equals(fileName)) {
                                // If that's the main binary generated by the target, we push the
                                // full directory
                                return moduleDir;
                            }
                            // Search the module directory if it exists use it in priority
                            src = FileUtil.findFile(fileName, null, moduleDir);
                            if (src != null) {
                                CLog.i("Retrieving src file from" + src.getAbsolutePath());
                                return src;
                            }
                        } else {
                            CLog.d("Did not find any module directory for '%s'", mModuleName);
                        }

                    } catch (IOException e) {
                        CLog.w(
                                "Something went wrong while searching for the module '%s' "
                                        + "directory.",
                                mModuleName);
                    }
                }
            }
            // Search top-level matches
            for (File searchDir : scanDirs) {
                try {
                    Set<File> allMatch = FileUtil.findFilesObject(searchDir, fileName);
                    if (allMatch.size() > 1) {
                        CLog.d(
                                "Several match for filename '%s', searching for top-level match.",
                                fileName);
                        for (File f : allMatch) {
                            if (f.getParent().equals(searchDir.getAbsolutePath())) {
                                return f;
                            }
                        }
                    } else if (allMatch.size() == 1) {
                        return allMatch.iterator().next();
                    }
                } catch (IOException e) {
                    CLog.w("Failed to find test files from directory.");
                }
            }
            // Fall-back to searching everything
            try {
                // Search the full tests dir if no target dir is available.
                src = FileUtil.findFile(fileName, null, scanDirs.toArray(new File[] {}));
                if (src != null) {
                    // Search again with filtering on ABI
                    File srcWithAbi =
                            FileUtil.findFile(fileName, mAbi, scanDirs.toArray(new File[] {}));
                    if (srcWithAbi != null
                            && !srcWithAbi.getAbsolutePath().startsWith(src.getAbsolutePath())) {
                        // When multiple matches are found, return the one with matching
                        // ABI unless src is its parent directory.
                        return srcWithAbi;
                    }
                    return src;
                }
            } catch (IOException e) {
                CLog.w("Failed to find test files from directory.");
                src = null;
            }

            if (src == null && testDir != null) {
                // TODO(b/138416078): Once build dependency can be fixed and test required
                // APKs are all under the test module directory, we can remove this fallback
                // approach to do individual download from remote artifact.
                // Try to stage the files from remote zip files.
                src = buildInfo.stageRemoteFile(fileName, testDir);
                if (src != null) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.STAGE_UNDEFINED_DEPENDENCY, fileName);
                    try {
                        // Search again with filtering on ABI
                        File srcWithAbi = FileUtil.findFile(fileName, mAbi, testDir);
                        if (srcWithAbi != null
                                && !srcWithAbi
                                        .getAbsolutePath()
                                        .startsWith(src.getAbsolutePath())) {
                            // When multiple matches are found, return the one with matching
                            // ABI unless src is its parent directory.
                            return srcWithAbi;
                        }
                    } catch (IOException e) {
                        CLog.w("Failed to find test files with matching ABI from directory.");
                    }
                }
            }
        }
        return src;
    }

    /**
     * Run a host command with the given array of command args.
     *
     * @param commandArgs args to be used to construct the host command.
     * @param stdout output of the command.
     * @param stderr error message if any from the command.
     * @return return the command results.
     */
    CommandResult runHostCommand(
            long timeOut, String[] commandArgs, OutputStream stdout, OutputStream stderr) {
        if (stdout != null && stderr != null) {
            return RunUtil.getDefault().runTimedCmd(timeOut, stdout, stderr, commandArgs);
        }
        return RunUtil.getDefault().runTimedCmd(timeOut, commandArgs);
    }
}
