# Ava Bedtime

First Android app for Ava's phone: play a Plex playlist over Bluetooth, restart on stir (mic + motion), stop after a timer.

## What works in this scaffold

- App UI with timer + sensitivity controls
- Foreground bedtime service + notification
- Demo audio loop (so Start works before Plex is wired)
- Mic + motion stir detection that restarts playback
- Settings saved on device (including Plex URL / token fields for next step)

## Open in Android Studio (first time)

1. Finish installing **Android Studio** and open it.
2. Go through the setup wizard — install the default **Android SDK**.
3. **File → Open** and choose this folder:
   `C:\Users\machi\Desktop\Avas bedtime app`
4. When prompted, trust the project and let Gradle sync (first sync downloads a lot; can take several minutes).
5. Plug in Ava's phone with a USB cable.
6. On the phone: **Settings → About phone → tap Build number 7 times** → back → **Developer options → USB debugging ON**.
7. Accept the “Allow USB debugging?” prompt on the phone.
8. In Android Studio, pick her phone in the device dropdown and press the green **Run** button.

The app installs and launches. Connect the Bluetooth speaker in Android Bluetooth settings (not inside the app), then tap **Start bedtime**.

## Battery tip

On her phone, set this app to **Unrestricted** battery use so Android does not kill overnight playback.

## Next build steps (in Cursor)

1. Real Plex playlist streaming (replace demo audio)
2. Playlist picker instead of pasting playlist ID
3. Tune stir thresholds with real bedtime tests

## Note on Java

Android Studio includes its own JDK 17. You do not need to fix the old Java 8 on this PC for this project.
