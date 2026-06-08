package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.BoidEntry
import com.example.data.model.BulkIpoResult
import com.example.data.model.IpoCompany
import com.example.data.repository.IpoRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
        loadCompanies()
    }

    fun loadCompanies() {
        viewModelScope.launch {
            repository.getCompanyList().onSuccess {
                _companies.value = it
                if (it.isNotEmpty() && _selectedCompany.value == null) {
                    _selectedCompany.value = it.first()
                }
            }
        }
    }

    fun selectCompany(company: IpoCompany) {
        _selectedCompany.value = company
    }

    fun addBoid(name: String, boid: String) {
        val newBoids = _boids.value + BoidEntry(name = name, boid = boid)
        _boids.value = newBoids
    }

    fun removeBoid(boidEntry: BoidEntry) {
        _boids.value = _boids.value.filter { it.id != boidEntry.id }
    }

    fun startBulkCheck() {
        val companyId = _selectedCompany.value?.id ?: return
        val currentBoids = _boids.value
        if (currentBoids.isEmpty()) return

        _results.value = currentBoids.map { BulkIpoResult(it) }
        _isChecking.value = true

        viewModelScope.launch {
            val semaphore = Semaphore(3)
            currentBoids.forEachIndexed { index, boidEntry ->
                launch {
                    semaphore.withPermit {
                        updateResult(index) { it.copy(isChecking = true) }
                        delay(200) 
                        repository.checkResult(boidEntry.boid, companyId)
                            .onSuccess { res ->
                                updateResult(index) { it.copy(result = res, isChecking = false) }
                            }
                            .onFailure { err ->
                                updateResult(index) { it.copy(error = err.message, isChecking = false) }
                            }
                    }
                }
            }
        }
    }

    private fun updateResult(index: Int, transform: (BulkIpoResult) -> BulkIpoResult) {
        val currentList = _results.value.toMutableList()
        if (index in currentList.indices) {
            currentList[index] = transform(currentList[index])
            _results.value = currentList
            
            if (currentList.none { it.isChecking }) {
                _isChecking.value = false
            }
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
