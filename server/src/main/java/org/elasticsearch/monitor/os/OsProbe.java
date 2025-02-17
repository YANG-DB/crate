/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.monitor.os;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.Constants;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.monitor.Probes;
import org.elasticsearch.monitor.os.OsStats.Cgroup;
import org.elasticsearch.monitor.os.OsStats.Cgroup.CpuStat;

import io.crate.common.SuppressForbidden;


public class OsProbe {

    private static final OperatingSystemMXBean OS_MXBEAN = ManagementFactory.getOperatingSystemMXBean();

    private static final Method GET_FREE_PHYSICAL_MEMORY_SIZE;
    private static final Method GET_TOTAL_PHYSICAL_MEMORY_SIZE;
    private static final Method GET_FREE_SWAP_SPACE_SIZE;
    private static final Method GET_TOTAL_SWAP_SPACE_SIZE;
    private static final Method GET_SYSTEM_LOAD_AVERAGE;
    private static final Method GET_SYSTEM_CPU_LOAD;

    static {
        GET_FREE_PHYSICAL_MEMORY_SIZE = getMethod("getFreePhysicalMemorySize");
        GET_TOTAL_PHYSICAL_MEMORY_SIZE = getMethod("getTotalPhysicalMemorySize");
        GET_FREE_SWAP_SPACE_SIZE = getMethod("getFreeSwapSpaceSize");
        GET_TOTAL_SWAP_SPACE_SIZE = getMethod("getTotalSwapSpaceSize");
        GET_SYSTEM_LOAD_AVERAGE = getMethod("getSystemLoadAverage");
        GET_SYSTEM_CPU_LOAD = getMethod("getSystemCpuLoad");
    }

    private static final List<String> V1_FILES = List.of(
        "/sys/fs/cgroup/cpu",
        "/sys/fs/cgroup/cpuacct",
        "/sys/fs/cgroup/memory"
    );

    private static final List<String> V2_FILE_GLOBS = List.of(
        "glob:/sys/fs/cgroup/**/cpu.stat",
        "glob:/sys/fs/cgroup/**/memory.stat"
    );

    enum CGroupHierarchy {
        V1,
        V2,
        UNAVAILABLE;
    }


    /**
     * Returns the amount of free physical memory in bytes.
     */
    public long getFreePhysicalMemorySize() {
        if (GET_FREE_PHYSICAL_MEMORY_SIZE == null) {
            return -1;
        }
        try {
            return (long) GET_FREE_PHYSICAL_MEMORY_SIZE.invoke(OS_MXBEAN);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Returns the total amount of physical memory in bytes.
     */
    public long getTotalPhysicalMemorySize() {
        if (GET_TOTAL_PHYSICAL_MEMORY_SIZE == null) {
            return -1;
        }
        try {
            return (long) GET_TOTAL_PHYSICAL_MEMORY_SIZE.invoke(OS_MXBEAN);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Returns the amount of free swap space in bytes.
     */
    public long getFreeSwapSpaceSize() {
        if (GET_FREE_SWAP_SPACE_SIZE == null) {
            return -1;
        }
        try {
            return (long) GET_FREE_SWAP_SPACE_SIZE.invoke(OS_MXBEAN);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Returns the total amount of swap space in bytes.
     */
    public long getTotalSwapSpaceSize() {
        if (GET_TOTAL_SWAP_SPACE_SIZE == null) {
            return -1;
        }
        try {
            return (long) GET_TOTAL_SWAP_SPACE_SIZE.invoke(OS_MXBEAN);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The system load averages as an array.
     *
     * On Windows, this method returns {@code null}.
     *
     * On Linux, this method returns the 1, 5, and 15-minute load averages.
     *
     * On macOS, this method should return the 1-minute load average.
     *
     * @return the available system load averages or {@code null}
     */
    final double[] getSystemLoadAverage() {
        if (Constants.WINDOWS) {
            return null;
        } else if (Constants.LINUX) {
            try {
                final String procLoadAvg = readProcLoadavg();
                assert procLoadAvg.matches("(\\d+\\.\\d+\\s+){3}\\d+/\\d+\\s+\\d+");
                final String[] fields = procLoadAvg.split("\\s+");
                return new double[]{Double.parseDouble(fields[0]), Double.parseDouble(fields[1]), Double.parseDouble(fields[2])};
            } catch (final IOException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("error reading /proc/loadavg", e);
                }
                return null;
            }
        } else {
            assert Constants.MAC_OS_X;
            if (GET_SYSTEM_LOAD_AVERAGE == null) {
                return null;
            }
            try {
                final double oneMinuteLoadAverage = (double) GET_SYSTEM_LOAD_AVERAGE.invoke(OS_MXBEAN);
                return new double[]{oneMinuteLoadAverage >= 0 ? oneMinuteLoadAverage : -1, -1, -1};
            } catch (IllegalAccessException | InvocationTargetException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("error reading one minute load average from operating system", e);
                }
                return null;
            }
        }
    }

    /**
     * The line from {@code /proc/loadavg}. The first three fields are the load averages averaged over 1, 5, and 15 minutes. The fourth
     * field is two numbers separated by a slash, the first is the number of currently runnable scheduling entities, the second is the
     * number of scheduling entities on the system. The fifth field is the PID of the most recently created process.
     *
     * @return the line from {@code /proc/loadavg} or {@code null}
     */
    @SuppressForbidden(reason = "access /proc/loadavg")
    String readProcLoadavg() throws IOException {
        return readSingleLine(PathUtils.get("/proc/loadavg"));
    }

    public short getSystemCpuPercent() {
        return Probes.getLoadAndScaleToPercent(GET_SYSTEM_CPU_LOAD, OS_MXBEAN);
    }

    /**
     * Reads a file containing a single line.
     *
     * @param path path to the file to read
     * @return the single line
     * @throws IOException if an I/O exception occurs reading the file
     */
    private String readSingleLine(final Path path) throws IOException {
        final List<String> lines = Files.readAllLines(path);
        assert lines != null && lines.size() == 1;
        return lines.get(0);
    }

    // this property is to support a hack to workaround an issue with Docker containers mounting the cgroups hierarchy inconsistently with
    // respect to /proc/self/cgroup; for Docker containers this should be set to "/"
    private static final String CONTROL_GROUPS_HIERARCHY_OVERRIDE = System.getProperty("es.cgroups.hierarchy.override");

    /**
     * A map of the control groups to which the Elasticsearch process belongs. Note that this is a map because the control groups can vary
     * from subsystem to subsystem. Additionally, this map can not be cached because a running process can be reclassified.
     *
     * @return a map from subsystems to the control group for the Elasticsearch process.
     * @throws IOException if an I/O exception occurs reading {@code /proc/self/cgroup}
     */
    private Map<String, String> getControlGroups() throws IOException {
        final List<String> lines = readProcSelfCgroup();
        final Map<String, String> controllerMap = new HashMap<>();
        for (final String line : lines) {
            /*
             * The virtual file /proc/self/cgroup lists the control groups that the Elasticsearch process is a member of. Each line contains
             * three colon-separated fields of the form hierarchy-ID:subsystem-list:cgroup-path. For cgroups version 1 hierarchies, the
             * subsystem-list is a comma-separated list of subsystems. The subsystem-list can be empty if the hierarchy represents a cgroups
             * version 2 hierarchy. For cgroups version 1
             */
            final String[] fields = line.split(":");
            assert fields.length == 3;
            final String[] controllers = fields[1].split(",");
            for (final String controller : controllers) {
                final String controlGroupPath;
                if (CONTROL_GROUPS_HIERARCHY_OVERRIDE != null) {
                    /*
                     * Docker violates the relationship between /proc/self/cgroup and the /sys/fs/cgroup hierarchy. It's possible that this
                     * will be fixed in future versions of Docker with cgroup namespaces, but this requires modern kernels. Thus, we provide
                     * an undocumented hack for overriding the control group path. Do not rely on this hack, it will be removed.
                     */
                    controlGroupPath = CONTROL_GROUPS_HIERARCHY_OVERRIDE;
                } else {
                    controlGroupPath = fields[2];
                }
                final String previous = controllerMap.put(controller, controlGroupPath);
                assert previous == null;
            }
        }
        return controllerMap;
    }

    /**
     * The lines from {@code /proc/self/cgroup}. This file represents the control groups to which the Elasticsearch process belongs. Each
     * line in this file represents a control group hierarchy of the form
     * <p>
     * {@code \d+:([^:,]+(?:,[^:,]+)?):(/.*)}
     * <p>
     * with the first field representing the hierarchy ID, the second field representing a comma-separated list of the subsystems bound to
     * the hierarchy, and the last field representing the control group.
     *
     * @return the lines from {@code /proc/self/cgroup}
     * @throws IOException if an I/O exception occurs reading {@code /proc/self/cgroup}
     */
    @SuppressForbidden(reason = "access /proc/self/cgroup")
    List<String> readProcSelfCgroup() throws IOException {
        final List<String> lines = Files.readAllLines(PathUtils.get("/proc/self/cgroup"));
        assert lines != null && !lines.isEmpty();
        return lines;
    }

    /**
     * The total CPU time in nanoseconds consumed by all tasks in the cgroup to which the Elasticsearch process belongs for the {@code
     * cpuacct} subsystem.
     *
     * @param controlGroup the control group for the Elasticsearch process for the {@code cpuacct} subsystem
     * @return the total CPU time in nanoseconds
     * @throws IOException if an I/O exception occurs reading {@code cpuacct.usage} for the control group
     */
    private long getCgroupCpuAcctUsageNanos(final String controlGroup) throws IOException {
        return Long.parseLong(readSysFsCgroupCpuAcctCpuAcctUsage(controlGroup));
    }

    /**
     * Returns the line from {@code cpuacct.usage} for the control group to which the Elasticsearch process belongs for the {@code cpuacct}
     * subsystem. This line represents the total CPU time in nanoseconds consumed by all tasks in the same control group.
     *
     * @param controlGroup the control group to which the Elasticsearch process belongs for the {@code cpuacct} subsystem
     * @return the line from {@code cpuacct.usage}
     * @throws IOException if an I/O exception occurs reading {@code cpuacct.usage} for the control group
     */
    @SuppressForbidden(reason = "access /sys/fs/cgroup/cpuacct")
    String readSysFsCgroupCpuAcctCpuAcctUsage(final String controlGroup) throws IOException {
        return readSingleLine(PathUtils.get("/sys/fs/cgroup/cpuacct", controlGroup, "cpuacct.usage"));
    }

    /**
     * The total period of time in microseconds for how frequently the Elasticsearch control group's access to CPU resources will be
     * reallocated.
     *
     * @param controlGroup the control group for the Elasticsearch process for the {@code cpuacct} subsystem
     * @return the CFS quota period in microseconds
     * @throws IOException if an I/O exception occurs reading {@code cpu.cfs_period_us} for the control group
     */
    private long getCgroupCpuAcctCpuCfsPeriodMicros(final String controlGroup) throws IOException {
        return Long.parseLong(readSysFsCgroupCpuAcctCpuCfsPeriod(controlGroup));
    }

    /**
     * Returns the line from {@code cpu.cfs_period_us} for the control group to which the Elasticsearch process belongs for the {@code cpu}
     * subsystem. This line represents the period of time in microseconds for how frequently the control group's access to CPU resources
     * will be reallocated.
     *
     * @param controlGroup the control group to which the Elasticsearch process belongs for the {@code cpu} subsystem
     * @return the line from {@code cpu.cfs_period_us}
     * @throws IOException if an I/O exception occurs reading {@code cpu.cfs_period_us} for the control group
     */
    @SuppressForbidden(reason = "access /sys/fs/cgroup/cpu")
    String readSysFsCgroupCpuAcctCpuCfsPeriod(final String controlGroup) throws IOException {
        return readSingleLine(PathUtils.get("/sys/fs/cgroup/cpu", controlGroup, "cpu.cfs_period_us"));
    }

    /**
     * The total time in microseconds that all tasks in the Elasticsearch control group can run during one period as specified by {@code
     * cpu.cfs_period_us}.
     *
     * @param controlGroup the control group for the Elasticsearch process for the {@code cpuacct} subsystem
     * @return the CFS quota in microseconds
     * @throws IOException if an I/O exception occurs reading {@code cpu.cfs_quota_us} for the control group
     */
    private long getCgroupCpuAcctCpuCfsQuotaMicros(final String controlGroup) throws IOException {
        return Long.parseLong(readSysFsCgroupCpuAcctCpuAcctCfsQuota(controlGroup));
    }

    /**
     * Returns the line from {@code cpu.cfs_quota_us} for the control group to which the Elasticsearch process belongs for the {@code cpu}
     * subsystem. This line represents the total time in microseconds that all tasks in the control group can run during one period as
     * specified by {@code cpu.cfs_period_us}.
     *
     * @param controlGroup the control group to which the Elasticsearch process belongs for the {@code cpu} subsystem
     * @return the line from {@code cpu.cfs_quota_us}
     * @throws IOException if an I/O exception occurs reading {@code cpu.cfs_quota_us} for the control group
     */
    @SuppressForbidden(reason = "access /sys/fs/cgroup/cpu")
    String readSysFsCgroupCpuAcctCpuAcctCfsQuota(final String controlGroup) throws IOException {
        return readSingleLine(PathUtils.get("/sys/fs/cgroup/cpu", controlGroup, "cpu.cfs_quota_us"));
    }

    /**
     * The CPU time statistics for all tasks in the Elasticsearch control group.
     *
     * @param controlGroup the control group for the Elasticsearch process for the {@code cpuacct} subsystem
     * @return the CPU time statistics
     * @throws IOException if an I/O exception occurs reading {@code cpu.stat} for the control group
     */
    private OsStats.Cgroup.CpuStat getCgroupCpuAcctCpuStat(final String controlGroup) throws IOException {
        final List<String> lines = readSysFsCgroupCpuAcctCpuStat(controlGroup);
        long numberOfPeriods = -1;
        long numberOfTimesThrottled = -1;
        long timeThrottledNanos = -1;
        for (final String line : lines) {
            final String[] fields = line.split("\\s+");
            switch (fields[0]) {
                case "nr_periods":
                    numberOfPeriods = Long.parseLong(fields[1]);
                    break;
                case "nr_throttled":
                    numberOfTimesThrottled = Long.parseLong(fields[1]);
                    break;
                case "throttled_time":
                    timeThrottledNanos = Long.parseLong(fields[1]);
                    break;
                default:
                    break;
            }
        }
        assert numberOfPeriods != -1;
        assert numberOfTimesThrottled != -1;
        assert timeThrottledNanos != -1;
        return new OsStats.Cgroup.CpuStat(numberOfPeriods, numberOfTimesThrottled, timeThrottledNanos);
    }

    /**
     * Returns the lines from {@code cpu.stat} for the control group to which the Elasticsearch process belongs for the {@code cpu}
     * subsystem. These lines represent the CPU time statistics and have the form
     * <blockquote><pre>
     * nr_periods \d+
     * nr_throttled \d+
     * throttled_time \d+
     * </pre></blockquote>
     * where {@code nr_periods} is the number of period intervals as specified by {@code cpu.cfs_period_us} that have elapsed, {@code
     * nr_throttled} is the number of times tasks in the given control group have been throttled, and {@code throttled_time} is the total
     * time in nanoseconds for which tasks in the given control group have been throttled.
     *
     * @param controlGroup the control group to which the Elasticsearch process belongs for the {@code cpu} subsystem
     * @return the lines from {@code cpu.stat}
     * @throws IOException if an I/O exception occurs reading {@code cpu.stat} for the control group
     */
    @SuppressForbidden(reason = "access /sys/fs/cgroup/cpu")
    List<String> readSysFsCgroupCpuAcctCpuStat(final String controlGroup) throws IOException {
        final List<String> lines = Files.readAllLines(PathUtils.get("/sys/fs/cgroup/cpu", controlGroup, "cpu.stat"));
        assert lines != null && lines.size() == 3;
        return lines;
    }

    /**
     * The maximum amount of user memory (including file cache).
     * If there is no limit then some Linux versions return the maximum value that can be stored in an
     * unsigned 64 bit number, and this will overflow a long, hence the result type is <code>String</code>.
     * (The alternative would have been <code>BigInteger</code> but then it would not be possible to index
     * the OS stats document into Elasticsearch without losing information, as <code>BigInteger</code> is
     * not a supported Elasticsearch type.)
     *
     * @param controlGroup the control group for the Elasticsearch process for the {@code memory} subsystem
     * @return the maximum amount of user memory (including file cache)
     * @throws IOException if an I/O exception occurs reading {@code memory.limit_in_bytes} for the control group
     */
    private String getCgroupMemoryLimitInBytes(final String controlGroup) throws IOException {
        return readSysFsCgroupMemoryLimitInBytes(controlGroup);
    }

    /**
     * Returns the line from {@code memory.limit_in_bytes} for the control group to which the Elasticsearch process belongs for the
     * {@code memory} subsystem. This line represents the maximum amount of user memory (including file cache).
     *
     * @param controlGroup the control group to which the Elasticsearch process belongs for the {@code memory} subsystem
     * @return the line from {@code memory.limit_in_bytes}
     * @throws IOException if an I/O exception occurs reading {@code memory.limit_in_bytes} for the control group
     */
    @SuppressForbidden(reason = "access /sys/fs/cgroup/memory")
    String readSysFsCgroupMemoryLimitInBytes(final String controlGroup) throws IOException {
        return readSingleLine(PathUtils.get("/sys/fs/cgroup/memory", controlGroup, "memory.limit_in_bytes"));
    }

    /**
     * The total current memory usage by processes in the cgroup (in bytes).
     * If there is no limit then some Linux versions return the maximum value that can be stored in an
     * unsigned 64 bit number, and this will overflow a long, hence the result type is <code>String</code>.
     * (The alternative would have been <code>BigInteger</code> but then it would not be possible to index
     * the OS stats document into Elasticsearch without losing information, as <code>BigInteger</code> is
     * not a supported Elasticsearch type.)
     *
     * @param controlGroup the control group for the Elasticsearch process for the {@code memory} subsystem
     * @return the total current memory usage by processes in the cgroup (in bytes)
     * @throws IOException if an I/O exception occurs reading {@code memory.limit_in_bytes} for the control group
     */
    private String getCgroupMemoryUsageInBytes(final String controlGroup) throws IOException {
        return readSysFsCgroupMemoryUsageInBytes(controlGroup);
    }

    /**
     * Returns the line from {@code memory.usage_in_bytes} for the control group to which the Elasticsearch process belongs for the
     * {@code memory} subsystem. This line represents the total current memory usage by processes in the cgroup (in bytes).
     *
     * @param controlGroup the control group to which the Elasticsearch process belongs for the {@code memory} subsystem
     * @return the line from {@code memory.usage_in_bytes}
     * @throws IOException if an I/O exception occurs reading {@code memory.usage_in_bytes} for the control group
     */
    @SuppressForbidden(reason = "access /sys/fs/cgroup/memory")
    String readSysFsCgroupMemoryUsageInBytes(final String controlGroup) throws IOException {
        return readSingleLine(PathUtils.get("/sys/fs/cgroup/memory", controlGroup, "memory.usage_in_bytes"));
    }

    /**
     * Checks if cgroup stats are available by checking for the existence of {@code /proc/self/cgroup}, {@code /sys/fs/cgroup/cpu},
     * {@code /sys/fs/cgroup/cpuacct} and {@code /sys/fs/cgroup/memory}.
     *
     * @return {@code true} if the stats are available, otherwise {@code false}
     */
    @SuppressForbidden(reason = "access /proc/self/cgroup, /sys/fs/cgroup/cpu, /sys/fs/cgroup/cpuacct and /sys/fs/cgroup/memory")
    CGroupHierarchy getCgroupHierarchy() throws IOException {
        if (!Files.exists(PathUtils.get("/proc/self/cgroup"))) {
            return CGroupHierarchy.UNAVAILABLE;
        }
        Predicate<? super String> fileExists = x -> Files.exists(PathUtils.get(x));
        if (V1_FILES.stream().allMatch(fileExists)) {
            return CGroupHierarchy.V1;
        }
        for (String v2Glob : V2_FILE_GLOBS) {
            PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher(v2Glob);
            Iterator<Path> it = Files.walk(Paths.get("/sys/fs/cgroup")).iterator();
            while (it.hasNext()) {
                Path path = it.next();
                if (pathMatcher.matches(path)) {
                    return CGroupHierarchy.V2;
                }
            }
        }
        return CGroupHierarchy.UNAVAILABLE;
    }

    /**
     * Basic cgroup stats.
     *
     * @return basic cgroup stats, or {@code null} if an I/O exception occurred reading the cgroup stats
     */
    private OsStats.Cgroup getCgroup() {
        try {
            return switch (getCgroupHierarchy()) {
                case V1 -> v1Cgroup();
                case V2 -> v2Cgroup();
                case UNAVAILABLE -> null;
            };
        } catch (final IOException e) {
            logger.debug("error reading control group stats", e);
            return null;
        }
    }

    private static String getProperty(List<String> lines, String property, String defaultValue) {
        for (String line : lines) {
            int idxOfWhitespace = line.indexOf(' ');
            if (idxOfWhitespace >= 0) {
                String propertyName = line.substring(0, idxOfWhitespace);
                if (propertyName.equals(property)) {
                    return line.substring(idxOfWhitespace + 1);
                }
            }
        }
        return defaultValue;
    }

    private Cgroup v2Cgroup() throws IOException {
        // See https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html
        // for a description of the files in /sys/fs/cgroup

        final Map<String, String> controllerMap = getControlGroups();
        String cgroupPath = controllerMap.get("");
        if (cgroupPath == null) {
            return null;
        }
        List<String> cpuStats = Files.readAllLines(PathUtils.get("/sys/fs/cgroup/", cgroupPath, "cpu.stat"));
        long cpuAcctUsageNanos = Long.parseLong(getProperty(cpuStats, "usage_usec", "0")) * 1000;

        // cpu.stat contains the following properties only if the controller is enabled
        // - nr_periods
        // - nr_throttled
        // - throttled_usec
        //
        long throttledUsec = Long.parseLong(getProperty(cpuStats, "throttled_usec", "-1"));
        CpuStat cpuStat = new CpuStat(
            Long.parseLong(getProperty(cpuStats, "nr_periods", "-1")),
            Long.parseLong(getProperty(cpuStats, "nr_throttled", "-1")),
            // usec -> nanos
            throttledUsec == -1 ? -1 : throttledUsec * 1000
        );

        long cpuCfsPeriodMicros = -1;
        long cpuCfsQuotaMicros = -1;
        String memoryLimitInBytes = readSingleLine(PathUtils.get("/sys/fs/cgroup/", cgroupPath, "memory.max"));
        String memoryUsageInBytes = readSingleLine(PathUtils.get("/sys/fs/cgroup/", cgroupPath, "memory.current"));
        return new Cgroup(
            cgroupPath,
            cpuAcctUsageNanos,
            cgroupPath,
            cpuCfsPeriodMicros,
            cpuCfsQuotaMicros,
            cpuStat,
            cgroupPath,
            memoryLimitInBytes,
            memoryUsageInBytes
        );
    }

    private Cgroup v1Cgroup() throws IOException {
        final Map<String, String> controllerMap = getControlGroups();
        assert !controllerMap.isEmpty();

        final String cpuAcctControlGroup = controllerMap.get("cpuacct");
        assert cpuAcctControlGroup != null;
        final long cgroupCpuAcctUsageNanos = getCgroupCpuAcctUsageNanos(cpuAcctControlGroup);

        final String cpuControlGroup = controllerMap.get("cpu");
        assert cpuControlGroup != null;
        final long cgroupCpuAcctCpuCfsPeriodMicros = getCgroupCpuAcctCpuCfsPeriodMicros(cpuControlGroup);
        final long cgroupCpuAcctCpuCfsQuotaMicros = getCgroupCpuAcctCpuCfsQuotaMicros(cpuControlGroup);
        final OsStats.Cgroup.CpuStat cpuStat = getCgroupCpuAcctCpuStat(cpuControlGroup);

        final String memoryControlGroup = controllerMap.get("memory");
        assert memoryControlGroup != null;
        final String cgroupMemoryLimitInBytes = getCgroupMemoryLimitInBytes(memoryControlGroup);
        final String cgroupMemoryUsageInBytes = getCgroupMemoryUsageInBytes(memoryControlGroup);

        return new OsStats.Cgroup(
            cpuAcctControlGroup,
            cgroupCpuAcctUsageNanos,
            cpuControlGroup,
            cgroupCpuAcctCpuCfsPeriodMicros,
            cgroupCpuAcctCpuCfsQuotaMicros,
            cpuStat,
            memoryControlGroup,
            cgroupMemoryLimitInBytes,
            cgroupMemoryUsageInBytes
        );
    }

    private static class OsProbeHolder {
        private static final OsProbe INSTANCE = new OsProbe();
    }

    public static OsProbe getInstance() {
        return OsProbeHolder.INSTANCE;
    }

    OsProbe() {

    }

    private final Logger logger = LogManager.getLogger(getClass());

    public OsInfo osInfo(long refreshInterval, int allocatedProcessors) {
        return new OsInfo(refreshInterval, Runtime.getRuntime().availableProcessors(),
                allocatedProcessors, Constants.OS_NAME, Constants.OS_ARCH, Constants.OS_VERSION);
    }

    public OsStats osStats() {
        final OsStats.Cpu cpu = new OsStats.Cpu(getSystemCpuPercent(), getSystemLoadAverage());
        final OsStats.Mem mem = new OsStats.Mem(getTotalPhysicalMemorySize(), getFreePhysicalMemorySize());
        final OsStats.Swap swap = new OsStats.Swap(getTotalSwapSpaceSize(), getFreeSwapSpaceSize());
        final OsStats.Cgroup cgroup = Constants.LINUX ? getCgroup() : null;
        return new OsStats(System.currentTimeMillis(), cpu, mem, swap, cgroup);
    }

    /**
     * Returns a given method of the OperatingSystemMXBean, or null if the method is not found or unavailable.
     */
    private static Method getMethod(String methodName) {
        try {
            return Class.forName("com.sun.management.OperatingSystemMXBean").getMethod(methodName);
        } catch (Exception e) {
            // not available
            return null;
        }
    }

}
