package com.example.ui.viewmodel

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class BulkIpoViewModel(private val repository: IpoRepository) : ViewModel() {

    val ipos: StateFlow<List<IpoMaster>> = repository.ipoMasterList
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    }

    fun syncIpos() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Starting sync..."
            val result = repository.syncIpos()
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

    fun updateCdscId(companyName: String, id: Int) {
        viewModelScope.launch {
            val ipo = ipos.value.find { it.companyName == companyName }
            if (ipo != null) {
                val updated = ipo.copy(cdscCompanyId = id, updatedAt = System.currentTimeMillis())
                repository.updateIpo(updated)
                _selectedIpo.value = updated
                _syncMessage.value = "Updated CDSC ID for ${ipo.companyName}"
            }
        }
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

    fun startBulkCheck() {
        val ipo = _selectedIpo.value ?: run {
            showTemporaryMessage("Please select an IPO first")
            return
        }
        val companyId = ipo.cdscCompanyId ?: run {
            showTemporaryMessage("Company ID missing. Click the warning to enter it.")
            return
        }
        val currentBoids = boids.value
        if (currentBoids.isEmpty()) {
            showTemporaryMessage("No BOIDs added to check.")
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

    private fun showTemporaryMessage(msg: String) {
        viewModelScope.launch {
            _syncMessage.value = msg
            delay(3000)
            if (_syncMessage.value == msg) _syncMessage.value = null
        }
    }

    fun startDeepScan() {
        val recentIposWithId = ipos.value.filter { it.cdscCompanyId != null }.take(8)
        val currentBoids = boids.value
        
        if (recentIposWithId.isEmpty()) {
            showTemporaryMessage("No recent IPOs with CDSC IDs found. Sync first.")
            return
        }
        if (currentBoids.isEmpty()) {
            showTemporaryMessage("No BOIDs added to scan.")
            return
        }

        viewModelScope.launch {
            _isChecking.value = true
            _results.value = emptyList()
            val finalResults = mutableListOf<BulkIpoResult>()
            
            // Initialize results with "Scanning..." state
            currentBoids.forEach { boid ->
                finalResults.add(BulkIpoResult(boid, isChecking = true))
            }
            _results.value = finalResults

            for (ipo in recentIposWithId) {
                _syncMessage.value = "Checking ${ipo.companyName}..."
                
                // We use a latch or similar to wait for each IPO's bulk check to finish
                // OR we just process them. Since bulkCheckIpoResults uses coroutines internally, 
                // we can just call it and wait.
                
                repository.bulkCheckIpoResults(ipo.cdscCompanyId!!, currentBoids) { index, result ->
                    viewModelScope.launch {
                        val currentList = _results.value.toMutableList()
                        result.onSuccess { res ->
                            if (res.success) {
                                // If allotted, we update the result. If already allotted from another IPO, 
                                // we might want to append? For now, just show the first success.
                                if (currentList[index].result?.success != true) {
                                    val formattedMsg = "${ipo.scrip ?: ipo.companyName.take(10)}: ${res.message}"
                                    currentList[index] = currentList[index].copy(
                                        result = res.copy(message = formattedMsg),
                                        isChecking = false
                                    )
                                }
                            }
                        }
                        // On failure or not allotted, we don't necessarily want to overwrite a success 
                        // from a previous IPO in the loop.
                        _results.value = currentList
                    }
                }
                delay(1000) // Pause between companies to be nice to the WAF
            }
            
            // Mark remaining as not checking
            _results.value = _results.value.map { it.copy(isChecking = false) }
            _isChecking.value = false
            _syncMessage.value = "Deep Scan Complete"
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
