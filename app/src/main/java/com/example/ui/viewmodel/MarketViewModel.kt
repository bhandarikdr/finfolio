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

    private val _indices = MutableStateFlow<List<NepseIndex>>(emptyList())
    val indices = _indices.asStateFlow()

    private val _visibleIndices = MutableStateFlow<Set<String>>(
        setOf("NEPSE Index", "Sensitive Index", "Float Index", "Banking", "HydroPower Index", "Life Insurance", "Microfinance Index")
    )
    val visibleIndices = _visibleIndices.asStateFlow()

    val filteredIndices: StateFlow<List<NepseIndex>> = combine(_indices, _visibleIndices) { all, visible ->
        if (visible.isEmpty()) all else all.filter { visible.contains(it.index) || it.index.contains("NEPSE", true) }
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
                val fetchedIndices = repository.fetchNepseIndices()
                val fetchedPriceChanges = repository.fetchPriceChanges()
                
                android.util.Log.d("MarketViewModel", "Fetched ${fetchedIndices.size} indices")
                android.util.Log.d("MarketViewModel", "Fetched ${fetchedPriceChanges.size} price changes")
                
                _indices.value = fetchedIndices
                _priceChanges.value = fetchedPriceChanges
            } catch (e: Exception) {
                android.util.Log.e("MarketViewModel", "Error refreshing market data", e)
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

    fun toggleIndexVisibility(indexName: String) {
        val current = _visibleIndices.value.toMutableSet()
        if (current.contains(indexName)) {
            current.remove(indexName)
        } else {
            current.add(indexName)
        }
        _visibleIndices.value = current
    }

    fun toggleWishlist(scrip: ScripMaster) {
        viewModelScope.launch {
            repository.updateWishlist(scrip.copy(isWishlisted = !scrip.isWishlisted))
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
