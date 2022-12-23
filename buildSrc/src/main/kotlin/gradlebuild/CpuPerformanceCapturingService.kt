/*
 * Copyright 2022 the original author or authors.
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

package gradlebuild

import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.ExecOperations
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.ceil

abstract class PmSetValueSource : ValueSource<Int?, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): Int? {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("pmset", "-g", "therm")
            standardOutput = output
        }
        return if (result.exitValue == 0) parseOutput(output.toString(StandardCharsets.UTF_8)) else null
    }

    private fun parseOutput(output: String) =
        output
            .lines()
            .map { it.trim() }
            .find { it.startsWith("CPU_Speed_Limit") }
            ?.split("=")
            ?.first()
            ?.trim()
            ?.toInt()
}

abstract class CpuPerformanceCapturingService : BuildService<BuildServiceParameters.None>, AutoCloseable, OperationCompletionListener {
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private val performanceSamples = ConcurrentLinkedQueue<Int>()

    @get:Inject
    abstract val providerFactory: ProviderFactory

    private val pmSetValue = providerFactory.of(PmSetValueSource::class.java) {}

    init {
        println("Perf service initializing...")
        if (OperatingSystem.current().isMacOsX) {
            val captureDataPoint = {
                println("Query speed limit")
                val speedLimit = querySpeedLimit()
                println("Speed limit: $speedLimit")
                if (speedLimit != null) {
                    performanceSamples.add(speedLimit)
                }
            }
            scheduler.scheduleAtFixedRate(captureDataPoint, 0, 3, TimeUnit.SECONDS)
            println("Perf service started")
        } else {
            println("Not running on MacOS - no thermal throttling data will be captured")
        }
    }

    override fun onFinish(event: FinishEvent?) {
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    fun getCpuPerformance() =
        if (performanceSamples.isEmpty()) null
        else {
            val samples = performanceSamples.toList().sorted() // ascending sort is intentional here as the lower the value the bigger actual throttling is
            CpuPerformance(
                samples.average().toInt(),
                samples.getOrElse(0) { -1 },
                samples.getOrElse(samples.size / 2) { -1 },
                listOf(50, 75, 95, 99).map { Percentile(it, percentile(samples, it)) }
            )
        }

    private fun percentile(samples: List<Int>, percentile: Int) =
        if (samples.size > 1) {
            val index = ceil(percentile / 100.0 * samples.size).toInt()
            samples[index - 1]
        } else -1

    private fun querySpeedLimit(): Int? {
        return pmSetValue.get()
    }

    data class CpuPerformance(
        val average: Int,
        val max: Int,
        val median: Int,
        val percentiles: List<Percentile>
    )

    data class Percentile(val rank: Int, val value: Int)
}

fun addPerformanceMeasurement(performanceService: Provider<CpuPerformanceCapturingService>) {
//    buildScan.buildFinished {
//        println("Build scan - build finished")
//        println(performanceService.get().getCpuPerformance())
//        performanceService.get().getCpuPerformance()?.apply {
//            println("Add perfm measuremenet values")
//            buildScan.value("CPU Performance Average", average.toString())
//            buildScan.value("CPU Performance Max", max.toString())
//            buildScan.value("CPU Performance Median", median.toString())
//
//            if (average < 100) {
//                buildScan.tag("CPU_THROTTLED")
//            }
//        }
//    }
}
