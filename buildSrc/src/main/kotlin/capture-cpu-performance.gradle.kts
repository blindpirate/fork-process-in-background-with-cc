import org.gradle.kotlin.dsl.support.serviceOf
import gradlebuild.CpuPerformanceCapturingService

if (project.name != "gradle-kotlin-dsl-accessors") {
    val performanceService = gradle.sharedServices.registerIfAbsent("cpuPerformanceCapturing", CpuPerformanceCapturingService::class.java) {}
    gradle.serviceOf<BuildEventsListenerRegistry>().onTaskCompletion(performanceService)
}
