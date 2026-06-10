package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.ScripMaster
import com.example.data.repository.MarketRepository
import com.example.data.repository.NepseIndex
import com.example.data.repository.ScripPriceChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MarketViewModel(private val repository: MarketRepository) : ViewModel() {

    val allScripMaster: StateFlow<List<ScripMaster>> = repository.allScripMaster
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val wishlistedScrips: StateFlow<List<ScripMaster>> = repository.wishlistedScrips
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val indices: StateFlow<List<NepseIndex>> = repository.persistedIndices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _visibleIndices = MutableStateFlow<Set<String>>(
        setOf("NEPSE Index", "Sensitive Index", "Float Index", "Banking", "HydroPower Index", "Life Insurance", "Microfinance Index")
    )
    val visibleIndices = _visibleIndices.asStateFlow()

    val filteredIndices: StateFlow<List<NepseIndex>> = combine(indices, _visibleIndices) { all, visible ->
        val fixed = all.filter { it.index.contains("NEPSE Index", true) }
        val others = all.filter { it.index !in fixed.map { f -> f.index } && it.index in visible }
        fixed + others
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _priceChanges = MutableStateFlow<List<ScripPriceChange>>(emptyList())
    val priceChanges = _priceChanges.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    init {
        refreshMarketData()
        syncMasterScrips()
    }

    fun refreshMarketData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.fetchNepseIndices()
                _priceChanges.value = repository.fetchPriceChanges()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun syncMasterScrips() {
        viewModelScope.launch {
            repository.fetchMasterScrips()
        }
    }

    fun toggleIndexVisibility(name: String) {
        val current = _visibleIndices.value.toMutableSet()
        if (current.contains(name)) current.remove(name) else current.add(name)
        _visibleIndices.value = current
    }

    fun toggleWishlist(scrip: ScripMaster) {
        viewModelScope.launch {
            repository.updateWishlist(scrip.symbol, !scrip.isWishlisted)
        }
    }
}

class MarketViewModelFactory(private val repository: MarketRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MarketViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MarketViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
