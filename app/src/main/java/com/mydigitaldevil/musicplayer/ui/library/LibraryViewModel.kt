package com.mydigitaldevil.musicplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.ads.nativead.NativeAd
import com.mydigitaldevil.musicplayer.ads.AdManager
import com.mydigitaldevil.musicplayer.library.LocalMedia
import com.mydigitaldevil.musicplayer.library.MediaAccess
import com.mydigitaldevil.musicplayer.library.MediaStoreRepository
import com.mydigitaldevil.musicplayer.playback.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { SONGS, VIDEOS }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    repository: MediaStoreRepository,
    private val player: PlayerConnection,
    private val adManager: AdManager,
) : ViewModel() {

    private val _access = MutableStateFlow<MediaAccess?>(null)
    /** Null until first checked, so the UI doesn't flash the permission card. */
    val access: StateFlow<MediaAccess?> = _access.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _tab = MutableStateFlow(LibraryTab.SONGS)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    val songs: StateFlow<List<LocalMedia>> = combine(
        _access.flatMapLatest { if (it?.audio == true) repository.audio() else flowOf(emptyList()) },
        _query,
    ) { list, q -> list.filterBy(q) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val videos: StateFlow<List<LocalMedia>> = combine(
        _access.flatMapLatest { if (it?.video == true || it?.videoPartial == true) repository.video() else flowOf(emptyList()) },
        _query,
    ) { list, q -> list.filterBy(q) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Native ads for list slots, owned here so they survive scrolling. */
    private val _nativeAds = MutableStateFlow<Map<Int, NativeAd>>(emptyMap())
    val nativeAds: StateFlow<Map<Int, NativeAd>> = _nativeAds.asStateFlow()
    private val requestedSlots = mutableSetOf<Int>()

    fun onAccessChanged(access: MediaAccess) { _access.value = access }
    fun onQueryChanged(q: String) { _query.value = q }
    fun onTabSelected(tab: LibraryTab) { _tab.value = tab }

    fun play(list: List<LocalMedia>, index: Int) = player.playMedia(list, index)

    fun requestNativeAd(slot: Int) {
        // Before consent/SDK init there's nothing to load; the UI asks again once ads are ready.
        if (!adManager.adsReady.value || !requestedSlots.add(slot)) return
        viewModelScope.launch {
            adManager.loadNativeAd()?.let { ad -> _nativeAds.value = _nativeAds.value + (slot to ad) }
        }
    }

    override fun onCleared() {
        _nativeAds.value.values.forEach(NativeAd::destroy)
        super.onCleared()
    }

    private fun List<LocalMedia>.filterBy(q: String): List<LocalMedia> {
        val needle = q.trim()
        if (needle.isEmpty()) return this
        return filter {
            it.title.contains(needle, ignoreCase = true) ||
                it.artist?.contains(needle, ignoreCase = true) == true ||
                it.album?.contains(needle, ignoreCase = true) == true
        }
    }

    companion object {
        /** First ad after this many rows, then every [AD_EVERY] rows, at most [MAX_AD_SLOTS]. */
        const val FIRST_AD_AFTER = 6
        const val AD_EVERY = 15
        const val MAX_AD_SLOTS = 3
    }
}
