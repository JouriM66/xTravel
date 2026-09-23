<!-- русский текст для того чтобы редакторы не путали кодировку -->

# xTravel

* [NEWS](NEWS.md)
* [Built with](#создано-с-помощью--built-with)

Русский

* [ОПИСАНИЕ](#русский)
* [УСТАНОВКА И НАСТРОЙКА](#установка-и-настройка)
* [КЛЮЧИ ДОСТУПА К API КАРТ](#ключи-доступа-к-api-карт)

English

* [DESCRIPTION](#english)
* [INSTALLATION AND SETUP](#installation-and-setup)
* [MAP API KEYS](#map-api-keys)

---

## Versions

* 0.1.70

---

## Русский

### Что это такое

xTravel — приложение для Android для самостоятельных путешественников. Оно помогает заранее собрать
интересные места на карте, составить из них маршрут, пройти его с подсказками и записать фактический путь.
Все данные хранятся на телефоне: регистрации и облака нет, при этом их легко передать другому человеку
или в другое приложение.

Приложение подойдёт, если вы:

- сами планируете поездки и хотите держать список мест, заметки и фото в одном месте;
- гуляете или ездите по городу, по музеям и достопримечательностям, где важно время работы;
- хотите записывать треки своих прогулок, походов и поездок;
- обмениваетесь планами поездок с друзьями или переносите их между телефонами.

### Точки — места, которые хочется посетить

- Точку можно поставить в любом месте карты, в том числе сразу со снимком с камеры.
- К точке прикрепляются заметки и фотографии в любом количестве и порядке; ссылки в тексте открываются касанием.
  Текст можно надиктовать голосом.
- Название и адрес места можно подставить автоматически по данным карты.
- Каждой точке выбирается значок; цвет булавки показывает её состояние: запланирована, активна,
  уже посещена или просто сохранена «на всякий случай».
- **Часы работы.** Для точки задаётся расписание по дням недели. На карте видно, открыто ли место сейчас
  и сколько осталось до открытия или закрытия; можно включить предупреждение заранее, чтобы не опоздать.
- **Автопосещение.** Когда вы подходите к точке, она сама отмечается посещённой.
- Список всех точек с выбором нескольких сразу, групповыми действиями и фильтрами.

### Маршруты

- Маршрут — упорядоченный список точек, которые нужно посетить по очереди.
- Пути между точками строятся для разных способов передвижения: пешком, на велосипеде, общественным транспортом,
  на машине или грузовике. Если вариантов пути несколько, между ними можно переключаться.
- Порядок остановок можно менять вручную или упорядочить автоматически.
- **Навигация:** в верхней строке показываются ближайшая остановка, расстояние, ожидаемое время в пути с учётом
  вашей реальной скорости и, для автомобиля, ближайший манёвр. Достигнутые остановки убираются сами.
- Маршрут можно открыть в Яндекс Картах, 2ГИС или другом картографическом приложении.

### Треки — запись пройденного пути

- Запись пути по GPS идёт в фоне, даже когда экран выключен.
- Встроенный фильтр отсекает скачки и «дрожание» координат на стоянке.
- Сохранённые треки показываются на карте; их можно переименовать, объединить, удалить, перейти к началу или концу.
- Если приложение было закрыто во время записи, при следующем запуске трек продолжается.

### Записки

Отдельный блокнот с текстами и фотографиями, не привязанными к месту: брони, телефоны, списки дел.

### Фото

Кнопка «Фото» позволяет сделать серию снимков и затем решить, куда их положить: в ближайшую точку,
в новую точку на текущем месте или в записки.

### Карта и позиция

- Карта следует за вашим положением, может поворачиваться по направлению движения или оставаться «севером вверх».
- Текущая скорость, кольца расстояний вокруг вас, линейка масштаба.
- Поиск по своим данным (точки, заметки, треки) и, по желанию, поиск мест через интернет.

### Обмен данными и резервные копии

- Любые данные — все сразу или выборочно — можно отправить через стандартное меню «Поделиться»:
  - в собственном формате xTravel (сохраняет всё, включая фото и треки);
  - в формате GPX, который понимают большинство навигаторов и картографических сервисов;
  - как текст с фото или ссылкой на место в Яндекс Картах.
- Приём файлов xTravel и GPX: перед импортом можно выбрать, что именно забрать. Совпадающие места
  не дублируются, а объединяются.
- Выборочная очистка данных и уборка лишних файлов.

### Что нужно знать заранее

- Нужен Android 8.0 или новее.
- Карта, поиск адресов и построение путей работают через сервисы Яндекса. Ключи к ним в приложение не встроены:
  их нужно получить самостоятельно и ввести в настройках (см. [Ключи доступа к API карт](#ключи-доступа-к-api-карт)). Без ключа карты вместо неё показывается
  координатная сетка, а точки, треки, записки и обмен данными продолжают работать.
- Для записи треков и навигации нужен доступ к местоположению, в том числе в фоне.
- Интерфейс на русском и английском языках.
- Приложение в ранней стадии разработки (версия 0.1).

---

## English

### What it is

xTravel is an Android app for independent travellers. It helps you collect interesting places on a map in advance,
build a route from them, follow it with guidance and record the path you actually took.
All data stays on your phone: no account and no cloud, yet it is easy to hand over to another person
or to another app.

The app is for you if you:

- plan your own trips and want places, notes and photos kept together;
- explore cities, museums and sights where opening hours matter;
- want to record tracks of your walks, hikes and rides;
- share trip plans with friends or move them between phones.

### Points — places you want to visit

- Put a point anywhere on the map, optionally straight with a camera shot.
- Attach any number of notes and photos to a point, in any order; links in the text open with a tap.
  Text can be dictated by voice.
- The place name and address can be filled in automatically from map data.
- Pick an icon for each point; the pin colour shows its state: planned, active, already visited,
  or simply saved "just in case".
- **Opening hours.** Set a weekly schedule for a point. The map shows whether the place is open now
  and how long until it opens or closes; an early warning can be turned on so you are not late.
- **Auto-visit.** When you come close to a point, it is marked as visited by itself.
- A list of all points with multi-selection, bulk actions and filters.

### Routes

- A route is an ordered list of points to visit one after another.
- Paths between points are built for different modes of travel: walking, cycling, public transport,
  car or truck. When several path options exist, you can switch between them.
- Reorder the stops by hand or let the app sort them automatically.
- **Navigation:** the top bar shows the next stop, the distance, the estimated travel time based on your actual speed
  and, for driving, the next manoeuvre. Reached stops are removed automatically.
- A route can be opened in Yandex Maps, 2GIS or another map app.

### Tracks — recording where you went

- GPS recording runs in the background, even with the screen off.
- A built-in filter removes jumps and position "jitter" while you stand still.
- Saved tracks are shown on the map; you can rename, merge and delete them, or jump to their start or end.
- If the app was closed while recording, the track continues on the next launch.

### Notes

A separate notebook for texts and photos not tied to a place: bookings, phone numbers, to-do lists.

### Photos

The Photo button lets you take a series of shots and then decide where they go: to the nearest point,
to a new point at your current location, or to the notes.

### Map and position

- The map follows your position and can rotate with your direction of travel or stay north-up.
- Current speed, distance rings around you, a scale bar.
- Search through your own data (points, notes, tracks) and, if you wish, search for places online.

### Sharing and backups

- Any data — everything or a selection — can be sent via the standard Share menu:
  - in the native xTravel format (keeps everything, including photos and tracks);
  - as GPX, understood by most navigators and map services;
  - as text with photos, or as a link to the place in Yandex Maps.
- Receiving xTravel and GPX files: before importing you choose exactly what to take. Matching places
  are merged rather than duplicated.
- Selective data clearing and cleanup of unused files.

### Good to know

- Requires Android 8.0 or newer.
- The map, address lookup and path building use Yandex services. Their keys are not built into the app:
  you obtain them yourself and enter them in the settings (see [Map API keys](#map-api-keys)). Without a map key a coordinate grid
  is shown instead of the map, while points, tracks, notes and sharing keep working.
- Track recording and navigation need location access, including in the background.
- The interface is available in English and Russian.
- The app is at an early stage of development (version 0.1).

---

## Установка и настройка

### Установка

1. Скачайте готовый APK-файл xTravel из артефактов репозитория.
2. Откройте его на телефоне. Если Android спросит, разрешите установку приложений из этого источника.
3. При первом запуске разрешите приложению доступ к местоположению (для записи треков — «разрешать всегда»)
   и показ уведомлений.

После установки сразу работают точки, записки, фото, запись треков и обмен данными.

### Настройка

Отображение карты, построение путей для навигации и получение сведений о местах (название, адрес)
идут через внешних поставщиков. Для этого нужно получить собственные ключи доступа к их API
(см. [Ключи доступа к API карт](#ключи-доступа-к-api-карт)) и ввести их в приложении:
главное меню → «Настройки» → группа «Карта», поля «Ключ MapKit» и «Ключ Геокодера».

- Новый ключ MapKit начинает действовать после перезапуска приложения.
- При переносе настроек на другой телефон ключи не передаются: вводите их на каждом устройстве.

---

## Installation and setup

### Installation

1. Download the ready-made xTravel APK file from the repository artifacts.
2. Open it on your phone. If Android asks, allow installing apps from this source.
3. On first launch, grant location access (choose "Allow all the time" for track recording)
   and permission to show notifications.

Points, notes, photos, track recording and data sharing work right after installation.

### Setup

Map display, path building for navigation and place information (name, address) come from external providers.
For these you need your own API keys (see [Map API keys](#map-api-keys)), entered in the app:
main menu → "Settings" → "Map" group, fields "MapKit key" and "Geocoder key".

- A new MapKit key takes effect after the app is restarted.
- Keys are not transferred when you move settings to another phone: enter them on each device.

---

## Ключи доступа к API карт

Сейчас в приложении есть такие поставщики:

| Назначение | Поставщик | Нужный ключ | Бесплатный ключ |
|---|---|---|---|
| Карта и построение маршрутов | Яндекс | MapKit SDK | Отображение карты не ограничено; до 1000 запросов на построение маршрута в день |
| Геоинформация: название и адрес места | Яндекс | Геокодер (HTTP Geocoder) | До 1000 запросов данных об адресах в день |

Как получить ключи:

1. Войдите в [кабинет разработчика Яндекса](https://developer.tech.yandex.ru/services) со своим Яндекс ID.
2. Нажмите «Подключить API» и выберите сервис:
   - «MapKit – мобильный SDK» — для ключа карты;
   - «JavaScript API и HTTP Геокодер» — для ключа Геокодера.
3. Заполните анкету, выбрав бесплатный вариант использования, и скопируйте выданный ключ.
4. Введите ключ в приложении: «Настройки» → «Карта». Новый ключ начинает работать не сразу —
   активация на стороне Яндекса может занять до нескольких часов.

Условия бесплатного использования устанавливает Яндекс, и они могут меняться; действующие правила смотрите
на страницах сервисов в кабинете разработчика.

---

## Map API keys

The app currently supports these providers:

| Purpose | Provider | Key needed | Free key |
|---|---|---|---|
| Map and route building | Yandex | MapKit SDK | Unlimited map display; up to 1000 route requests per day |
| Geo information: place name and address | Yandex | Geocoder (HTTP Geocoder) | Up to 1000 address data requests per day |

How to get the keys:

1. Sign in to the [Yandex developer dashboard](https://developer.tech.yandex.ru/services) with your Yandex ID.
2. Click "Connect API" and choose the service:
   - "MapKit – mobile SDK" for the map key;
   - "JavaScript API and HTTP Geocoder" for the Geocoder key.
3. Fill in the form choosing the free usage option, and copy the issued key.
4. Enter the key in the app: "Settings" → "Map". A new key does not work immediately —
   activation on the Yandex side can take up to a few hours.

The free usage terms are set by Yandex and may change; check the current rules on the service pages
in the developer dashboard.

---

## Создано с помощью / Built with

Приложение разрабатывается с помощью **Claude Code** — ИИ-агента для программирования от компании [Anthropic](https://www.anthropic.com).
Модель: **Claude Opus 5.5** (`claude-opus-5-5`) из семейства больших языковых моделей Claude.
Решения о функциях и проверку результата выполняет разработчик.

The app is developed with the help of **Claude Code**, an AI coding agent by [Anthropic](https://www.anthropic.com).
Model: **Claude Opus 5.5** (`claude-opus-5-5`) from the Claude family of large language models.
Feature decisions and verification of results are made by the developer.
