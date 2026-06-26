package com.example.ui.viewmodel

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.IpoMaster
import com.example.data.model.*
import com.example.data.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

class BulkIpoViewModel(private val repository: IpoRepository) : ViewModel() {

    val ipos: StateFlow<List<IpoMaster>> = repository.ipoMasterList
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val readyToCheckIpos: StateFlow<List<IpoMaster>> = ipos.map { list ->
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        list.filter { 
            it.status.equals("Allotted", true) || 
            (it.allotmentDate != null && it.allotmentDate <= today)
        }.sortedWith(
            compareByDescending<IpoMaster> { it.allotmentDate ?: "" }
            .thenByDescending { it.updatedAt }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedIpo = MutableStateFlow<IpoMaster?>(null)
    val selectedIpo = _selectedIpo.asStateFlow()

    val boids: StateFlow<List<BoidEntry>> = repository.allBoids
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _results = MutableStateFlow<List<BulkIpoResult>>(emptyList())
    val results = _results.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking = _isChecking.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage = _syncMessage.asStateFlow()

    val syncLog = repository.syncLog

    private val _enabledBoids = mutableStateMapOf<String, Boolean>()
    val enabledBoids: Map<String, Boolean> = _enabledBoids

    init {
        // Automatically select the first IPO if available
        viewModelScope.launch {
            ipos.collect { list ->
                if (list.isNotEmpty() && _selectedIpo.value == null) {
                    _selectedIpo.value = list.first()
                }
            }
        }
        
        // Sync if empty
        viewModelScope.launch {
            if (ipos.value.isEmpty()) {
                syncIpos()
            }
        }

        // Initialize enabled boids
        viewModelScope.launch {
            boids.collect { list ->
                list.forEach { 
                    if (!_enabledBoids.containsKey(it.boid)) {
                        _enabledBoids[it.boid] = it.isEnabledForBulk
                    }
                }
            }
        }
    }

    fun toggleBoidEnabled(boid: String) {
        val current = _enabledBoids[boid] ?: true
        _enabledBoids[boid] = !current
    }

    fun syncIpos(force: Boolean = false) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Starting sync..."
            val result = repository.syncIpos(force)
            if (result.isSuccess) {
                _syncMessage.value = "Sync successful"
            } else {
                _syncMessage.value = "Sync failed"
            }
            _isSyncing.value = false
            delay(2000)
            _syncMessage.value = null
        }
    }

    fun selectIpo(ipo: IpoMaster) {
        _selectedIpo.value = ipo
    }

    fun updateResultPortalId(companyName: String, id: Int) {
        viewModelScope.launch {
            val ipo = ipos.value.find { it.companyName == companyName }
            if (ipo != null) {
                val updated = ipo.copy(resultPortalId = id, updatedAt = System.currentTimeMillis())
                repository.updateIpo(updated)
                _selectedIpo.value = updated
                _syncMessage.value = "Updated Result Portal ID for ${ipo.companyName}"
            }
        }
    }

    fun updateAllotmentDate(companyName: String, date: String) {
        viewModelScope.launch {
            val ipo = ipos.value.find { it.companyName == companyName }
            if (ipo != null) {
                val updated = ipo.copy(allotmentDate = date, updatedAt = System.currentTimeMillis())
                repository.updateIpo(updated)
                _selectedIpo.value = updated
                _syncMessage.value = "Allotment date set for ${ipo.companyName}"
            }
        }
    }

    fun setDefaultBoid(boid: String) {
        viewModelScope.launch {
            repository.setDefaultBoid(boid)
        }
    }

    private val _searchingIpos = mutableStateMapOf<String, Boolean>()
    val searchingIpos: Map<String, Boolean> = _searchingIpos

    private var discoveryJob: Job? = null

    fun discoverResultPortalId(ipo: IpoMaster) {
        // Individual item search
        _searchingIpos[ipo.companyName] = true
        
        viewModelScope.launch {
            try {
                val foundId = repository.discoverResultPortalId(ipo.companyName)
                if (foundId != null) {
                    updateResultPortalId(ipo.companyName, foundId)
                    _syncMessage.value = "Found ID for ${ipo.companyName}: $foundId"
                } else {
                    _syncMessage.value = "Could not auto-find ID for ${ipo.companyName}"
                }
            } catch (e: CancellationException) {
                // ignore
            } finally {
                _searchingIpos.remove(ipo.companyName)
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
    }

    fun addBoid(name: String, boid: String) {
        viewModelScope.launch {
            repository.addBoid(name, boid)
        }
    }

    fun removeBoid(boid: BoidEntry) {
        viewModelScope.launch {
            repository.removeBoid(boid.boid)
        }
    }

    fun addMultipleBoids(pastedText: String) {
        viewModelScope.launch {
            val lines = pastedText.lines()
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                
                val boidRegex = Regex("\\b\\d{16}\\b")
                val match = boidRegex.find(line)
                
                if (match != null) {
                    val boid = match.value
                    var name = line.replace(boid, "").replace(Regex("[,:|\\t]"), " ").trim()
                    
                    if (name.isBlank()) {
                        name = "User_${boid.takeLast(4)}"
                    }
                    
                    if (boids.value.none { it.boid == boid }) {
                        repository.addBoid(name, boid)
                    }
                }
            }
        }
    }

    private val _isHybridChecking = MutableStateFlow(false)
    val isHybridChecking = _isHybridChecking.asStateFlow()

    fun startBulkCheck(hybrid: Boolean = true) {
        val ipo = _selectedIpo.value ?: run {
            showTemporaryMessage("Please select an IPO first")
            return
        }

        if (hybrid) {
            _isHybridChecking.value = true
            _isChecking.value = true // Keep legacy flag for UI backward compatibility
            return
        }
        
        com.example.data.util.AppLogger.i("IpoCheck", "Legacy Check Results button pressed")
        val companyId = ipo.resultPortalId ?: run {
            showTemporaryMessage("Company ID missing. Click the warning to enter it.")
            return
        }
        val currentBoids = boids.value.filter { _enabledBoids[it.boid] != false }
        if (currentBoids.isEmpty()) {
            showTemporaryMessage("No enabled BOIDs to check.")
            return
        }

        viewModelScope.launch {
            _isChecking.value = true
            val initialResults = currentBoids.map { BulkIpoResult(it, isChecking = true) }
            _results.value = initialResults

            repository.bulkCheckIpoResults(companyId, currentBoids) { index, result ->
                viewModelScope.launch {
                    val updatedList = _results.value.toMutableList()
                    if (index < updatedList.size) {
                        result.onSuccess {
                            updatedList[index] = updatedList[index].copy(result = it, isChecking = false)
                        }
                        result.onFailure {
                            updatedList[index] = updatedList[index].copy(error = it.message, isChecking = false)
                        }
                        _results.value = updatedList
                    }
                }
            }
            _isChecking.value = false
        }
    }

    fun onHybridResultReceived(boidEntry: BoidEntry, message: String, success: Boolean) {
        val currentResults = _results.value.toMutableList()
        val existingIndex = currentResults.indexOfFirst { it.boidEntry.boid == boidEntry.boid }
        
        val newResult = BulkIpoResult(
            boidEntry = boidEntry,
            result = IpoResultResponse(success, message),
            isChecking = false
        )
        
        if (existingIndex != -1) {
            currentResults[existingIndex] = newResult
        } else {
            currentResults.add(newResult)
        }
        _results.value = currentResults
    }

    fun finishHybridCheck() {
        _isHybridChecking.value = false
        _isChecking.value = false
    }

    private fun showTemporaryMessage(msg: String) {
        viewModelScope.launch {
            _syncMessage.value = msg
            delay(3000)
            if (_syncMessage.value == msg) _syncMessage.value = null
        }
    }

    fun startCheckAllRecentResults() {
        com.example.data.util.AppLogger.i("IpoCheck", "Check All Recent Results button pressed")
        val recentIposWithId = ipos.value.filter { it.resultPortalId != null }.take(15)
        val currentBoids = boids.value
        
        if (recentIposWithId.isEmpty()) {
            showTemporaryMessage("No recent IPOs with Result Portal IDs found. Sync first.")
            return
        }
        if (currentBoids.isEmpty()) {
            showTemporaryMessage("No BOIDs added to check.")
            return
        }

        viewModelScope.launch {
            _isChecking.value = true
            _results.value = emptyList()
            
            val boidResults = currentBoids.map { BulkIpoResult(it, isChecking = true) }.toMutableList()
            _results.value = boidResults

            for (ipo in recentIposWithId) {
                _syncMessage.value = "Checking: ${ipo.companyName}..."
                
                repository.bulkCheckIpoResults(ipo.resultPortalId!!, currentBoids) { index, result ->
                    viewModelScope.launch {
                        val currentList = _results.value.toMutableList()
                        result.onSuccess { res ->
                            val existing = currentList[index]
                            
                            // If allotted, we definitely want to show it. 
                            // If multiple are allotted, we aggregate them in the message.
                            if (res.success) {
                                val currentMsg = existing.result?.message ?: ""
                                val newMsg = if (currentMsg.contains("✓")) {
                                    "$currentMsg\n✓ ${ipo.scrip ?: ipo.companyName.take(8)}: ${res.message}"
                                } else {
                                    "✓ ${ipo.scrip ?: ipo.companyName.take(8)}: ${res.message}"
                                }
                                
                                currentList[index] = existing.copy(
                                    result = res.copy(message = newMsg),
                                    isChecking = false
                                )
                                _results.value = currentList
                            } else if (existing.result == null || !existing.result.success) {
                                // Only show Not Allotted if we haven't found any Allotted yet for this BOID
                                // Or maybe show the most recent "Not Allotted"
                                val currentMsg = existing.result?.message ?: ""
                                val newMsg = if (currentMsg.isEmpty()) {
                                    "✗ ${ipo.scrip ?: ipo.companyName.take(8)}: Not Allotted"
                                } else {
                                    currentMsg // Keep existing (might be another Not Allotted or an Allotted)
                                }
                                currentList[index] = existing.copy(
                                    result = res.copy(message = newMsg),
                                    isChecking = false
                                )
                                _results.value = currentList
                            }
                        }
                    }
                }
                delay(1200) // Moderate delay to avoid trigger WAF
            }
            
            _results.value = _results.value.map { it.copy(isChecking = false) }
            _isChecking.value = false
            _syncMessage.value = "Bulk Check Complete"
            delay(2000)
            _syncMessage.value = null
        }
    }

    fun clearResults() {
        _results.value = emptyList()
    }
}

class BulkIpoViewModelFactory(private val repository: IpoRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BulkIpoViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BulkIpoViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
