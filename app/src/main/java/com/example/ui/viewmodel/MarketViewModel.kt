package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.ScripMaster
import com.example.data.repository.MarketRepository
import com.example.data.repository.NepseIndex
import com.example.data.repository.PortfolioRepository
import com.example.data.repository.ScripPriceChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MarketViewModel(private val repository: MarketRepository, private val portfolioRepository: PortfolioRepository) : ViewModel() {

    val allScripMaster: StateFlow<List<ScripMaster>> = repository.allScripMaster
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val wishlistedScrips: StateFlow<List<ScripMaster>> = repository.wishlistedScrips
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val indices: StateFlow<List<NepseIndex>> = repository.persistedIndices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val visibleIndices: StateFlow<Set<String>> = portfolioRepository.userProfile.map { profile ->
        if (profile.visibleIndices.isEmpty()) {
            setOf("NEPSE Index", "Sensitive Index", "Float Index", "Banking", "HydroPower Index", "Life Insurance", "Microfinance Index")
        } else {
            profile.visibleIndices.toSet()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val filteredIndices: StateFlow<List<NepseIndex>> = combine(indices, visibleIndices) { all, visible ->
        if (all.isEmpty()) {
            // Provide placeholders with 0.0 values while loading
            setOf("NEPSE Index", "Sensitive Index", "Float Index", "Banking", "HydroPower Index", "Life Insurance", "Microfinance Index")
                .map { NepseIndex(it, 0.0, 0.0, 0.0) }
        } else {
            val fixed = all.filter { it.index.equals("NEPSE Index", true) }
            val others = all.filter { it.index !in fixed.map { f -> f.index } && 
                (it.index in visible || it.index.replace(" SubIndex", "").replace(" Index", "") in visible) }
            (fixed + others).distinctBy { it.index }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val priceChanges: StateFlow<List<ScripPriceChange>> = portfolioRepository.allExternalLtps.map { list ->
        list.map { 
            ScripPriceChange(
                symbol = it.symbol,
                ltp = it.ltp,
                change = it.ltp - it.previousLtp,
                percentChange = if (it.previousLtp > 0) (it.ltp - it.previousLtp) / it.previousLtp * 100.0 else 0.0,
                previousLtp = it.previousLtp
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchlistMovers: StateFlow<List<ScripPriceChange>> = combine(wishlistedScrips, priceChanges) { wish, changes ->
        wish.map { s ->
            val live = changes.find { it.symbol.equals(s.symbol, true) }
            live ?: ScripPriceChange(
                symbol = s.symbol,
                ltp = 0.0,
                change = 0.0,
                percentChange = 0.0,
                previousLtp = 0.0
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                repository.fetchPriceChanges()
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
        viewModelScope.launch {
            val current = visibleIndices.value.toMutableSet()
            if (current.contains(name)) current.remove(name) else current.add(name)
            portfolioRepository.updateVisibleIndices(current.toList())
        }
    }

    fun toggleWishlist(scrip: ScripMaster) {
        viewModelScope.launch {
            repository.updateWishlist(scrip.symbol, !scrip.isWishlisted)
        }
    }
}

class MarketViewModelFactory(private val repository: MarketRepository, private val portfolioRepository: PortfolioRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MarketViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MarketViewModel(repository, portfolioRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
