package com.wa2c.android.cifsdocumentsprovider.presentation.ui.edit

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wa2c.android.cifsdocumentsprovider.common.exception.EditException
import com.wa2c.android.cifsdocumentsprovider.common.utils.generateUUID
import com.wa2c.android.cifsdocumentsprovider.domain.model.ConnectionResult
import com.wa2c.android.cifsdocumentsprovider.domain.model.RemoteConnection
import com.wa2c.android.cifsdocumentsprovider.domain.repository.EditRepository
import com.wa2c.android.cifsdocumentsprovider.presentation.ext.MainCoroutineScope
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.EditScreenParamHost
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.EditScreenParamId
import com.wa2c.android.cifsdocumentsprovider.presentation.worker.DixsuProxyWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Edit Screen ViewModel
 */
@HiltViewModel
class EditViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val editRepository: EditRepository,
) : ViewModel(), CoroutineScope by MainCoroutineScope() {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    private val paramId: String? = savedStateHandle[EditScreenParamId]
    private val paramHost: String? = savedStateHandle[EditScreenParamHost]

    /** Current ID */
    private var currentId: String = paramId ?: generateUUID()

    /** Init connection */
    private var initConnection: RemoteConnection = RemoteConnection.INVALID_CONNECTION

    /** Current RemoteConnection */
    val remoteConnection = MutableStateFlow<RemoteConnection>(initConnection)

    /** True if adding new settings */
    val isNew: Boolean
        get() = paramId.isNullOrEmpty()

    /** True if data changed */
    val isChanged: Boolean
        get() = isNew || initConnection != remoteConnection.value

    init {
        launch {
            val connection = paramId?.let {
                editRepository.getConnection(paramId)?.also { initConnection = it }
            } ?: RemoteConnection(id = currentId, name = paramHost ?: "", host = paramHost ?: "")
            remoteConnection.emit(connection)
        }
    }

    private val _navigateSearchHost = MutableSharedFlow<String>()
    val navigateSearchHost = _navigateSearchHost.asSharedFlow()

    private val _navigateSelectFolder = MutableSharedFlow<Result<RemoteConnection>>()
    val navigateSelectFolder = _navigateSelectFolder.asSharedFlow()

    private val _result = MutableSharedFlow<Result<Unit>>()
    val result = _result.asSharedFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _connectionResult = MutableSharedFlow<ConnectionResult?>()
    val connectionResult = channelFlow<ConnectionResult?> {
        launch { _connectionResult.collect { send(it) } }
        launch {
            var prevConnection = initConnection
            remoteConnection.collect {
                if (prevConnection != initConnection && it.isChangedConnection(prevConnection)) {
                    send(null)
                }
                prevConnection = it
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private val _keyCheckResult = MutableSharedFlow<Result<Unit>>()
    val keyCheckResult = _keyCheckResult.asSharedFlow()

    // first: grant permission uri / second: revoke permission uri
    private val _updatePermission = MutableSharedFlow<Pair<Uri?, Uri?>>()
    val updatePermission = _updatePermission.asSharedFlow()

    /** Local port of the standalone dixsu proxy, if currently running for this connection */
    private val _dixsuProxyPort = MutableStateFlow<Int?>(null)
    val dixsuProxyPort = _dixsuProxyPort.asStateFlow()

    /**
     * Start local SFTP proxy (for third-party backup/sync apps to connect to directly)
     */
    fun onClickStartDixsuProxy() {
        launch {
            _isBusy.emit(true)
            runCatching {
                val connection = remoteConnection.value
                val port = editRepository.startDixsuProxy(connection) ?: throw IllegalStateException("Not a dixsu-tunnel connection")
                val request = OneTimeWorkRequest.Builder(DixsuProxyWorker::class.java)
                    .setInputData(
                        workDataOf(
                            DixsuProxyWorker.KEY_CONNECTION_ID to connection.id,
                            DixsuProxyWorker.KEY_PORT to port,
                        )
                    )
                    .build()
                workManager.enqueueUniqueWork(DixsuProxyWorker.workerName(connection.id), ExistingWorkPolicy.REPLACE, request)
                port
            }.onSuccess {
                _dixsuProxyPort.emit(it)
            }.onFailure {
                _connectionResult.emit(ConnectionResult.Failure(cause = it))
            }
            _isBusy.emit(false)
        }
    }

    /**
     * Stop local SFTP proxy
     */
    fun onClickStopDixsuProxy() {
        launch {
            val connection = remoteConnection.value
            workManager.cancelUniqueWork(DixsuProxyWorker.workerName(connection.id))
            editRepository.stopDixsuProxy(connection.id)
            _dixsuProxyPort.emit(null)
        }
    }

    /**
     * Check connection
     */
    fun onClickCheckConnection(addKnownHost: Boolean = false) {
        launch {
            _isBusy.emit(true)
            runCatching {
                _connectionResult.emit(null)
                if (addKnownHost) {
                    editRepository.addKnownHost(remoteConnection.value)
                }
                editRepository.checkConnection(remoteConnection.value)
            }.fold(
                onSuccess = { _connectionResult.emit(it) },
                onFailure = { _connectionResult.emit(ConnectionResult.Failure(it)) }
            ).also {
                _isBusy.emit(false)
            }
        }
    }

    fun onClickSearchHost() {
        launch {
            _navigateSearchHost.emit(currentId)
        }
    }

    /**
     * Select Folder Click
     */
    fun onClickSelectFolder() {
        launch {
            _isBusy.emit(true)
            runCatching {
                val folderConnection = remoteConnection.value
                val result = editRepository.checkConnection(folderConnection)
                if (result !is ConnectionResult.Failure) {
                    editRepository.saveTemporaryConnection(folderConnection)
                    _navigateSelectFolder.emit(Result.success(folderConnection))
                } else {
                    _connectionResult.emit(result)
                }
            }.onFailure {
                _connectionResult.emit(ConnectionResult.Failure(cause = it))
            }.also {
                _isBusy.emit(false)
            }
        }
    }

    /**
     * Delete Click
     */
    fun onClickDelete() {
        launch {
            _isBusy.emit(true)
            runCatching {
                editRepository.deleteConnection(currentId)
            }.onSuccess {
                _result.emit(Result.success(Unit))
                _isBusy.emit(false)
            }.onFailure {
                _result.emit(Result.failure(it))
                _isBusy.emit(false)
            }
        }
    }

    /**
     * Save Click
     */
    fun onClickSave() {
        launch {
            _isBusy.emit(true)
            runCatching {
                remoteConnection.value.let { con ->
                    if (RemoteConnection.isInvalidConnectionId(con.id)) {
                        throw EditException.SaveCheck.InvalidIdException()
                    }
                    if (con.name.isEmpty() || con.host.isEmpty()) {
                        throw EditException.SaveCheck.InputRequiredException()
                    }
                    if (isNew && editRepository.getConnection(con.id) != null) {
                        throw EditException.SaveCheck.DuplicatedIdException()
                    }
                    editRepository.saveConnection(con)

                    // update permission
                    val grantPermissionUri = con.keyFileUri?.toUri()
                    _updatePermission.emit(grantPermissionUri to null) // not revoke permission

                    currentId = con.id
                    initConnection = con
                }
            }.onSuccess {
                _result.emit(Result.success(Unit))
            }.onFailure {
                _result.emit(Result.failure(it))
            }.also {
                _isBusy.emit(false)
            }
        }
    }

    /**
     * Select external key
     */
    fun selectKey(uri: Uri) {
        launch {
            _isBusy.emit(true)
            runCatching {
                editRepository.loadKeyFile(uri.toString()) // check key
            }.onSuccess {
                remoteConnection.emit(remoteConnection.value.copy(keyFileUri = uri.toString(), keyData = null))
                _keyCheckResult.emit(Result.success(Unit))
            }.onFailure {
                _keyCheckResult.emit(Result.failure(it))
            }.also {
                _isBusy.emit(false)
            }
        }
    }

    /**
     * Import external key
     */
    fun importKey(uri: Uri) {
        launch {
            _isBusy.emit(true)
            runCatching {
                editRepository.loadKeyFile(uri.toString())
            }.onSuccess {
                remoteConnection.emit(remoteConnection.value.copy(keyFileUri = null, keyData = it))
                _keyCheckResult.emit(Result.success(Unit))
            }.onFailure {
                _keyCheckResult.emit(Result.failure(it))
            }.also {
                _isBusy.emit(false)
            }
        }
    }

    /**
     * Input key
     */
    fun inputKey(key: String) {
        launch {
            _isBusy.emit(true)
            runCatching {
                editRepository.checkKey(key)
            }.onSuccess {
                remoteConnection.emit(remoteConnection.value.copy(keyFileUri = null, keyData = key))
                _keyCheckResult.emit(Result.success(Unit))
            }.onFailure {
                _keyCheckResult.emit(Result.failure(it))
            }.also {
                _isBusy.emit(false)
            }
        }
    }

    fun clearKey() {
        launch {
            remoteConnection.emit(remoteConnection.value.copy(keyFileUri = null, keyData = null))
        }
    }

    override fun onCleared() {
        runBlocking {
            editRepository.saveTemporaryConnection(null)
        }
        super.onCleared()
    }
}
