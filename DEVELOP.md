# Сборка xTravel с нуля

## Инструменты

Установить:

- JDK;
- Android SDK: Platform, Build-Tools, Platform-Tools (`adb`).

Проще всего поставить Android Studio: в ней есть и JDK, и менеджер компонентов SDK. Studio для сборки не обязательна, компоненты ставятся и консольным `sdkmanager`:

```
sdkmanager --sdk_root=<путь к SDK> --licenses
sdkmanager --sdk_root=<путь к SDK> "platform-tools" "build-tools;<версия>" "platforms;android-<версия API>"
```

Gradle ставить не нужно, его скачивает `gradlew`.

### Проект рассчитан на следующие параметры

* JDK
  D:\Android\astudio\jbr
  Используется:
    `deploy.ps1`, переменная `JAVA_HOME`
* Android SDK
  D:\Android\android-sdk
  Используется: 
    `deploy.ps1`, переменная `ANDROID_HOME`
    `local.properties`
    `sdk.dir`
* Кэш Gradle
  D:\Android\gradle
  Используется:
    `deploy.ps1`, переменная `GRADLE_USER_HOME`
* Версия API для сборки и минимальная версия Android
  `compileSdk`, `targetSdk`, `minSdk`
  Используется:
    build.gradle.kts
* Версия Gradle
  Используется:
    gradle/wrapper/gradle-wrapper.properties
* Номер версии приложения
  `versionCode` и `versionName`, правятся вручную, автоматически не увеличиваются
  Задаётся:
    `version.properties`
  Используется:
    build.gradle.kts
    имя файла пакета в `dist`
* Архитектура процессора для отладочной сборки
  arm64-v8a, если параметр не задан
  Задаётся:
    `local.properties`, параметр `DEBUG_ABI`
  Используется:
    build.gradle.kts, блок `buildTypes.debug`
* Каталог результатов сборки
  `output` содержит сборку и служебные каталоги Gradle и Kotlin; готовые пакеты лежат отдельно в `dist`
  Задаётся:
    `build.gradle.kts`, `layout.buildDirectory`
    `gradle.properties`, `org.gradle.projectcachedir` и `kotlin.project.persistent.dir`
    `deploy.ps1`, подкаталог `dist`
* Расположение исходников и ресурсов
  `src` — исходники, `res/AndroidManifest.xml` — манифест, `res\app` — ресурсы приложения, `res\ext` — внешние ресурсы
  Задаётся:
    `build.gradle.kts`, блок `sourceSets["main"]`

ВАЖНО: в отладочную сборку попадают библиотеки только для одной архитектуры, поэтому пакет получается в разы меньше (около 38 МБ вместо 111 МБ), но поставить его можно только на устройство с этой архитектурой.
Release-сборка содержит все архитектуры и ставится на любое устройство.

ВАЖНО: Требуемая версия платформы SDK должна совпадать с `compileSdk`, иначе сборка не найдёт нужный API.

## Ключи

### Ключ подписи приложения — общий, публикуется вместе с проектом

Лежит в каталоге `appKey`: хранилище `appKey/xtravel.jks` и файл `appKey/keystore.properties` (путь, алиас, пароли), который читает сборка.
Если каталога или хранилища нет, **сборка падает с ошибкой**: неподписанный пакет всё равно не установится на телефон.

Ключ хранится в проекте намеренно: обновить уже установленное приложение можно только пакетом, подписанным тем же ключом.

Для создания нового ключа нужно выполнить (пакеты, подписанные новым ключом, **не** встанут поверх установленных со старым: приложение придётся удалить и поставить заново):

```
keytool -genkeypair -keystore appKey/xtravel.jks -alias xtravel -keyalg RSA -keysize 2048 -validity 10000 -storepass <пароль> -keypass <пароль> -dname "CN=JM, OU=xTravel, O=JM, C=RU"
```

После этого указать алиас и пароли в `appKey/keystore.properties`. Посмотреть содержимое хранилища: `keytool -list -v -keystore appKey/xtravel.jks`.
`keytool` лежит в каталоге `bin` того JDK, который используется для сборки.

### Ключи карт

Для работы с картами необходимы ключи для соответствующих провайдеров:

**Яндекс**

https://developer.tech.yandex.ru в Кабинете разработчика подключить сервисы.

* MapKit - отображение и позиционирование карты
* GeoCoder - получение данных об объектах по координатам

ВАЖНО: Ключи указываются в настройках приложения. Без них оно будет работать но функционал для которых создаются улючи работать не будет.

### Данные сборки

При сборке используются определенные параметры и пути.
Параметры указаны и описаны в файле `local.properties`.

## Подготовка устройства для приложения

1. *Настройки → О телефоне* → 7 раз нажать **«Номер сборки»**.
2. В параметрах разработчика включить **«Отладка по USB»**.
3. Подключить кабелем, выбрать режим **«Передача файлов»**, подтвердить на телефоне запрос на отладку с этого компьютера.
4. **Xiaomi/POCO (MIUI, HyperOS):** дополнительно включить **«Установка через USB»** и **«Отладка по USB (Настройки безопасности)»**, иначе установка отклоняется с ошибкой `INSTALL_FAILED_USER_RESTRICTED`.

Проверка связи: `adb devices`.
В списке должно быть подключенное устройство.

## Сборка и установка

`deploy.bat` (обёртка над `deploy.ps1`), из корня проекта:

```
deploy.bat COMMAND [OPTIONS]

Where COMMANDs are:
  -?       - this help
  build    - build app and create apk (only if required)
  install  - build and install latest build on device

Where OPTIONs are:
  -device NAME    - set specific name for device. Required if more then one device connected to ADB
  -release        - create release version. If missing, debug version used
```

Примеры:

```
deploy build                        отладочная сборка
deploy build -release               release-сборка
deploy install                      собрать при необходимости, установить и запустить
deploy install -device cf145993     то же, с выбором устройства
```

Собранный пакет копируется в `dist\xtravel-<versionName>.<versionCode>.apk`, к отладочному добавляется суффикс `-debug`. Если пакет не изменился, копия не создаётся.
Если ничего не менялось, Gradle ничего не пересобирает и повторный запуск занимает секунды. Номер версии скрипт не трогает.

Сборка без скрипта (пути к JDK и SDK в этом случае нужно задать самостоятельно):

```
gradlew.bat assembleDebug
gradlew.bat assembleRelease
```

Готовый release-пакет из `dist` ставится на любой телефон, там нужно разрешить установку из неизвестных источников. Чтобы пакет встал поверх предыдущего с сохранением данных, `versionCode` в `version.properties` должен быть увеличен.


## Окно «О программе»

Описание приложения и список стороннего ПО лежат в строках `about_description` и `about_software_*` файлов `res/app/values/strings.xml` (английский) и `res/app/values-ru/strings.xml` (русский); разметка — HTML внутри `CDATA`.
При добавлении сторонней библиотеки — добавить её запись туда и строку `SoftwareEntry` в `AboutScreen` (`src/Screens.kt`); при обновлении MapKit — поправить версию в тексте.
