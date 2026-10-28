package io.github.romanvht.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.TestActivity
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class TestService : Service() {

    companion object {
        private const val TAG = "TestService"
        private const val CHANNEL = "Proxy test"
        private const val RESULTS_FILE = "proxy_test_results.json"
        private const val BEST_RESULTS_FILE = "proxy_test_best_results.json"
        private const val NOTIFICATION_ID = 4
        private const val EXTRA_COMMANDS = "test_commands"
        private const val EXTRA_APPEND_RESULTS = "test_append_results"
        private const val EXTRA_GENETIC = "test_genetic"
        private val mutableState = MutableStateFlow(TestState())
        private val resultsLock = Any()
        val state: StateFlow<TestState> = mutableState.asStateFlow()
        val isRunning: Boolean
            get() = state.value.isRunning

        private val RESULT_ORDER = compareByDescending<StrategyResult> { it.successPercentage }
            .thenByDescending { it.successCount }
            .thenByDescending { it.totalRequests }

        fun start(
            context: Context,
            commands: List<String>? = null,
            appendResults: Boolean = false,
            genetic: Boolean = false,
        ) {
            if (isRunning) return
            ServiceManager.refresh(context)
            val previous = state.value
            mutableState.value = previous.copy(
                runId = previous.runId + 1,
                isRunning = true,
                isStopping = false,
                currentStrategy = 0,
                failed = false,
                wasInterrupted = false,
                isGenetic = genetic,
                generation = 0,
                generationSize = 0,
                geneticDeadline = 0L,
            )
            try {
                val intent = Intent(context, TestService::class.java).setAction(START_ACTION)
                if (commands != null) {
                    intent.putStringArrayListExtra(EXTRA_COMMANDS, ArrayList(commands))
                }
                if (appendResults) {
                    intent.putExtra(EXTRA_APPEND_RESULTS, true)
                }
                if (genetic) {
                    intent.putExtra(EXTRA_GENETIC, true)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                mutableState.value = previous.copy(failed = true)
                Log.e(TAG, "Failed to start proxy tests", e)
            }
        }

        fun stop() {
            if (!isRunning) return
            mutableState.value = state.value.copy(isStopping = true)
        }

        suspend fun awaitStopped() {
            state.first { !it.isRunning }
        }

        suspend fun loadResults(context: Context) {
            val previous = state.value
            if (previous.isLoaded || previous.isRunning) return
            val app = context.applicationContext
            val loaded = withContext(Dispatchers.IO) {
                val results = readResults(app, RESULTS_FILE)
                val best = readResults(app, BEST_RESULTS_FILE)
                previous.copy(
                    isLoaded = true,
                    strategies = results,
                    bestStrategies = best,
                    wasInterrupted = app.getPreferences().getBoolean("is_test_running", false),
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (mutableState.compareAndSet(previous, loaded)) {
                    app.getPreferences().edit { putBoolean("is_test_running", false) }
                }
            }
        }

        private fun readResults(context: Context, fileName: String): List<StrategyResult> {
            return try {
                synchronized(resultsLock) {
                    AtomicFile(File(context.filesDir, fileName)).openRead().bufferedReader().use { reader ->
                        val type = object : TypeToken<List<StrategyResult>>() {}.type
                        Gson().fromJson<List<StrategyResult>>(reader, type) ?: emptyList()
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private class TestRun {
        var settings: TestSettings? = null
        var currentStrategy: StrategyResult? = null
        var prepared = false
        var failed = false
        var genetic = false
        val tested = mutableListOf<StrategyResult>()
        var lastGeneration: List<StrategyResult> = emptyList()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gson = Gson()
    private val engineMutex = Mutex()
    private var engine: NativeEngine? = null
    private var testJob: Job? = null
    private var lastStartId = 0
    private var destroyed = false
    private var notifiedStrategy = -1
    private var notifiedCompleted = -1
    private var notifiedGeneration = -1
    private var carriedStrategies: List<StrategyResult> = emptyList()

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(this, CHANNEL, R.string.title_test)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            START_ACTION -> {
                try {
                    startForeground()
                    if (!isRunning) {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                        stopSelfResult(startId)
                    } else if (testJob == null) {
                        startTesting(
                            intent?.getStringArrayListExtra(EXTRA_COMMANDS),
                            intent?.getBooleanExtra(EXTRA_APPEND_RESULTS, false) == true,
                            intent?.getBooleanExtra(EXTRA_GENETIC, false) == true,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show proxy test notification", e)
                    if (testJob == null) {
                        mutableState.value = state.value.copy(isRunning = false, isStopping = false, failed = true)
                        stopSelfResult(startId)
                    } else {
                        stop()
                    }
                }
            }
            STOP_ACTION -> {
                stop()
                if (testJob == null) stopSelfResult(startId)
            }
            else -> if (testJob == null) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (testJob != null) stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startTesting(commands: List<String>?, appendResults: Boolean, genetic: Boolean) {
        val previous = state.value.copy(isRunning = false, isStopping = false)
        carriedStrategies = emptyList()
        val run = TestRun().apply { this.genetic = genetic }
        testJob = scope.launch(start = CoroutineStart.LAZY) {
            val testingJob = coroutineContext.job
            val stopWatcher = launch {
                try {
                    state.first { it.isStopping }
                    testingJob.cancel()
                } finally {
                    stopEngine()
                }
            }

            try {
                if (state.value.isStopping) {
                    throw CancellationException("Proxy test stopped")
                }
                val options = withContext(Dispatchers.IO) { loadSettings(commands) }
                run.settings = options
                if (options.sites.isEmpty()) {
                    Toast.makeText(this@TestService, R.string.test_settings_domain_empty, Toast.LENGTH_LONG).show()
                    return@launch
                }
                run.prepared = true
                getPreferences().edit(commit = true) { putBoolean("is_test_running", true) }

                if (genetic) {
                    runGenetic(run, options)
                } else {
                    runSingle(run, options, appendResults)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run proxy tests", e)
                run.failed = true
            } finally {
                stopWatcher.cancel()
                withContext(NonCancellable) {
                    mutableState.value = state.value.copy(isStopping = true)
                    run.settings?.let { options ->
                        run.currentStrategy?.takeIf { !it.isCompleted }?.let {
                            resetStrategyResult(it, options)
                            it.isCompleted = true
                        }
                    }
                    stopEngine()
                    if (run.prepared) {
                        if (run.tested.isEmpty() || run.tested.any { it.isCompleted }) {
                            try {
                                saveResults(run.tested + carriedStrategies)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save proxy test results", e)
                                run.failed = true
                            }
                        }
                        if (genetic) {
                            val completed = run.lastGeneration.filter { it.isCompleted }
                            if (completed.isNotEmpty()) {
                                val config = loadGeneticSettings()
                                val merged = mergeBest(state.value.bestStrategies, completed, config.bestCount)
                                if (merged.isNotEmpty()) {
                                    try {
                                        saveBest(merged)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to save best strategies", e)
                                    }
                                    mutableState.value = state.value.copy(bestStrategies = merged)
                                }
                                saveGeneticPopulation(completed.sortedWith(RESULT_ORDER))
                            }
                        }
                        getPreferences().edit(commit = true) { putBoolean("is_test_running", false) }
                    }
                    testJob = null
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    stopSelfResult(lastStartId)
                    val results = snapshot(
                        if (run.prepared) run.tested + carriedStrategies else emptyList()
                    )
                    carriedStrategies = emptyList()
                    mutableState.value = if (run.prepared) TestState(
                        runId = previous.runId,
                        isLoaded = true,
                        strategies = results,
                        failed = run.failed,
                        wasInterrupted = state.value.wasInterrupted,
                        isGenetic = genetic,
                        generation = state.value.generation,
                        generationSize = state.value.generationSize,
                        bestStrategies = state.value.bestStrategies,
                    ) else previous.copy(failed = run.failed, carriedStrategies = emptyList())
                }
            }
        }
        testJob?.start()
    }

    private suspend fun runSingle(run: TestRun, options: TestSettings, appendResults: Boolean) {
        val strategies = options.commands.map { StrategyResult(command = it) }.toMutableList()
        run.tested.addAll(strategies)
        run.lastGeneration = strategies
        if (appendResults) {
            carriedStrategies = state.value.strategies.filter { it.command !in options.commands }
        }
        publish(run, strategies, 0)
        ServiceManager.waitStop()
        val configurations = withContext(Dispatchers.IO) {
            options.commands.map { command ->
                runCatching { testConfiguration(this@TestService, command, options.host, options.port) }
            }
        }

        for ((index, strategy) in strategies.withIndex()) {
            currentCoroutineContext().ensureActive()
            run.currentStrategy = strategy
            strategy.totalRequests = options.sites.size * options.requestsCount
            publish(run, strategies, index + 1)
            val configuration = configurations[index].getOrNull()
            if (configuration == null || !checkStrategy(run, strategy, strategies, configuration, options)) {
                resetStrategyResult(strategy, options)
            }
            strategy.isCompleted = true
            publish(run, strategies)
            saveResults(strategies + carriedStrategies)
            stopEngine()
            run.currentStrategy = null
            delay(options.delaySec * 500L)
        }
    }

    private suspend fun runGenetic(run: TestRun, options: TestSettings) {
        val config = loadGeneticSettings()
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = if (config.timeLimitMinutes > 0) {
            startedAt + config.timeLimitMinutes * 60_000L
        } else {
            0L
        }
        mutableState.value = state.value.copy(
            isGenetic = true,
            generation = 0,
            generationSize = 0,
            geneticDeadline = deadline,
        )

        val storedBest = state.value.bestStrategies.ifEmpty {
            withContext(Dispatchers.IO) { readResults(applicationContext, BEST_RESULTS_FILE) }
        }
        var best = mergeBest(storedBest, emptyList(), config.bestCount)
        mutableState.value = state.value.copy(bestStrategies = best)

        var generationCommands = options.commands
        var generation = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            if (generation > 0 && deadline > 0 && SystemClock.elapsedRealtime() >= deadline) break
            generation++
            val strategies = generationCommands.map { StrategyResult(command = it) }.toMutableList()
            run.tested.clear()
            run.tested.addAll(strategies)
            run.lastGeneration = strategies
            publish(run, strategies, 0, generation)
            ServiceManager.waitStop()

            val configurations = withContext(Dispatchers.IO) {
                strategies.map { strategy ->
                    runCatching { testConfiguration(this@TestService, strategy.command, options.host, options.port) }
                }
            }

            var stopped = false
            for ((index, strategy) in strategies.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) {
                    stopped = true
                    break
                }
                run.currentStrategy = strategy
                strategy.totalRequests = options.sites.size * options.requestsCount
                publish(run, strategies, index + 1, generation)
                val configuration = configurations[index].getOrNull()
                if (configuration == null || !checkStrategy(run, strategy, strategies, configuration, options)) {
                    resetStrategyResult(strategy, options)
                }
                strategy.isCompleted = true
                publish(run, strategies, index + 1, generation)
                saveResults(strategies)
                stopEngine()
                run.currentStrategy = null
                delay(options.delaySec * 500L)

                if (countFull(strategies) >= config.stopAtFull) {
                    stopped = true
                    break
                }
            }

            best = mergeBest(best, strategies.filter { it.isCompleted }, config.bestCount)
            if (best.isNotEmpty()) saveBest(best)
            mutableState.value = state.value.copy(
                bestStrategies = best,
                generationSize = strategies.size,
            )

            if (stopped) break
            if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) break

            val parents = strategies
                .filter { it.isCompleted }
                .sortedWith(RESULT_ORDER)
                .take(config.parents)
                .map { it.command }
            if (parents.isEmpty()) break

            val next = GeneticEngine.nextGeneration(
                parents = parents,
                additions = config.additions,
                deletions = config.deletions,
                changes = config.changes,
            )
            if (next.isEmpty()) break
            generationCommands = next
        }

        publish(run, run.lastGeneration.toList(), run.lastGeneration.size, generation)
    }

    private suspend fun checkStrategy(
        run: TestRun,
        strategy: StrategyResult,
        strategies: List<StrategyResult>,
        configuration: Configuration,
        settings: TestSettings,
    ): Boolean {
        return try {
            val current = startEngine(configuration)
            supervisorScope {
                val engineExit = async { current.awaitExit() }
                val check = async {
                    delay(settings.delaySec * 500L)
                    val host = when (configuration.host) {
                        "0.0.0.0" -> "127.0.0.1"
                        "::", "[::]" -> "::1"
                        else -> configuration.host
                    }
                    SiteCheckUtils(host, configuration.port).checkSitesAsync(
                        sites = settings.sites,
                        requestsCount = settings.requestsCount,
                        requestTimeout = settings.requestTimeout,
                        concurrentRequests = settings.requestLimit,
                        fullLog = true,
                        onSiteChecked = { site, successCount, countRequests ->
                            withContext(Dispatchers.Main.immediate) {
                                strategy.currentProgress += countRequests
                                strategy.successCount += successCount
                                strategy.siteResults.add(SiteResult(site, successCount, countRequests))
                                publish(run, strategies)
                            }
                        }
                    )
                    true
                }
                try {
                    select {
                        engineExit.onAwait { false }
                        check.onAwait { it }
                    }
                } finally {
                    engineExit.cancel()
                    check.cancel()
                }
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (state.value.isStopping) throw CancellationException("Proxy test stopped")
            Log.e(TAG, "Proxy test engine failed", e)
            Toast.makeText(this, getString(R.string.failed_to_start, Sender.Proxy.name), Toast.LENGTH_SHORT).show()
            false
        }
    }

    private suspend fun startEngine(configuration: Configuration): NativeEngine {
        if (state.value.isStopping) throw CancellationException("Proxy test stopped")
        val current = NativeEngine(applicationContext, Mode.Proxy, foreground = false)
        engine = current
        current.start(configuration)
        return current
    }

    private suspend fun stopEngine() = withContext(NonCancellable) {
        engineMutex.withLock {
            val current = engine ?: return@withLock
            var stopFailed = false
            while (true) {
                try {
                    current.stop()
                    engine = null
                    break
                } catch (e: Exception) {
                    if (!stopFailed) Log.e(TAG, "Failed to stop proxy test engine", e)
                    stopFailed = true
                    delay(500)
                }
            }
        }
    }

    private fun loadSettings(testCommands: List<String>?): TestSettings {
        val prefs = getPreferences()
        val sniValue = prefs.getStringNotNull("byedpi_proxytest_sni", "google.com")
        val content = if (testCommands != null) {
            testCommands.joinToString("\n")
        } else {
            val userCommands = prefs.getBoolean("byedpi_proxytest_usercommands", false)
            if (userCommands) prefs.getStringNotNull("byedpi_proxytest_commands", "")
            else assets.open("proxytest_strategies.list").bufferedReader().use { it.readText() }
        }
        val commands = content.replace("{sni}", "\"${sniValue}\"").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val host = prefs.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val port = prefs.getIntStringNotNull("byedpi_proxy_port", 1080)
        val delaySec = prefs.getIntStringNotNull("byedpi_proxytest_delay", 1).coerceAtLeast(0)
        val requestsCount = prefs.getIntStringNotNull("byedpi_proxytest_requests", 1).coerceAtLeast(1)
        val requestTimeout = prefs.getLongStringNotNull("byedpi_proxytest_timeout", 5).coerceAtLeast(1)
        val requestLimit = prefs.getIntStringNotNull("byedpi_proxytest_limit", 20).coerceAtLeast(1)
        DomainListUtils.syncLists(this)
        return TestSettings(DomainListUtils.getActiveDomains(this).toList(), commands, host, port,
            delaySec, requestsCount, requestTimeout, requestLimit)
    }

    private fun loadGeneticSettings(): GeneticSettings {
        val prefs = getPreferences()
        return GeneticSettings(
            parents = prefs.getIntStringNotNull("byedpi_proxytest_gen_n", 5).coerceIn(1, 500),
            additions = prefs.getIntStringNotNull("byedpi_proxytest_gen_m", 3).coerceIn(0, 500),
            deletions = prefs.getIntStringNotNull("byedpi_proxytest_gen_d", 1).coerceIn(0, 500),
            changes = prefs.getIntStringNotNull("byedpi_proxytest_gen_r", 2).coerceIn(0, 500),
            timeLimitMinutes = prefs.getIntStringNotNull("byedpi_proxytest_gen_minutes", 120).coerceIn(0, 525600),
            stopAtFull = prefs.getIntStringNotNull("byedpi_proxytest_gen_stop_k", 3).coerceIn(1, 10000),
            bestCount = prefs.getIntStringNotNull("byedpi_proxytest_gen_best_k", 10).coerceIn(1, 10000),
        )
    }

    private fun resetStrategyResult(strategy: StrategyResult, settings: TestSettings) {
        strategy.successCount = 0
        strategy.currentProgress = 0
        strategy.siteResults.clear()
        strategy.siteResults.addAll(settings.sites.map { SiteResult(it, 0, settings.requestsCount) })
    }

    private fun snapshot(strategies: List<StrategyResult>): List<StrategyResult> =
        strategies.map { it.copy(siteResults = it.siteResults.toMutableList()) }

    private suspend fun saveResults(strategies: List<StrategyResult>) {
        saveResultsToFile(strategies, RESULTS_FILE)
    }

    private suspend fun saveBest(strategies: List<StrategyResult>) {
        saveResultsToFile(strategies, BEST_RESULTS_FILE)
    }

    private suspend fun saveResultsToFile(strategies: List<StrategyResult>, fileName: String) {
        val results = snapshot(strategies)
        withContext(Dispatchers.IO) {
            synchronized(resultsLock) {
                val file = AtomicFile(File(filesDir, fileName))
                val output = file.startWrite()
                try {
                    output.write(gson.toJson(results).toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    throw e
                }
            }
        }
    }

    private fun saveGeneticPopulation(strategies: List<StrategyResult>) {
        if (strategies.isEmpty()) return
        val commands = strategies.joinToString("\n") { it.command }
        getPreferences().edit(commit = true) {
            putString("byedpi_proxytest_commands", commands)
            putBoolean("byedpi_proxytest_usercommands", true)
        }
    }

    private fun mergeBest(
        existing: List<StrategyResult>,
        tested: List<StrategyResult>,
        limit: Int,
    ): List<StrategyResult> {
        val merged = LinkedHashMap<String, StrategyResult>()
        (existing + tested).forEach { candidate ->
            val current = merged[candidate.command]
            if (current == null || RESULT_ORDER.compare(candidate, current) < 0) {
                merged[candidate.command] = candidate
            }
        }
        return merged.values
            .sortedWith(RESULT_ORDER)
            .take(limit)
            .map { it.copy(siteResults = it.siteResults.toMutableList()) }
    }

    private fun countFull(strategies: List<StrategyResult>): Int =
        strategies.count { it.isCompleted && it.totalRequests > 0 && it.successCount >= it.totalRequests }

    private fun publish(
        run: TestRun,
        strategies: List<StrategyResult>,
        currentStrategy: Int = state.value.currentStrategy,
        generation: Int = state.value.generation,
    ) {
        mutableState.value = state.value.copy(
            isLoaded = true,
            strategies = snapshot(strategies),
            carriedStrategies = carriedStrategies,
            currentStrategy = currentStrategy,
            generation = generation,
            generationSize = strategies.size,
            isGenetic = run.genetic,
        )
        val completed = strategies.count { it.isCompleted }
        if (!destroyed && (
            notifiedStrategy != currentStrategy ||
                notifiedCompleted != completed ||
                notifiedGeneration != generation
            )
        ) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
            notifiedStrategy = currentStrategy
            notifiedCompleted = completed
            notifiedGeneration = generation
        }
    }

    private fun notification(): Notification {
        val value = state.value
        val text = if (value.isGenetic) {
            getString(R.string.test_process_genetic, value.generation, value.currentStrategy, value.strategies.size)
        } else {
            getString(R.string.test_process, value.currentStrategy, value.strategies.size)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.title_test))
            .setContentText(text)
            .setProgress(value.strategies.size, value.strategies.count { it.isCompleted }, value.currentStrategy == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, TestActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, getString(R.string.test_stop), PendingIntent.getService(this, 0,
                Intent(this, TestService::class.java).setAction(STOP_ACTION), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        notifiedStrategy = state.value.currentStrategy
        notifiedCompleted = state.value.strategies.count { it.isCompleted }
        notifiedGeneration = state.value.generation
    }
}
