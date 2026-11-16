package com.simon.harmonichackernews.feed

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.simon.harmonichackernews.awaitHarmonicAppComposition
import com.simon.harmonichackernews.feed.refresh.FeedRefreshOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel as cancelScope
import kotlinx.coroutines.launch

/** Periodic background refresh of subscribed feeds, including new-article alerts. */
class FeedRefreshJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            try {
                // A background launch can be the first graph consumer; do not block the
                // callback on preference migration or platform initialization.
                val app = awaitHarmonicAppComposition()
                val runtime = app.feedRefresh
                runtime.reschedule()
                val outcome = runtime.refreshInBackground()
                val failed = outcome is FeedRefreshOutcome.Synced && outcome.failedFeeds > 0
                jobFinished(params, failed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("FeedRefresh", "Unable to refresh feeds", error)
                jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        refreshJob?.cancel()
        refreshJob = null
        return true
    }

    override fun onDestroy() {
        serviceScope.cancelScope()
        super.onDestroy()
    }
}