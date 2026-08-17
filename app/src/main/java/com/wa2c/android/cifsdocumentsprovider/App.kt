package com.wa2c.android.cifsdocumentsprovider

import android.app.Application
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.wa2c.android.cifsdocumentsprovider.common.utils.initLog
import com.wa2c.android.cifsdocumentsprovider.domain.repository.AppRepository
import com.wa2c.android.cifsdocumentsprovider.presentation.worker.DixsuAutoStartWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class App: Application() {

    @Inject
    lateinit var repository: AppRepository

    override fun onCreate() {
        super.onCreate()

        initLog(BuildConfig.DEBUG)
        runBlocking {
            repository.migrate()
        }

        WorkManager.getInstance(this).enqueue(OneTimeWorkRequest.Builder(DixsuAutoStartWorker::class.java).build())
    }
}
