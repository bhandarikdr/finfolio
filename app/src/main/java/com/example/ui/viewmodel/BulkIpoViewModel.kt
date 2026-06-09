package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class BulkIpoViewModel(private val repository: IpoRepository) : ViewModel() {

    private val _companies = MutableStateFlow<List<IpoCompany>>(emptyList())
    val companies = _companies.asStateFlow()

    private val _selectedCompany = MutableStateFlow<IpoCompany?>(null)
    val selectedCompany = _selectedCompany.asStateFlow()

    private val _boids = MutableStateFlow<List<BoidEntry>>(emptyList())
    val boids = _boids.asStateFlow()

    private val _results = MutableStateFlow<List<BulkIpoResult>>(emptyList())
    val results = _results.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking = _isChecking.asStateFlow()

    init {
        fetchCompanies()
    }

    private fun fetchCompanies() {
        viewModelScope.launch {
            repository.fetchIpoCompanies().onSuccess {
                _companies.value = it
            }
        }
    }

    fun selectCompany(company: IpoCompany) {
        _selectedCompany.value = company
    }

    fun addBoid(name: String, boid: String) {
        val current = _boids.value.toMutableList()
        current.add(BoidEntry(name, boid))
        _boids.value = current
    }

    fun removeBoid(boid: BoidEntry) {
        val current = _boids.value.toMutableList()
        current.remove(boid)
        _boids.value = current
    }

    fun startBulkCheck() {
        val company = _selectedCompany.value ?: return
        val currentBoids = _boids.value
        if (currentBoids.isEmpty()) return

        viewModelScope.launch {
            _isChecking.value = true
            val initialResults = currentBoids.map { BulkIpoResult(it, isChecking = true) }
            _results.value = initialResults

            initialResults.forEachIndexed { index, boidResult ->
                val result = repository.checkIpoResult(company.id, boidResult.boidEntry.boid)
                val updatedList = _results.value.toMutableList()
                
                result.onSuccess {
                    updatedList[index] = updatedList[index].copy(result = it, isChecking = false)
                }
                result.onFailure {
                    updatedList[index] = updatedList[index].copy(error = it.message, isChecking = false)
                }
                
                _results.value = updatedList
                delay(300) // Safe delay between requests
            }
            _isChecking.value = false
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
