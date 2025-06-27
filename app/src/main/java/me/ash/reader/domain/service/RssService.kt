package me.ash.reader.domain.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.di.ApplicationScope
import javax.inject.Inject

class RssService @Inject constructor(
    @ApplicationScope
    private val coroutineScope: CoroutineScope,
    accountService: AccountService,
    private val localRssService: LocalRssService,
    private val feverRssService: FeverRssService,
    private val googleReaderRssService: GoogleReaderRssService,
    private val nextcloudNewsRssService: NextcloudNewsRssService, // Added NextcloudNewsRssService
) {

    private val currentServiceFlow =
        accountService.currentAccountFlow.mapNotNull { it }.map { it.type.id }
            .distinctUntilChanged()
            .map { get(it) }
            .stateIn(coroutineScope, SharingStarted.Eagerly, localRssService)

    fun get() = currentServiceFlow.value

    fun flow() = currentServiceFlow

    fun get(accountTypeId: Int) = when (accountTypeId) {
        AccountType.Local.id -> localRssService
        AccountType.Fever.id -> feverRssService
        AccountType.GoogleReader.id -> googleReaderRssService
        AccountType.FreshRSS.id -> googleReaderRssService // Assuming FreshRSS still uses GoogleReader API compatible service
        AccountType.Inoreader.id -> localRssService // Placeholder, Inoreader might need its own service
        AccountType.Feedly.id -> localRssService // Placeholder, Feedly might need its own service
        AccountType.NextcloudNews.id -> nextcloudNewsRssService // Added NextcloudNews
        else -> localRssService
    }
}
