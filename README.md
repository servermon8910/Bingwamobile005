# Bingwa Mobile

## Requirements

- JDK 17 (Android Gradle Plugin 8.x)
- Android SDK (compileSdk 34)
- Gradle installed (or use Android Studio)

## Build (debug APK)

```bash
bash scripts/gradle17 assembleDebug
```

Or, if you manage Java yourself:

```bash
JAVA_HOME=/path/to/jdk17 PATH=$JAVA_HOME/bin:$PATH gradle assembleDebug
```

The output APK is:

`app/build/outputs/apk/debug/app-debug.apk`
