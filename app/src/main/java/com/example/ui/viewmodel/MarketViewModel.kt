package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.ScripMaster
import com.example.data.repository.MarketIndex
import com.example.data.repository.MarketRepository
import com.example.data.repository.PortfolioRepository
import com.example.data.repository.ScripPriceChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MarketViewModel(private val repository: MarketRepository, private val portfolioRepository: PortfolioRepository) : ViewModel() {

    val allScripMaster: StateFlow<List<ScripMaster>> = repository.allScripMaster
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val wishlistedScrips: StateFlow<List<ScripMaster>> = repository.wishlistedScrips
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val indices: StateFlow<List<MarketIndex>> = repository.persistedIndices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val visibleIndices: StateFlow<Set<String>> = portfolioRepository.userProfile.map { profile ->
        profile.visibleIndices.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val filteredIndices: StateFlow<List<MarketIndex>> = combine(indices, portfolioRepository.userProfile) { all, profile ->
        val primaryName = profile.primaryIndexName.ifBlank { "NEPSE Index" }
        val visible = profile.visibleIndices.toSet()
        
        val primaryMatch = all.find { it.index.equals(primaryName, true) } 
            ?: all.find { it.index.contains(primaryName, true) }
            ?: MarketIndex(primaryName, 0.0, 0.0, 0.0)
            
        // Other indices: Only if user has selected them and they are not the primary
        val others = all.filter { it.index != primaryMatch.index && it.index in visible }
        
        // Strict order: Primary first, then others
        (listOf(primaryMatch) + others).distinctBy { it.index }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val priceChanges: StateFlow<List<ScripPriceChange>> = portfolioRepository.allExternalLtps.map { list ->
        list.map { 
            ScripPriceChange(
                symbol = it.symbol,
                ltp = it.ltp,
                change = it.pointChange,
                percentChange = it.changePercent,
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

    fun refreshMarketData(force: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val indexResult = repository.fetchMarketIndices(force)
                val priceResult = repository.fetchPriceChanges(force)
                
                val indexUpdates = indexResult.getOrDefault(0)
                val priceUpdates = priceResult.getOrDefault(0)
                
                // Get primary index name from profile
                val profile = portfolioRepository.userProfile.first()
                val primaryName = profile.primaryIndexName
                
                // Check if primary index was found in latest update (or exists in DB)
                val allIndices = indices.value
                val primaryFound = allIndices.any { it.index.equals(primaryName, true) || it.index.contains(primaryName, true) }
                
                if (force && !primaryFound && primaryName != "NEPSE Index") {
                    portfolioRepository.triggerSnackbar("Primary Index '$primaryName' not found in market data sources.")
                }

                checkDefaultIndices()
                
                if (force) {
                    if (indexUpdates > 0 || priceUpdates > 0) {
                        portfolioRepository.triggerSnackbar("Market data refreshed: $indexUpdates indices, $priceUpdates prices updated")
                    } else if (primaryFound || primaryName == "NEPSE Index") {
                        portfolioRepository.triggerSnackbar("Market data is already up to date")
                    }
                }
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

    fun setAllIndicesVisible(visible: Set<String>) {
        viewModelScope.launch {
            portfolioRepository.updateVisibleIndices(visible.toList())
        }
    }

    private fun checkDefaultIndices() {
        // This was restoring defaults when the list was empty. 
        // We've removed the auto-population logic to respect the user's choice to unselect all.
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
