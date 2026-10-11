# 🚀 How to Build APK for DroidNode

### Option 1: Using Android Studio (Recommended - 1 Click)
1. Open **Android Studio**.
2. Click **File -> Open...** and select this extracted project folder.
3. Wait for Gradle sync to complete.
4. From the top menu, click **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
5. In the bottom-right popup, click **locate** to find your compiled file:
   `app/build/outputs/apk/debug/app-debug.apk`
6. Transfer `app-debug.apk` to your phone and install!

### Option 2: Using Terminal / Command Line
Run this command inside the project root:
```bash
./gradlew assembleDebug
```
The APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Storage Location:
The app is configured to bind and serve from your internal storage:
```text
/sdcard/www/index.html
```
Users can edit this file on Android with apps like **Acode**, **QuickEdit**, or **MT Manager**!
