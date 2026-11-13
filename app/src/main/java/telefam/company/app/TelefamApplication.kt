package telefam.company.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.telefam.company.app.BuildConfig
import telefam.company.data.local.DeviceIdentityStore
import telefam.company.data.local.TelefamDatabase
import telefam.company.data.network.NetworkQualityMonitor
import telefam.company.data.network.TelefamApiService
import telefam.company.data.repository.TelefamRepository
import telefam.company.media.TelefamVideoCacheManager

class TelefamApplication : Application(), ImageLoaderFactory {
  lateinit var repository: TelefamRepository
    private set

  override fun onCreate() {
    super.onCreate()
    val database = TelefamDatabase.getInstance(this)
    val identityStore = DeviceIdentityStore(this)
    val networkMonitor = NetworkQualityMonitor(this)
    val apiService = TelefamApiService.create(BuildConfig.TELEFAM_API_BASE_URL)
    repository = TelefamRepository(
      appContext = applicationContext,
      dao = database.telefamDao(),
      identityStore = identityStore,
      networkMonitor = networkMonitor,
      apiService = apiService,
    )
  }

  override fun newImageLoader(): ImageLoader {
    return TelefamVideoCacheManager.buildImageLoader(this)
  }
}
