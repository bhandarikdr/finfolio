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
import kotlinx.coroutines.flow.flatMapLatest
import com.example.data.db.IpoMemberActivity
import java.text.SimpleDateFormat
import java.util.*

/**
 * UI & LOGIC STANDARDS - BulkIpoViewModel:
 * 1. RENAMING: Guided -> CDSC Portal, Bulk Pro -> Auto Check (Check Tab).
 * 2. RENAMING: Individual -> Check Through CDSC, Bulk Pro -> Auto Apply (Apply Tab).
 * 3. FILTERING: Apply list shows only verified members with credentials.
 * 4. SYNC: Refresh button triggers both IPO and DP master sync.
 */
class BulkIpoViewModel(
    private val repository: IpoRepository,
    private val msRepository: MeroShareRepository,
    private val marketRepository: MarketRepository
) : ViewModel() {

    val ipos: StateFlow<List<IpoMaster>> = repository.ipoMasterList
        .map { list ->
            list.sortedByDescending { it.updatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDps: StateFlow<List<com.example.data.db.DpMaster>> = marketRepository.getAllDpMaster()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val checkIpos: StateFlow<List<IpoMaster>> = ipos.map { list ->
        list.filter { 
            it.status.contains("Completed", true) || it.status.contains("Result", true) || it.status.contains("Allotted", true)
        }.sortedByDescending { it.allotmentDate ?: "0000-00-00" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val applyIpos: StateFlow<List<IpoMaster>> = ipos.map { list ->
        list.filter { 
            it.status.contains("Open", true) || 
            it.status.contains("Ongoing", true) ||
            it.status.contains("Applying", true)
        }.sortedByDescending { it.closingDate ?: "" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedIpo = MutableStateFlow<IpoMaster?>(null)
    val selectedIpo = _selectedIpo.asStateFlow()

    val boids: StateFlow<List<BoidEntry>> = repository.allBoids
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Filtered list for Auto-Apply: Only members with Password, PIN, and CRN */
    val verifiedApplyBoids: StateFlow<List<BoidEntry>> = boids.map { list ->
        list.filter { !it.msPassword.isNullOrBlank() && !it.msPin.isNullOrBlank() && !it.msCrn.isNullOrBlank() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _results = MutableStateFlow<List<BulkIpoResult>>(emptyList())
    val results = _results.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking = _isChecking.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage = _syncMessage.asStateFlow()

    val syncLog = repository.syncLog

    @OptIn(ExperimentalCoroutinesApi::class)
    val memberActivity: StateFlow<List<IpoMemberActivity>> = _selectedIpo
        .flatMapLatest { ipo ->
            if (ipo == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else repository.getActivityForCompany(ipo.companyName)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isTestingLogin = mutableStateMapOf<String, Boolean>()
    val isTestingLogin: Map<String, Boolean> = _isTestingLogin

    fun testLogin(boid: BoidEntry) {
        val user = boid.msUsername ?: return
        val pass = boid.msPassword ?: return
        
        viewModelScope.launch {
            _isTestingLogin[boid.boid] = true
            val result = msRepository.login(boid.boid, user, pass)
            result.onSuccess {
                _syncMessage.value = "Login Success for ${boid.name}"
            }.onFailure {
                _syncMessage.value = "Login Failed: ${it.message}"
            }
            _isTestingLogin.remove(boid.boid)
            delay(2000)
            _syncMessage.value = null
        }
    }

    private val _enabledBoids = mutableStateMapOf<String, Boolean>()
    val enabledBoids: Map<String, Boolean> = _enabledBoids

    init {
        // Automatically select the first relevant IPO (preferring Results for the Check screen)
        viewModelScope.launch {
            checkIpos.collect { list ->
                if (list.isNotEmpty() && _selectedIpo.value == null) {
                    _selectedIpo.value = list.first()
                }
            }
        }

        // Sync if empty
        viewModelScope.launch {
            if (ipos.value.isEmpty() || allDps.value.isEmpty()) {
                refreshAllCompanies()
            }
        }

        // Initialize and sync enabled boids map
        viewModelScope.launch {
            boids.collect { list ->
                list.forEach { 
                    // Update if already exists or add if new
                    _enabledBoids[it.boid] = it.isEnabledForCheck || it.isEnabledForApply || it.isEnabledForBulk
                }
            }
        }
    }

    fun toggleBoidEnabled(boidId: String, isCheck: Boolean) {
        viewModelScope.launch {
            repository.updateBoidToggle(boidId, isCheck, null)
        }
    }

    fun toggleAllBoids(enabled: Boolean, isCheck: Boolean?) {
        viewModelScope.launch {
            repository.toggleAllBoids(isCheck, enabled)
        }
    }

    fun refreshAllCompanies() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Refreshing IPOs & DPs..."
            val ipoRes = repository.syncIpos(true)
            val dpRes = marketRepository.fetchDpMaster()
            
            _syncMessage.value = if (ipoRes.isSuccess) "Refresh complete" else "IPO Refresh failed"
            _isSyncing.value = false
            delay(2000)
            _syncMessage.value = null
        }
    }

    fun syncIpos(force: Boolean = false) {
        refreshAllCompanies()
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
            // AUTO-PARSING: Username is the last 8 digits of 16-digit BOID
            val autoUser = if (boid.length == 16) boid.substring(8) else null
            repository.addBoid(name, boid, autoUser?.let { mapOf("username" to it) })
        }
    }

    fun removeBoid(boid: BoidEntry) {
        viewModelScope.launch {
            repository.removeBoid(boid.boid)
        }
    }

    /**
     * Phase 2.3: Secure Credential Management
     * Saves or updates MeroShare login details for a specific BOID.
     */
    fun saveCredentials(boid: String, msDetails: Map<String, String>) {
        viewModelScope.launch {
            repository.updateBoidCredentials(boid, msDetails)
            _syncMessage.value = "Credentials saved for $boid"
            delay(1500)
            _syncMessage.value = null
        }
    }

    fun syncDpMaster() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Syncing DP Master..."
            val success = marketRepository.fetchDpMaster()
            _syncMessage.value = if (success) "DP Master synced" else "DP Master sync failed"
            _isSyncing.value = false
            delay(2000)
            _syncMessage.value = null
        }
    }

    fun addMultipleBoids(pastedText: String) {
        viewModelScope.launch {
            val lines = pastedText.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return@launch

            // Check if first line is a header
            val headerLine = lines.first().lowercase()
            val isCsvFormat = headerLine.contains(",") || headerLine.contains(";") || headerLine.contains("\t")
            
            if (isCsvFormat && (headerLine.contains("boid") || headerLine.contains("name") || headerLine.contains("user"))) {
                // Structured CSV/TSV Parsing
                val separator = if (headerLine.contains("\t")) "\t" else if (headerLine.contains(";")) ";" else ","
                val header = lines.first().split(separator).map { it.trim().lowercase() }
                
                val nameIdx = header.indexOfFirst { it == "name" || it == "full name" }
                val boidIdx = header.indexOfFirst { it == "boid" || it == "demat" }
                val userIdx = header.indexOfFirst { it.contains("user") || it.contains("id") }
                val passIdx = header.indexOfFirst { it.contains("pass") }
                val pinIdx = header.indexOfFirst { it == "pin" || it.contains("trans") }
                val crnIdx = header.indexOfFirst { it == "crn" || it.contains("bank") }

                if (boidIdx == -1) {
                    _syncMessage.value = "Import Failed: 'Boid' column missing"
                    return@launch
                }

                lines.drop(1).forEach { line ->
                    val cols = line.split(separator).map { it.trim().replace("\"", "") }
                    if (cols.size > boidIdx) {
                        val boid = cols[boidIdx].filter { it.isDigit() }.takeLast(16)
                        if (boid.length == 16) {
                            val name = if (nameIdx != -1 && nameIdx < cols.size) cols[nameIdx] else "User_${boid.takeLast(4)}"
                            
                            // AUTO-PARSING LOGIC: DP is digits 4-8 (index 3 to 7) of 16-digit BOID
                            // Username is the remaining digits to the right of DP (index 8 onwards)
                            val autoUser = boid.substring(8)

                            val details = mutableMapOf<String, String>()
                            details["username"] = if (userIdx != -1 && userIdx < cols.size && cols[userIdx].isNotBlank()) cols[userIdx] else autoUser
                            if (passIdx != -1 && passIdx < cols.size) details["password"] = cols[passIdx]
                            if (pinIdx != -1 && pinIdx < cols.size) details["pin"] = cols[pinIdx]
                            if (crnIdx != -1 && crnIdx < cols.size) details["crn"] = cols[crnIdx]
                            
                            repository.addBoid(name, boid, details.takeIf { it.isNotEmpty() })
                        }
                    }
                }
                _syncMessage.value = "Imported family members from CSV"
            } else {
                // Legacy Regex-based parsing for raw text paste
                lines.forEach { line ->
                    val boidRegex = Regex("\\b\\d{16}\\b")
                    val match = boidRegex.find(line)
                    if (match != null) {
                        val boid = match.value
                        var name = line.replace(boid, "").replace(Regex("[,:|\\t]"), " ").trim()
                        if (name.isBlank()) name = "User_${boid.takeLast(4)}"
                        if (boids.value.none { it.boid == boid }) {
                            // AUTO-PARSING for raw text paste
                            val autoUser = boid.substring(8)
                            repository.addBoid(name, boid, mapOf("username" to autoUser))
                        }
                    }
                }
                _syncMessage.value = "Imported from text paste"
            }
        }
    }

    private val _isHybridChecking = MutableStateFlow(false)
    val isHybridChecking = _isHybridChecking.asStateFlow()

    private val _hybridBoids = MutableStateFlow<List<BoidEntry>>(emptyList())
    val hybridBoids = _hybridBoids.asStateFlow()

    private fun extractUnits(message: String): Int {
        return try {
            val regex = """(\d+)\s+units""".toRegex()
            val match = regex.find(message.lowercase())
            if (match != null) {
                match.groupValues[1].toInt()
            } else if (message.lowercase().contains("allotted")) {
                10 // Default to 10 for standard IPOs if allotted but units not specified in text
            } else 0
        } catch (e: Exception) { 
            if (message.lowercase().contains("allotted")) 10 else 0 
        }
    }

    fun resetAllotment(boid: String) {
        val ipo = _selectedIpo.value ?: return
        viewModelScope.launch {
            repository.resetAllotmentStatus(ipo.companyName, boid)
        }
    }

    fun resetAllResults() {
        val ipo = _selectedIpo.value ?: return
        viewModelScope.launch {
            repository.resetAllAllotments(ipo.companyName)
        }
    }

    fun markAsApplied(boid: String) {
        val ipo = _selectedIpo.value ?: return
        viewModelScope.launch {
            repository.updateApplyStatus(ipo.companyName, boid, "APPLIED", "Manually Marked", System.currentTimeMillis())
        }
    }

    fun markAsRecorded(boid: String) {
        val ipo = _selectedIpo.value ?: return
        viewModelScope.launch {
            repository.updateIpoMemberActivity(IpoMemberActivity(
                companyName = ipo.companyName,
                boid = boid,
                isRecorded = true
            ))
        }
    }

    fun startAutoCheck() {
        val ipo = _selectedIpo.value ?: run {
            showTemporaryMessage("Please select an IPO first")
            return
        }

        val companyName = ipo.companyName

        com.example.data.util.AppLogger.i("IpoCheck", "Auto Check Results initiated")
        val currentBoids = boids.value.filter { it.isEnabledForCheck && !it.msPassword.isNullOrBlank() }
        
        if (currentBoids.isEmpty()) {
            showTemporaryMessage("No enabled BOIDs with credentials to check.")
            return
        }

        viewModelScope.launch {
            _isChecking.value = true
            
            for (boid in currentBoids) {
                // Mark as checking in UI
                repository.updateIpoMemberActivity(IpoMemberActivity(
                    companyName = companyName,
                    boid = boid.boid,
                    allotmentStatus = "CHECKING",
                    checkedAt = System.currentTimeMillis()
                ))

                val loginResult = msRepository.login(boid.boid, boid.msUsername!!, boid.msPassword!!)
                loginResult.onSuccess { token ->
                    val result = msRepository.checkResult(token, companyName)
                    if (result != null) {
                        repository.updateIpoMemberActivity(IpoMemberActivity(
                            companyName = companyName,
                            boid = boid.boid,
                            allotmentStatus = if (result.success) "ALLOTTED" else "NOT_ALLOTTED",
                            allotmentUnits = if (result.success) extractUnits(result.message) else 0,
                            allotmentMessage = result.message,
                            checkedAt = System.currentTimeMillis()
                        ))
                    } else {
                        repository.updateIpoMemberActivity(IpoMemberActivity(
                            companyName = companyName,
                            boid = boid.boid,
                            allotmentStatus = "ERROR",
                            allotmentMessage = "Result not found in MeroShare reports",
                            checkedAt = System.currentTimeMillis()
                        ))
                    }
                }.onFailure { err ->
                    repository.updateIpoMemberActivity(IpoMemberActivity(
                        companyName = companyName,
                        boid = boid.boid,
                        allotmentStatus = "ERROR",
                        allotmentMessage = "Login Failed: ${err.message}",
                        checkedAt = System.currentTimeMillis()
                    ))
                }
                delay(1000)
            }
            _isChecking.value = false
        }
    }

    fun startIndividualHybridCheck(boid: BoidEntry) {
        val ipo = _selectedIpo.value ?: return
        _hybridBoids.value = listOf(boid)
        _isHybridChecking.value = true
        _isChecking.value = true

        viewModelScope.launch {
            repository.updateIpoMemberActivity(IpoMemberActivity(
                companyName = ipo.companyName,
                boid = boid.boid,
                allotmentStatus = "CHECKING",
                checkedAt = System.currentTimeMillis()
            ))
        }
    }

    fun startBulkCheck(hybrid: Boolean = false) {
        if (hybrid) {
            val ipo = _selectedIpo.value ?: return
            val enabled = boids.value.filter { _enabledBoids[it.boid] ?: it.isEnabledForBulk }
            if (enabled.isEmpty()) return

            _hybridBoids.value = enabled
            _isHybridChecking.value = true
            _isChecking.value = true
            
            // Mark all enabled members as checking in the database
            val companyName = ipo.companyName
            viewModelScope.launch {
                enabled.forEach { boid ->
                    repository.updateIpoMemberActivity(IpoMemberActivity(
                        companyName = companyName,
                        boid = boid.boid,
                        allotmentStatus = "CHECKING",
                        checkedAt = System.currentTimeMillis()
                    ))
                }
            }
        } else {
            startAutoCheck()
        }
    }

    fun onHybridResultReceived(boidEntry: BoidEntry, message: String, success: Boolean) {
        val ipo = _selectedIpo.value ?: return
        com.example.data.util.AppLogger.i("IpoCheck", "Result received for ${boidEntry.name}: $message (Success: $success)")
        
        val units = if (success) extractUnits(message) else 0
        
        viewModelScope.launch {
            repository.updateIpoMemberActivity(IpoMemberActivity(
                companyName = ipo.companyName,
                boid = boidEntry.boid,
                allotmentStatus = if (success) "ALLOTTED" else "NOT_ALLOTTED",
                allotmentUnits = units,
                allotmentMessage = if (success && units > 0 && !message.contains("$units")) 
                    "$message ($units Units)" else message,
                checkedAt = System.currentTimeMillis()
            ))
        }
    }

    fun finishHybridCheck() {
        _isHybridChecking.value = false
        _isChecking.value = false
        _hybridBoids.value = emptyList()
    }

    private fun showTemporaryMessage(msg: String) {
        viewModelScope.launch {
            _syncMessage.value = msg
            delay(3000)
            if (_syncMessage.value == msg) _syncMessage.value = null
        }
    }

    fun clearResults() {
        _results.value = emptyList()
    }

    fun exportVaultToCsv(): String {
        return buildString {
            append("Name,Boid,Username,Password,PIN,CRN\n")
            boids.value.forEach { b ->
                append("${b.name},${b.boid},${b.msUsername ?: ""},${b.msPassword ?: ""},${b.msPin ?: ""},${b.msCrn ?: ""}\n")
            }
        }
    }

    /**
     * Phase 4.2: Auto IPO Application
     * Automates the submission of IPO applications for all enabled family accounts with verified credentials.
     */
    fun startAutoApply(units: Int = 10) {
        val ipo = _selectedIpo.value ?: return
        val companyId = ipo.resultPortalId ?: run {
            showTemporaryMessage("Company Share ID missing for application.")
            return
        }
        
        val accounts = verifiedApplyBoids.value.filter { it.isEnabledForApply }
        
        if (accounts.isEmpty()) {
            showTemporaryMessage("No enabled accounts with verified credentials (PIN, CRN) found.")
            return
        }

        viewModelScope.launch {
            _isChecking.value = true
            _results.value = accounts.map { BulkIpoResult(it, isChecking = true) }

            for ((index, account) in accounts.withIndex()) {
                val loginResult = msRepository.login(account.boid, account.msUsername!!, account.msPassword!!)
                loginResult.onSuccess { token ->
                    val result = msRepository.applyForIpo(
                        authToken = token,
                        boid = account.boid,
                        crn = account.msCrn!!,
                        pin = account.msPin!!,
                        companyShareId = companyId,
                        units = units
                    )
                    
                    result.onSuccess {
                        viewModelScope.launch {
                            repository.updateApplyStatus(ipo.companyName, account.boid, "APPLIED", it, System.currentTimeMillis())
                        }
                    }.onFailure {
                        viewModelScope.launch {
                            repository.updateApplyStatus(ipo.companyName, account.boid, "FAILED", it.message ?: "Apply Failed", System.currentTimeMillis())
                        }
                    }
                }.onFailure { error ->
                    viewModelScope.launch {
                        repository.updateApplyStatus(ipo.companyName, account.boid, "FAILED", error.message ?: "Login Failed", System.currentTimeMillis())
                    }
                }
                delay(1500)
            }
            _isChecking.value = false
            _syncMessage.value = "Auto Application Process Finished"
        }
    }
}

class BulkIpoViewModelFactory(
    private val repository: IpoRepository,
    private val msRepository: MeroShareRepository,
    private val marketRepository: MarketRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BulkIpoViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BulkIpoViewModel(repository, msRepository, marketRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
