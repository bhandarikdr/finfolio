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
import kotlinx.coroutines.launch

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
            repository.syncIpos()
            _isSyncing.value = false
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
            // Split by lines, commas, or spaces
            val lines = pastedText.split(Regex("[\n, ]+"))
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.length == 16 && trimmed.all { it.isDigit() }) {
                    // Check if already exists in boids
                    if (boids.value.none { it.boid == trimmed }) {
                        repository.addBoid("User_${trimmed.takeLast(4)}", trimmed)
                    }
                }
            }
        }
    }

    fun startBulkCheck() {
        val ipo = _selectedIpo.value ?: return
        val currentBoids = boids.value
        if (currentBoids.isEmpty()) return

        viewModelScope.launch {
            _isChecking.value = true
            val initialResults = currentBoids.map { BulkIpoResult(it, isChecking = true) }
            _results.value = initialResults

            repository.bulkCheckIpoResults(ipo.cdscCompanyId, currentBoids) { index, result ->
                val updatedList = _results.value.toMutableList()
                result.onSuccess {
                    updatedList[index] = updatedList[index].copy(result = it, isChecking = false)
                }
                result.onFailure {
                    updatedList[index] = updatedList[index].copy(error = it.message, isChecking = false)
                }
                _results.value = updatedList
            }
            _isChecking.value = false
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
