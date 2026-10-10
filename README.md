# Wi-Fi Share

Send files between an Android phone and a PC over your local Wi-Fi. No cloud, no internet.
The phone runs a small server; the PC opens a link in any browser and types a 6-digit code.

## Get the APK (GitHub builds it for you)

1. Create a new GitHub repository.
2. Upload everything inside this folder to the repository root, including the hidden `.github` folder.
   If `.github` is missing after the upload, click Add file > Create new file, name it
   `.github/workflows/build-apk.yml`, and paste in the contents of `build-apk.yml`.
3. Open the Actions tab. The "Build APK" run starts by itself (or click Run workflow). It takes about 5 minutes.
4. Open the finished run, scroll to Artifacts, and download `WiFiShare-apk`. Unzip it to get `app-debug.apk`.
5. Install it on the phone. Android will ask you to allow installs from this source.

## Use it

1. Open Wi-Fi Share on the phone and tap Start sharing.
2. Put the PC on the same Wi-Fi (or join the phone's hotspot).
3. Open the link shown on the phone, for example http://192.168.1.23:8080, and type the code.
4. Drop files on the page to send them to the phone. They land in Downloads/WiFiShare.
   To get files from the phone, tap Add files in the app, then press Download on the page.

Needs Android 10 or newer.

## Notes

- Transfers use plain HTTP on your local network. Anyone on the same Wi-Fi who has the code can connect.
  The server locks out an address for a minute after 8 wrong codes.
- The code changes every time you start sharing.
- `tools/ServerSelfTest.java` tests the server on a normal computer (instructions at the top of the file).
