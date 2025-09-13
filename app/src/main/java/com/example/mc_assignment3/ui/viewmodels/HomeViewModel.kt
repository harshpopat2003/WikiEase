package com.example.mc_assignment3.ui.viewmodels

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mc_assignment3.data.model.WikipediaArticle
import com.example.mc_assignment3.data.repository.WikipediaRepository
import com.example.mc_assignment3.util.LocationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel for the Home Screen, handling search, article browsing, and article suggestions.
 */
class HomeViewModel(
    private val repository: WikipediaRepository,
    private val locationService: LocationService
) : ViewModel() {

    // UI state
    private val _uiState = MutableStateFlow<UiState>(UiState.Initial)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Search results
    private val _searchResults = MutableStateFlow<List<WikipediaArticle>>(emptyList())
    val searchResults: StateFlow<List<WikipediaArticle>> = _searchResults.asStateFlow()

    // Recent articles
    private val _recentArticles = MutableStateFlow<List<WikipediaArticle>>(emptyList())
    val recentArticles: StateFlow<List<WikipediaArticle>> = _recentArticles.asStateFlow()

    // Location-based articles
    private val _nearbyArticles = MutableStateFlow<List<WikipediaArticle>>(emptyList())
    val nearbyArticles: StateFlow<List<WikipediaArticle>> = _nearbyArticles.asStateFlow()

    // Current article
    private val _currentArticle = MutableStateFlow<WikipediaArticle?>(null)
    val currentArticle: StateFlow<WikipediaArticle?> = _currentArticle.asStateFlow()

    // Favorite articles
    private val _favoriteArticles = MutableStateFlow<List<WikipediaArticle>>(emptyList())
    val favoriteArticles: StateFlow<List<WikipediaArticle>> = _favoriteArticles.asStateFlow()
    
    // Location permission state
    private val _showLocationPermissionDialog = MutableStateFlow(false)
    val showLocationPermissionDialog: StateFlow<Boolean> = _showLocationPermissionDialog.asStateFlow()
    
    // Location permission result message
    private val _permissionResultMessage = MutableStateFlow<String?>(null)
    val permissionResultMessage: StateFlow<String?> = _permissionResultMessage.asStateFlow()

    init {
        // Load recent articles on initialization
        loadRecentArticles()
        
        // Load favorite articles
        loadFavoriteArticles()
        
        // Check location permission and show dialog if needed
        checkLocationPermission()
        
        // Cleanup old cached articles
        viewModelScope.launch {
            repository.cleanupOldArticles()
        }
    }

    /**
     * Check if the app has location permissions and show dialog if needed
     */
    private fun checkLocationPermission() {
        if (!locationService.hasLocationPermission()) {
            _showLocationPermissionDialog.value = true
        } else {
            loadNearbyArticles()
        }
    }
    
    /**
     * Request location permission
     */
    fun requestLocationPermission() {
        _showLocationPermissionDialog.value = false
        // The actual permission request will be handled by the UI layer
    }
    
    /**
     * Dismiss location permission dialog
     */
    fun dismissLocationPermissionDialog() {
        _showLocationPermissionDialog.value = false
        _permissionResultMessage.value = "You won't see location-based articles without permission"
    }
    
    /**
     * Handle location permission result
     */
    fun handleLocationPermissionResult(isGranted: Boolean) {
        if (isGranted) {
            _permissionResultMessage.value = "Now you'll see articles based on your location"
            loadNearbyArticles()
        } else {
            _permissionResultMessage.value = "You won't see location-based articles without permission"
        }
    }
    
    /**
     * Clear permission result message
     */
    fun clearPermissionResultMessage() {
        _permissionResultMessage.value = null
    }

    /**
     * Set search query and update UI state
     */
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            _uiState.value = UiState.Initial
        }
    }

    /**
     * Perform a search for articles
     */
    fun searchArticles() {
        val query = _searchQuery.value
        if (query.isBlank()) return

        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val results = repository.searchArticles(query)
                _searchResults.value = results
                _uiState.value = if (results.isEmpty()) UiState.NoResults else UiState.Success
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Load a specific article by ID
     */
    fun loadArticle(articleId: Int) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val article = repository.getArticle(articleId)
                _currentArticle.value = article
                _uiState.value = if (article != null) UiState.Success else UiState.Error("Article not found")
                
                // Generate AI summary if not already available
                if (article != null && article.aiSummary == null) {
                    repository.generateAiSummary(articleId)
                    // Refresh the article to get the summary
                    _currentArticle.value = repository.getArticle(articleId)
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Load recent articles
     */
    fun loadRecentArticles(limit: Int = 10) {
        viewModelScope.launch {
            repository.getRecentArticles(limit).collectLatest {
                _recentArticles.value = it
            }
        }
    }

    /**
     * Load favorite articles
     */
    fun loadFavoriteArticles() {
        viewModelScope.launch {
            repository.getFavoriteArticles().collectLatest {
                _favoriteArticles.value = it
            }
        }
    }

    /**
     * Load articles near the user's current location
     */
    fun loadNearbyArticles() {
        viewModelScope.launch {
            try {
                if (!locationService.hasLocationPermission()) {
                    _showLocationPermissionDialog.value = true
                    return@launch
                }

                val location = locationService.getCurrentLocation()
                if (location != null) {
                    val articles = repository.getNearbyArticles(location.latitude, location.longitude)
                    _nearbyArticles.value = articles
                }
            } catch (e: Exception) {
                // Handle location error silently
            }
        }
    }

    /**
     * Toggle favorite status for an article
     */
    fun toggleFavorite(article: WikipediaArticle) {
        viewModelScope.launch {
            val newStatus = !article.isFavorite
            repository.toggleFavorite(article.pageid, newStatus)
            
            // Update current article if it's the same one
            _currentArticle.value?.let { 
                if (it.pageid == article.pageid) {
                    _currentArticle.value = it.copy(isFavorite = newStatus)
                }
            }
        }
    }

    /**
     * Clear search results
     */
    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _uiState.value = UiState.Initial
    }

    /**
     * Check if the app has location permission
     * @return true if the app has location permission, false otherwise
     */
    fun hasLocationPermission(): Boolean {
        return locationService.hasLocationPermission()
    }

    /**
     * Cleanup resources when the ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()
        locationService.cleanup()
    }

    /**
     * UI state sealed class to represent different states of the UI
     */
    sealed class UiState {
        data object Initial : UiState()
        data object Loading : UiState()
        data object Success : UiState()
        data object NoResults : UiState()
        data class Error(val message: String) : UiState()
    }
}