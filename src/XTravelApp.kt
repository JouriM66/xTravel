package com.jm.xtravel

import android.app.Application

/** Initializes application-wide services and module registrations. */
class XTravelApp : Application() {

  override fun onCreate() {
    super.onCreate()
    Failures.installCrashHandler(this)
    AppSession.init(this)
    Settings.init(this)
    LicenseManager.register(YandexMapEngine)
    LicenseManager.register(YandexSearchEngine)
    GeoSearchManager.register(YandexSearchEngine)
    GeoDataShareManager.register(XTravelShare)
    GeoDataShareManager.register(GpxShare)
    GeoDataShareManager.register(TextPhotoShare)
    GeoDataShareManager.register(YandexMapsSiteShare)
    GeoDataShareManager.register(DoubleGisShare)
    GeoDataShareManager.register(GeoAppShare) // универсальный - последним из провайдеров
    GeoDataShareManager.clearFiles()
    AppDirs.init(this)
    Maps.init()
    GpsDataManager.register(GpsFilter_None)
    GpsDataManager.register(GpsFilter_Simple)
    GpsDataManager.selfRegister()
    DataFormats.register(DataFormat1Importer)
    DataOwnerManager.register(PointsData)
    DataOwnerManager.register(RouteStore)
    DataOwnerManager.register(NotesData)
    DataOwnerManager.register(TracksData)
    DataOwnerManager.register(GlobalData)
    PointStore.listeners.register(RouteStore)
    DataStore.init()
    Alerts.init(this)
    if (Maps.type == MapType.YANDEX && Maps.shownType == MapType.NONE) AppSession.message(R.string.mapkit_key_missing)

    Settings.useGps.onChange { on ->
      if (on) {
        Failures.reset(FailureSource.GPS)
        Settings.miuiConfirmed.value = false
        AppSession.activity?.gpsSetup?.run()
      } else {
        GpsService.stop(this)
      }
    }
    Settings.filterMode.onChange { GpsDataManager.rebuildFilter() }
    Settings.mapkitKey.onChange { YandexMapEngine.keyChanged() }
  }
}
