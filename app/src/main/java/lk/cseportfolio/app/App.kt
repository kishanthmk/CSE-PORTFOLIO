package lk.cseportfolio.app

import android.app.Application
import lk.cseportfolio.app.data.AppDb
import lk.cseportfolio.app.data.MarketStore
import lk.cseportfolio.app.data.Repository
import lk.cseportfolio.app.data.SettingsStore

class App : Application() {
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val repository: Repository by lazy { Repository(this, AppDb.open(this), settings, MarketStore(this)) }
}
