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
        .map { list -> 
            // Show all IPOs (Active and Archived), prioritizing those with result IDs
            list.sortedWith(compareByDescending<IpoMaster> { it.resultAvailable }.thenByDescending { it.openingDate })
        }
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
                
                // Try to find a 16-digit BOID in the line
                val boidRegex = Regex("\\b\\d{16}\\b")
                val match = boidRegex.find(line)
                
                if (match != null) {
                    val boid = match.value
                    // Extract name: everything before or after the BOID, minus common separators
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
        val ipo = _selectedIpo.value ?: return
        val companyId = ipo.cdscCompanyId ?: return
        val currentBoids = boids.value
        if (currentBoids.isEmpty()) return

        viewModelScope.launch {
            _isChecking.value = true
            val initialResults = currentBoids.map { BulkIpoResult(it, isChecking = true) }
            _results.value = initialResults

            repository.bulkCheckIpoResults(companyId, currentBoids) { index, result ->
                viewModelScope.launch {
                    val updatedList = _results.value.toMutableList()
                    result.onSuccess {
                        updatedList[index] = updatedList[index].copy(result = it, isChecking = false)
                    }
                    result.onFailure {
                        updatedList[index] = updatedList[index].copy(error = it.message, isChecking = false)
                    }
                    _results.value = updatedList
                }
            }
            _isChecking.value = false
        }
    }

    fun clearResults() {
        _results.value = emptyList()
    }

    fun toggleIpoActive(ipo: IpoMaster) {
        viewModelScope.launch {
            repository.updateIpo(ipo.copy(isActive = !ipo.isActive))
        }
    }

    fun addManualIpo(name: String, code: String, id: Int) {
        viewModelScope.launch {
            repository.addIpo(IpoMaster(
                companyName = name,
                companyCode = code,
                cdscCompanyId = id,
                status = "Manual",
                source = "USER"
            ))
        }
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
