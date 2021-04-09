![Feature graphic](fastlane//metadata/android/en-US/images/featureGraphic.png)

## A photo album that saves all your precious memory in the private Nextcloud server
<a href='https://play.google.com/store/apps/details?id=site.leos.apps.lespas'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height='50'/></a>
<a href='https://f-droid.org/packages/site.leos.apps.lespas/'><img alt='Get it on F-Droid' src='https://fdroid.gitlab.io/artwork/badge/get-it-on.png' height='50'></a>

Les Pas, is a free, modern, lightweight and fast gallery app. Organize your photos, GIFs and videos into albums for easy viewing and sharing. With built-in two-way sync with your Nextcloud server, your files are kept private, secure and safe.

Features:
* Simple, beautiful and fast photos & videos viewing
* View picture details
* Organized albums
* Manage your phone's camera roll and auto backup to server
* Synchronization among your Nextcloud server and multiple devices
* Manage albums and photos on Nextcloud server and also on your phones
* Search for photos by objects
* Integrate Snapseed for photo editing
* Share to social networks
* Theme design inspired by Wes Anderson's works
* All files saved in App's private storage, stop being scanned by malicious apps
* Open-source

This project build using the following open source software:
- <a href=https://github.com/thegrizzlylabs/sardine-android>Sardine-android</a>
- <a href=https://github.com/chrisbanes/PhotoView>PhotoView</a>
- <a href=https://www.tensorflow.org>TensorFlow</a>

<a id="faq"></a>
## Faq
### Why organzied by album?
I believe when someone start searching his/her memory for a moment in the past, it's hard for him/her to recall the exact date or exact location, but rather easy to remember what was happening during that period of time, like kid's birthday or family trip to Paris. So organized photos by events is probably the best way for most people, therefore grouping photos by event into an album is the best choice.

### Why use folder but not tag to group photos?
Les Pas use folder to group photos on the server, e.g., each album in Les Pas app has a one to one relationship with a folder on your Nextcloud server. You can manage your photo collection by working with folders/files on server side or albums/photos on your phone, Les Pas will sync changes from both sides. But how about tags? Yes, tagging is much more flexible than folder, and Nextcloud has it's own file tagging support too. But not every picture format support tagging, that makes tagging picture file a feature that will heavily rely on platform speicific functions. I would like my data (and yours too) to be platform neutual instead.

### Why does Les Pas use a lot of storage space?
Les Pas store photos in it's app private storage, so if you have a large collection of photos, you will find that it use a lot of storage space (I have 10GB myself) in Android's setting menu.<br> 
There are two reasons why Les Pas use private storage. First, Android introduced scope storage policy recently, highly recommends apps to stay out of share storage area. Second, storing photos in apps private storage area can prevent malicious apps scanning, uploading your photo secretly in the backgroud. Yes, they love your pictures so much, especially those with your face in it.<br>
**For privacy sake, stop using "/Pictures" folder in your phone's internal/external storage.**<br><br>
Since Les Pas use app's private storage to stop photos, if you reinstall the app, albums/photos need to be downloaded again from server.

### About server using self-signed certificate
You need to install your certificates in your phone first. A quick search on instructions points to <a href=https://aboutssl.org/how-to-create-and-import-self-signed-certificate-to-android-device/>here</a> and <a href=https://proxyman.io/blog/2020/09/Install-And-Trust-Self-Signed-Certificate-On-Android-11.html>here</a>.

### About synchronization
Les Pas does two types of sync in the background. A two-way sync of your albums and a one-way backup of your phone's camera roll.<br>
Whenever you did something with your albums on your phone, Les Pas will synchronize the changes to your server immediately. Since Nextcloud's push notification only work with Google Firebase Cloud Messaging, which Les Pas decided not to support due to privacy concern, any changes you make to your albums on server side will be synced to your phone during the next synchonization cycle.<br>
Upon opening Les Pas app, it will sync with server once. If you enable periodic sync setting, Les Pas will synchronize with your server every 6 hours in the background.<br>
One-way backup of phone's camera roll is a background job which also happen every 6 hours. So don't delete photos from your camera roll too fast too soon.
 
 
 
