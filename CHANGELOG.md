## Version 2.8.1
* Fixed bug when moving photos from 'Local' album, the moved photos are not deleted in the original album
* Fixed bug after the last photo deleted from Camera Roll Archive, it's still showing in the list
* Fixed bug of sync process stuck for Caddy server when deleting a file not existed 
* New app shortcut you can add to launcher for launching camera roll manager screen directly
* Move "Create New Album" item to the top of the album list in adding photos dialog
* Back press now clear selection first instead of closing app directly
* Show country flag emoji in location search result

## Version 2.8.0
* New feature: RAW format support
* New feature: Backup app's settings to server
* New feature: Backup device's 'Pictures' folder to server
* Fixed backup stuck on creating new sub folder

## Version 2.7.6
* Fixed camera roll backup stuck with some server software stack like Caddy
* Fixed camera roll backup resulted in 0 size file being created on server

## Version 2.7.5
* Various bugs fixed for video player, especially gapless playback after device screen rotated
* Various bugs fixed for photo displaying on map
* Various bugs fixed for Muzei integration, especially portrait mode photo cropping

## Version 2.7.4
* New feature: accept self-signed certificate on the fly during login

## Version 2.7.3
* Fixed swipe up gesture conflict between revealing camera roll list and adjusting video volume/brightness
* Fixed file name changed when adding file using Android's Photo Picker

## Version 2.7.2
* Fixed audio volume change to maximum in camera roll screen or picture browsing screen
* Fixed timezone difference not being handled when adding picture to album

## Version 2.7.0
* New feature: geotag photos with GPX file
* New feature: export album's location information to GPX file

## Version 2.6.6
* New feature: publish album to Nextcloud Circle and General Public
* Fixed video wrongly resumed playing after re-gaining window focus in Camera Roll screen
* Longer network read/write timeout setting

## Version 2.6.5
* Fixed login screen flickering on some NC instance
* Fixed displaying slogan with escaped characters in login screen

## Version 2.6.4
* New UI design for video playing
* Fixed side bar on display cutout area
* Fixed resuming video playing in Android 13 after device rotated
* Search by photo name in album is now case insensitive

## Version 2.6.3
* Quick fix for crash of new login
 
## Version 2.6.2
* New feature: Feeding Muzei with AI cropped landscape photos
* More robust implementation of sharing out medias to other apps
* Fixed several bugs affecting search in camera roll archive
* Fixed wrong zoned time displayed in Timeline theme blog post

## Version 2.6.1
* New feature: photo blogging
* Fixed video item preview download

## Version 2.6.0
* New feature: photo blogging
* Fixed video item preview download

## Version 2.5.22
* Added function to set server home folder during login
* Fixed album publishing and avatar loading when user has email address set
* Fixed not able to create new album when acquiring photos from friend's publication
* Fixed album statistic not updated when adding or deleting photos in album detail view
* Fixed thumbnail for very small size video
* Fixed overscroll effect for some dialogs

## Version 2.5.21
* Fixed crash when opening video on some OEM devices, like Xiaomi for instance
* Fixed wrong photo taken date display

## Version 2.5.20
* Support LDAP and other SSO authentication
* New function to view status of background sync/backup, new manual re-sync button
* Offer choice to backup existing photo in camera roll for new installs
* Fixed crash when viewing high resolution pictures
* Show picture taken time in taken place's local time

## Version 2.5.19
* Fixed bug preventing backup of a large camera roll
* Show name of current file being backup in Setting
* Fixed ANR when requesting storage access permission in Setting
* Stop playing slideshow when app is moved to the background
* Android 13 compatible, adapted to granular media permission, monochrome launcher icon, per-app language and predictive back gesture

## Version 2.5.18
* Fixed bug preventing backup of a large camera roll
* Show name of current file being backup in Setting
* Fixed ANR when requesting storage access permission in Setting
* Stop playing slideshow when app is moved to the background
* Android 13 compatible, adapted to granular media permission, monochrome launcher icon, per-app language and predictive back gesture

## Version 2.5.17
* Correctly read photo creation timestamp which has timezone offset from EXIF
* Persist album list sorting order
* Fixed crash when playing video in Gallery app while Les Pas app is still running in the background
* Longer timeout to facilitate syncing on lousy connection

* ## Version 2.5.16
* Fixed screen auto-off when device rotated during video playing
* Fixed when adding portrait mode pictures from server to 'Local' album, the picture didn't rotated accordingly
* Fixed portrait mode photo in 'Remote' album not correctly rotated when sharing out
* Fixed layout when viewing portrait mode photo in 'Remote' album with map
* Updated French translation thanks to github user @Choukajohn

## Version 2.5.15
* Fixed crash when opening camera roll on Android 9 or below
* Fixed top potion of map covered by title bar when viewing photos on map

## Version 2.5.14
* Fixed sync failed after album renamed
* Fixed date reading from EXIF being offset
* Fixed refreshing publication
* Fixed timeout when reading large camera roll archive
* Resume video playing after device rotated
* Warn user of low internal storage space

## Version 2.5.13
* Pre-fetch image from server when playing slideshow on map
* Scroll bar for album list and camera roll list
* More user friendly camera roll list UI and UX

## Version 2.5.12
* New Italian localization, thanks to github user @chiaratira and @michelafr
* Name filter for Publications which has 'show title' setting turned on
* New theme for OpenStreetMap tiles in dark mode
* Revamp video file meta data extracting, correctly read creation date, extract location data
* Optimized media file processing, more responsive UI, less freezing

## Version 2.5.11
* New: name filter for albums with "Show Name" option turned on
* New: AI object detection and location search for camera roll backups on server
* New: Date picker in the camera roll list
* Fixed login using self-signed certificate

## Version 2.5.10
* New: manage camera roll archive on server
* New: show sync status in Setting menu
* Fixed crash when opening app on Android 9
* Fixed QR code scanning function in Login screen

## Version 2.5.9
* Fixed sync setting error that accidentally turns off album auto sync. You might need to take a look at the 'Setting'.

## Version 2.5.8
* Fixed crash while showing message when user try to search in empty album list
* Fixed crash when trying to play animated GIF/WebP in some OEM ROM which doesn't implement certain Android APIs
* Fixed crashes reported in Google Play console

## Version 2.5.7
* True black theme

## Version 2.5.6
* Fixed crash in some edge cases when Nextcloud server return null value for file attributes like 'last modified'

## Version 2.5.5
* Fixed login problem with Nextcloud instances which does not support theming by default
* New detail layout can be chose to display media filename, useful for video collection album
* New media rename function. Can be used to change photo taken date too if name contains pattern like 'yyyyMMddHHmmss' or 'yyyyMMdd_HHmmss'
* New option to converge nearby photos when playing slideshow on map

## Version 2.5.4
* Fixed bugs halting sync process when upgrading from release 2.4.x
* When copying/moving photos to Joint Album or remote album, file operations are carried out on server only
* Fixed various bugs when adding photos to Joint Album
* Fixed button not responding in camera roll photo list screen
* Fixed bug preventing some photos from being "Today in history" artwork to Muzei
* Support Chinese made Map app when sharing location to them. Yes, they use a bizarre coordinate system. 
* Other improvements and bugs fixes

## Version 2.5.3
* New login and re-login screen, more robust implementation and follow your Nextcloud server's theming
* Fixed crash when asking for storage access permission
* Fixed crash when transferring app storage between internal and external SD
* Fixed camera roll auto backup stalled
* Fixed album name not being updated on screen after renaming

## Version 2.5.2
* When syncing with server, recreate metadata files on server if they are missing

## Version 2.5.1
* Fixed wrong format of metadata JSON file being generated when running in system with non english locales like French, German, etc.
* New pt-BR translation, thanks to github user @nosklo

## Version 2.5.0
* New feature: Remote Album, all image files of remote album are stored in server only, free up phone's storage space
* New feature: slideshow in map can now be shared to other Nextcloud user
* New feature: support setting animated GIF, animated WEBP as album's cover photo
* New feature: photo meta scanning process can now parse file name with timestamp pattern, support yyyyMMddHHmmss and yyyyMMdd_HHmmss
* Fixed potential crash when quickly enter and exit location search screen
* Fixed crash when playing slideshow in map
* Fixed seeding image with wrong orientation to Muzei
* After sorting album list, current scroll position will reset to top
* Other improvements and bugs fixes

## Version 2.4.6
* Show camera roll backup status summary in Setting
* New default photo sort order preference in Setting
* New option to avoid refreshing Muzei wallpaper during late night
* Able to launch map app in Photo with Map screen
* Avoid name collision when hiding and un-hiding album
* Fixed crash when long press to select multiple albums
* Fixed crash when adding Gallery launcher icon in Setting

## Version 2.4.5
* Added BGM to slideshow
* Added hide/unhide album function
* Optimized app startup

## Version 2.4.4
* Fixed bug of reading EXIF from camera roll photos, which will crash app when viewing detail meta data of camera roll photos, brick camera roll auto backup, prevent adding photo to Les Pas from camera roll, etc.
* Fixed bug that allowing search in album while there are no albums at all

## Version 2.4.3
* Quick fix to sharing out selected photos in album detail screen

## Version 2.4.2
* New feature: auto play slideshow in map for entire album
* New feature: able to share in photo with map mode
* New feature: able to scan Nextcloud's app password QR code when login
* New feature: Info button when viewing camera roll to reveal photo meta data
* Fixed photo zoom, over scroll on edge to swipe to next
* Fixed various crashes related to reading photo's EXIF
* Fixed image broken after Snapseed editing
* Sync now respect file hidden attribute following linux file name convention

## Version 2.4.1
* Fixed publishing feature on Nextcloud 22 and up. From now on, no need to install Share List app on server

## Version 2.4.0
* Major update to support photo's GPS data, show photo with map, show whole album in map, search by location
* Fixed camera roll backup not uploading original photo (system provides a modified version without GPS coordinate) on Android 10 and up

## Version 2.3.13
* Fixed not able to list video file in external storage

## Version 2.3.12
* Fixed crash when sharing media while phone is in landscape orientation
* Use last modified date if EXIF doesn't contain taken date when syncing photo from server

## Version 2.3.11
* Fixed bug of missing Camera Roll album cover
* Fixed HEIF/HEIC format support
* Change album start and end date when photo's taken date were changed on server
* More meaningful SSL certification error message during login
* Can cancel long EXIF stripping process now
* Refactored video playing codebase with new Media3 library
* Refactored album meta data file update codebase, now not just published ones but every album's meta data are synced to server immediately after changes

## Version 2.3.10
* Fixed http server access
* Fixed crash when there are no local album exist
* Fixed sync process hang when creating more than one album at one time
* Fixed accidental deletion of newly created album/photo

## Version 2.3.9
* Fixed bug of uploading chunked large file (bugs introduced in version 2.3.7)
* Added album list sorting feature
* Added setting option to show media list when entering camera roll

## Version 2.3.8
* Fixed bug of failing to remove photos in camera roll after moving them to album when runs on Android 11 or above
* Show a waiting sign during long process of striping EXIF info
* Update German translation, thanks to github user @raphaelporsche
* Update Taiwanese translation

## Version 2.3.7
* Fixed bugs renaming album, uploading photo which name containing non ASCII characters
* Updated screenshot

## Version 2.3.6
* Revamped camera roll management screen, swipe up to reveal camera roll media list
* Android 12 compatible
* Fixed integration with Snapseed, now Les Pas will correctly load the Snapseed output file
* Fixed newly downloaded friend's photo not being scan by Media Store in Android 9 and below
* Fixed media creation date detection in camera roll when launched from file manager

## Version 2.3.5
* Add function to strip EXIF and scramble file name before sharing media items
* Add dedicate function to launch Snapseed for editing
* Add dedicate function to copy or move media items to another album

## Version 2.3.4
* Auto replay video and animation GIF/WebP (feature suggested by Github user @MetalNeo)
* Can swipe through friend's publication's items
* When viewing friend's publication, can download media item, add media item to other albums and view media item's meta data

## Version 2.3.3
* Re-login function, useful when you accidentally revoke Les Pas app's access token on server
* Can set video or animated GIF as album cover now

## Version 2.3.2
* quick fix for crash on startup

## Version 2.3.1
* Revamp code for video processing
* Add feature to show Camera Roll as a special album
* Minor UI changes

## Version 2.3.0
* Support insecure HTTP connection to server
* Upgrade to AGP 7.0
* Code cleanup

## Version 2.2.1
* Three ways to select photos as Muzei wallpaper: Recent, Then and now, Random
* Update notification of shares with me

## Version 2.2.0
* Support Muzei wallpaper app
* Fixed sync got stuck after finished editing photo with Snapseed
* Fixed sync crash when there is an new empty album on server
* Fixed can't delete media file in public storage area on Android 10

## Version 2.1.1
* Support sharing media to Joint Album
* Support large file upload
* Fixed bug of the whole sync process got blocked when contributing to Joint Album under some rare circumstances
 
## Version 2.1.0
* New feature, Joint album, edit it with other Nextcloud users
* Support user avatar from Nextcloud server
* Refresh publication content automatically
* Replaced Sardine library with homebrew, app should be more snappy
* Fixed cover display for video only album
* Fixed bug when renaming a shared album


## Version 2.0.4
* Fixed bug when new folder in Camera Roll not being backup, might due to Nextcloud changed the http response code

## Version 2.0.3
* Improved publication query speed by reducing numbers of http calls to server
* Improved publication viewing experience when using large screen
* Restore video player mute state after rotating phone screen
* Can view animated gif/webp in Camera Roll now

## Version 2.0.2
* Fixed various publishing (sharing on Nextcloud server) related bugs

## Version 2.0.1
* Fixed crash when opening albums, cause by updating some kotlin libraries

## Version 2.0.0
* Publish (e.g. sharing on Nextcloud server) your albums to other Nextcloud users
* Browse other Nextcloud users' publications
* Sync album meta data among your devices, restore album meta data during reinstall. Never lost your album cover again.

## Version 1.5.2
* add Traditional Chinese translation
* fix Japanese translation which prevent gradle from building
* other improvements and bugs fixed

## Version 1.5.1
* new Japanese translation
* other improvements and bugs fixed

## Version 1.5.0
* support storing media files in external SD card
* allow IPv6 server address
* Other improvements and bugs fixed

## Version 1.4.8
* Revamp adding media file dialog, now you can choose move instead of copy when sharing files from Camera Roll or another Les Pas album
* Fully comply to Android's scoped storage guideline
* More robust synchronization
* Updated German translation thanks to Github user @BettaGeorge
* Other improvements and bugs fixed

## Version 1.4.7
* Fixed wrong date displayed when viewing single image file
* Fixed media thumbnail loading in Android 10 and above
* Other improvements and bugs fixed

## Version 1.4.6
* Overhaul camera roll management, fixed various bugs
* Use ExoPlayer for video playing
* Request storage access permission before enabling camera roll auto backup
* Other improvements and bugs fixed
* Last but not least, preview of photo search function, you can search in your albums or your phone's camera roll for object of animal, plant, food and vehicle

## Version 1.4.5
* fixed bug when processing photo creation date information from EXIF
* fixed bug when viewing super high resolution photo
* other improvements and bugs fixed

## Version 1.4.4
* support server using self-signed certificate
* bugs fixed

## Version 1.4.3
* fixed a serious bug that cause syncing on mobile network even though "Sync only on WiFi network" is on (a nice user just lost money because of this bug, it's really terrible)
* fixed various syncing bugs introduced along with Snapseed integration feature

## Version 1.4.2
* Bugs fixed and other enhancements

## Version 1.4.1
* fixed crashes when sharing photo, setting photo as wallpaper and setting photo as album cover, when the photo is not yet synced to server

## Version 1.4
* Can auto backup camera roll to Nextcloud server
* Browse camera roll function, both in Les Pas app or launched in launcher
* Can be set as system default viewer for supported image and video file
* Added mute and replay function to video files
* Auto mute video playing during 22:00 to 7:00
* Resume video playback after interrupted
* Fixed crash when viewing detail information of media file which is not yet uploaded
* Other bugs fixed;

## Version 1.3
* Browse camera roll function, both in Les Pas app or launched in launcher
* Can be set as system default viewer for supported image and video file
* Added mute and replay function to video files
* Auto mute video playing during 22:00 to 7:00
* Fixed crash when viewing detail information of media file which is not yet uploaded
* Other bugs fixed

## Version 1.2
* upgrade okhttp to latest version to fix a upstream bug that crashes login

## Version 1.1
* more accurate media creation date
* bugs fixed

## Version 1.0
* First release